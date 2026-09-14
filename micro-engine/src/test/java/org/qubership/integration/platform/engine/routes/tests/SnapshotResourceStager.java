package org.qubership.integration.platform.engine.routes.tests;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotResourceDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

final class SnapshotResourceStager implements AutoCloseable {
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String CAMEL_PROCESSED_DIRECTORY = ".camel";
    private static final Path CHAIN_TEMP_DIRECTORY = Path.of("/tmp/chain_tmp")
            .toAbsolutePath()
            .normalize();
    private static final Path STAGING_LOCK_FILE = CHAIN_TEMP_DIRECTORY.resolveSibling(
            "qip-snapshot-chain-resources.lock"
    );
    private static final ReentrantLock JVM_STAGING_LOCK = new ReentrantLock(true);

    private final List<Path> stagedFiles = new ArrayList<>();
    private final Set<Path> createdDirectories = new LinkedHashSet<>();
    private final ReentrantLock jvmStagingLock;
    private final FileChannel stagingLockChannel;
    private final FileLock stagingLock;

    SnapshotResourceStager() throws IOException {
        this(STAGING_LOCK_FILE, JVM_STAGING_LOCK);
    }

    SnapshotResourceStager(Path stagingLockFile, ReentrantLock jvmStagingLock) throws IOException {
        this.jvmStagingLock = jvmStagingLock;
        jvmStagingLock.lock();
        FileChannel lockChannel = null;
        try {
            lockChannel = FileChannel.open(
                    stagingLockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            stagingLock = lockChannel.lock();
            stagingLockChannel = lockChannel;
        } catch (IOException | RuntimeException exception) {
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            jvmStagingLock.unlock();
            throw exception;
        }
    }

    void stage(List<SnapshotResourceDefinition> resources) throws IOException {
        for (SnapshotResourceDefinition resource : resources) {
            stage(resource);
        }
    }

    private void stage(SnapshotResourceDefinition resource) throws IOException {
        Path target = resolveTarget(resource);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Snapshot resource target already exists and cannot be replaced: " + target
            );
        }

        trackDefaultCamelProcessedFile(target);
        rejectSymbolicLinkDirectories(target.getParent());
        createParentDirectories(target.getParent());
        rejectSymbolicLinkDirectories(target.getParent());
        Path temporaryFile = Files.createTempFile(
                target.getParent(),
                ".snapshot-resource-",
                ".tmp"
        );
        stagedFiles.add(temporaryFile);
        try (InputStream inputStream = openSource(resource)) {
            Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
        }
        rejectSymbolicLinkDirectories(target.getParent());
        Files.move(temporaryFile, target);
        stagedFiles.add(target);
    }

    private void trackDefaultCamelProcessedFile(Path target) throws IOException {
        Path processedDirectory = target.getParent().resolve(CAMEL_PROCESSED_DIRECTORY);
        Path processedFile = processedDirectory.resolve(target.getFileName());
        rejectSymbolicLinkDirectories(processedDirectory);
        if (!Files.exists(processedFile, LinkOption.NOFOLLOW_LINKS)) {
            stagedFiles.add(processedFile);
        }
        if (!Files.exists(processedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            createdDirectories.add(processedDirectory);
        }
    }

    private static Path resolveTarget(SnapshotResourceDefinition resource) throws IOException {
        Path relativeTarget = Path.of(resource.getTarget()).normalize();
        Path target = CHAIN_TEMP_DIRECTORY.resolve(relativeTarget).normalize();
        if (relativeTarget.isAbsolute()
                || relativeTarget.getNameCount() == 0
                || !target.startsWith(CHAIN_TEMP_DIRECTORY)
                || target.equals(CHAIN_TEMP_DIRECTORY)) {
            throw new IOException(
                    "Snapshot resource target must stay inside the chain temporary directory: "
                            + resource.getTarget()
            );
        }
        return target;
    }

    private static void rejectSymbolicLinkDirectories(Path parent) throws IOException {
        Path directory = CHAIN_TEMP_DIRECTORY;
        if (Files.isSymbolicLink(directory)) {
            throw symbolicLinkDirectory(directory);
        }
        for (Path name : CHAIN_TEMP_DIRECTORY.relativize(parent)) {
            directory = directory.resolve(name);
            if (Files.isSymbolicLink(directory)) {
                throw symbolicLinkDirectory(directory);
            }
        }
    }

    private static IOException symbolicLinkDirectory(Path directory) {
        return new IOException(
                "Snapshot resources cannot use a symbolic-link directory: " + directory
        );
    }

    private static InputStream openSource(SnapshotResourceDefinition resource) throws IOException {
        String source = resource.getSource();
        if (!source.startsWith(CLASSPATH_PREFIX)) {
            throw new IOException(
                    "Snapshot resource source must use the classpath scheme: " + source
            );
        }

        String resourceName = source.substring(CLASSPATH_PREFIX.length());
        while (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }
        InputStream inputStream = SnapshotResourceStager.class.getClassLoader()
                .getResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IOException("Snapshot classpath resource is missing: " + source);
        }
        return inputStream;
    }

    private void createParentDirectories(Path parent) throws IOException {
        Path directory = parent;
        while (directory != null
                && directory.startsWith(CHAIN_TEMP_DIRECTORY)
                && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            createdDirectories.add(directory);
            directory = directory.getParent();
        }
        Files.createDirectories(parent);
    }

    @Override
    public void close() throws IOException {
        IOException cleanupFailure = null;
        for (int index = stagedFiles.size() - 1; index >= 0; index--) {
            cleanupFailure = delete(stagedFiles.get(index), cleanupFailure);
        }
        List<Path> directories = new ArrayList<>(createdDirectories);
        directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : directories) {
            try {
                Files.deleteIfExists(directory);
            } catch (DirectoryNotEmptyException ignored) {
                // Preserve files that another process created while the snapshot scenario was running.
            } catch (IOException exception) {
                cleanupFailure = append(cleanupFailure, exception);
            }
        }
        try {
            stagingLock.release();
        } catch (IOException exception) {
            cleanupFailure = append(cleanupFailure, exception);
        }
        try {
            stagingLockChannel.close();
        } catch (IOException exception) {
            cleanupFailure = append(cleanupFailure, exception);
        } finally {
            jvmStagingLock.unlock();
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static IOException delete(Path path, IOException cleanupFailure) {
        try {
            Files.deleteIfExists(path);
            return cleanupFailure;
        } catch (IOException exception) {
            return append(cleanupFailure, exception);
        }
    }

    private static IOException append(IOException cleanupFailure, IOException exception) {
        if (cleanupFailure == null) {
            return exception;
        }
        cleanupFailure.addSuppressed(exception);
        return cleanupFailure;
    }
}

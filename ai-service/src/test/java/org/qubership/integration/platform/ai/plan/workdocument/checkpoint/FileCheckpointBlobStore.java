package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;

/**
 * File-backed artifact store. A new instance on the same directory sees blobs written by an
 * earlier process. This is the local stand-in for the durable run-store path.
 */
public final class FileCheckpointBlobStore implements ArtifactBlobStore {

  private static final String VERSION_SUFFIX = ".version";

  private final Path root;

  private FileCheckpointBlobStore(Path root) {
    this.root = root;
  }

  /** Directory a later process reopens to read blobs written here. */
  Path root() {
    return root;
  }

  public static FileCheckpointBlobStore open(Path root) {
    if (root == null) {
      throw new IllegalStateException("STORE_UNAVAILABLE: durable artifact directory is missing.");
    }
    if (Files.exists(root) && !Files.isDirectory(root)) {
      throw new IllegalStateException(
          "STORE_UNAVAILABLE: durable artifact path is not a directory: " + root);
    }
    try {
      Files.createDirectories(root);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "STORE_UNAVAILABLE: cannot open durable artifact directory: " + root, failure);
    }
    if (!Files.isWritable(root)) {
      throw new IllegalStateException(
          "STORE_UNAVAILABLE: durable artifact directory is not writable: " + root);
    }
    return new FileCheckpointBlobStore(root);
  }

  @Override
  public void put(String key, byte[] content) {
    synchronized (root) {
      VersionedBlob current = getVersioned(key).orElse(null);
      long next = current == null ? 1L : Long.parseLong(current.version()) + 1L;
      write(key, content, Long.toString(next));
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    Path file = resolve(key);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException failure) {
      throw new IllegalStateException("STORE_UNAVAILABLE: cannot read " + key, failure);
    }
  }

  @Override
  public List<String> list(String prefix) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      List<String> keys = new ArrayList<>();
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        String key = root.relativize(file).toString().replace('\\', '/');
        if (key.endsWith(VERSION_SUFFIX)) {
          continue;
        }
        if (prefix == null || key.startsWith(prefix)) {
          keys.add(key);
        }
      }
      keys.sort(Comparator.naturalOrder());
      return List.copyOf(keys);
    } catch (IOException failure) {
      throw new IllegalStateException("STORE_UNAVAILABLE: cannot list " + root, failure);
    }
  }

  @Override
  public Optional<VersionedBlob> getVersioned(String key) {
    Optional<byte[]> content = get(key);
    if (content.isEmpty()) {
      return Optional.empty();
    }
    Path versionFile = versionFile(key);
    String version = "1";
    if (Files.isRegularFile(versionFile)) {
      try {
        version = Files.readString(versionFile).trim();
      } catch (IOException failure) {
        throw new IllegalStateException("STORE_UNAVAILABLE: cannot read version for " + key, failure);
      }
    }
    return Optional.of(new VersionedBlob(content.get(), version));
  }

  @Override
  public void putIfVersion(String key, byte[] content, String expectedVersion) {
    synchronized (root) {
      Optional<VersionedBlob> existing = getVersioned(key);
      if (expectedVersion == null) {
        if (existing.isPresent()) {
          throw new StaleBlobVersionException("create-only write lost for key " + key);
        }
        write(key, content, "1");
        return;
      }
      if (existing.isEmpty() || !expectedVersion.equals(existing.get().version())) {
        throw new StaleBlobVersionException("stale version for key " + key);
      }
      long next = Long.parseLong(existing.get().version()) + 1L;
      write(key, content, Long.toString(next));
    }
  }

  private void write(String key, byte[] content, String version) {
    Path file = resolve(key);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, content);
      Files.writeString(versionFile(key), version);
    } catch (IOException failure) {
      throw new IllegalStateException("STORE_UNAVAILABLE: cannot write " + key, failure);
    }
  }

  private Path versionFile(String key) {
    return resolve(key + VERSION_SUFFIX);
  }

  private Path resolve(String key) {
    if (key == null || key.isBlank() || key.contains("..")) {
      throw new IllegalArgumentException("Invalid artifact key.");
    }
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root)) {
      throw new IllegalArgumentException("Artifact key escapes the store directory.");
    }
    return file;
  }
}

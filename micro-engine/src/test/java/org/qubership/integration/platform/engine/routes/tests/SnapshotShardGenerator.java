package org.qubership.integration.platform.engine.routes.tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class SnapshotShardGenerator {
    private static final String PACKAGE_NAME = "org.qubership.integration.platform.engine.routes.tests";

    private SnapshotShardGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Expected generated source directory, report directory, worker directory, worker count, "
                            + "and compiled test directory");
        }
        generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Integer.parseInt(args[3]), Path.of(args[4]));
    }

    static void generate(Path generatedSourceRoot, Path reportDirectory, Path workerRoot, int workerCount,
                         Path compiledTestRoot) throws IOException {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("snapshot.workers must be greater than zero: " + workerCount);
        }
        Path packageDirectory = generatedSourceRoot.resolve(PACKAGE_NAME.replace('.', '/'));
        Files.createDirectories(packageDirectory);
        Files.createDirectories(reportDirectory);
        deleteMatchingFiles(packageDirectory, "MicroEngineSnapshotShard[0-9]+Test\\.java");
        deleteMatchingFiles(reportDirectory, "shard-[0-9]+\\.json|report\\.done|summary\\.json");
        String shardClassPattern = Pattern.quote(PACKAGE_NAME + ".MicroEngineSnapshotShard") + "[0-9]+Test";
        deleteMatchingFiles(reportDirectory.toAbsolutePath().getParent(),
                "TEST-" + shardClassPattern + "\\.xml|" + shardClassPattern + "\\.txt");
        Path compiledPackageDirectory = compiledTestRoot.resolve(PACKAGE_NAME.replace('.', '/'));
        if (Files.isDirectory(compiledPackageDirectory)) {
            deleteMatchingFiles(compiledPackageDirectory, "MicroEngineSnapshotShard[0-9]+Test\\.class");
        }

        for (int index = 0; index < workerCount; index++) {
            String className = "MicroEngineSnapshotShard" + index + "Test";
            String source = """
                    package %s;

                    import io.quarkus.test.component.QuarkusComponentTestExtension;

                    @SnapshotShard(index = %d, count = %d)
                    final class %s extends MicroEngineSnapshotRouteExecutionTest {
                        // Quarkus discovers component configuration through fields declared on each test class.
                        static final QuarkusComponentTestExtension COMPONENT_CONFIGURATION = COMPONENT_TEST;
                    }
                    """.formatted(PACKAGE_NAME, index, workerCount, className);
            Files.writeString(packageDirectory.resolve(className + ".java"), source);
            Files.createDirectories(workerRoot.resolve(Integer.toString(index + 1)).resolve("arc-classes"));
        }
    }

    private static void deleteMatchingFiles(Path directory, String filenamePattern) throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(filenamePattern)).toList()) {
                Files.delete(path);
            }
        }
    }
}

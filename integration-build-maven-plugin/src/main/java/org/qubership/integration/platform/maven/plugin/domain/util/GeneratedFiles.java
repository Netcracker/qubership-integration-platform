package org.qubership.integration.platform.maven.plugin.domain.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

public final class GeneratedFiles {
    public static final String LIST_FILE_PREFIX = ".cip-generated-";

    private GeneratedFiles() {}

    public static void record(Path file, String artifactId) throws IOException {
        Path list = file.getParent().resolve(LIST_FILE_PREFIX + artifactId);
        String name = file.getFileName().toString();
        if (Files.exists(list) && Files.readAllLines(list, UTF_8).contains(name)) {
            return;
        }
        Files.writeString(list, name + "\n", UTF_8, CREATE, APPEND);
    }

    public static List<Path> deleteRecorded(Path outputDirectory, String artifactId) throws IOException {
        Path list = outputDirectory.resolve(LIST_FILE_PREFIX + artifactId);
        List<Path> deleted = new ArrayList<>();
        if (!Files.exists(list)) {
            return deleted;
        }
        for (String name : Files.readAllLines(list, UTF_8)) {
            if (name.isBlank()) {
                continue;
            }
            Path file = outputDirectory.resolve(name);
            if (Files.deleteIfExists(file)) {
                deleted.add(file);
            }
        }
        Files.delete(list);
        return deleted;
    }
}

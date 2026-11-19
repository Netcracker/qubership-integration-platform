package org.qubership.integration.platform.maven.plugin.domain.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedFilesTest {
    @TempDir
    Path directory;

    @Test
    void recordsANameOnce() throws IOException {
        GeneratedFiles.record(directory.resolve("a.yaml"), "orders-app");
        GeneratedFiles.record(directory.resolve("a.yaml"), "orders-app");
        GeneratedFiles.record(directory.resolve("b.jar"), "orders-app");

        assertEquals(List.of("a.yaml", "b.jar"), Files.readAllLines(directory.resolve(".cip-generated-orders-app")));
    }

    @Test
    void deletesListedFilesAndKeepsOthers() throws IOException {
        Path a = Files.createFile(directory.resolve("a.yaml"));
        Path b = Files.createFile(directory.resolve("b.jar"));
        Path values = Files.createFile(directory.resolve("values.yaml"));
        GeneratedFiles.record(a, "orders-app");
        GeneratedFiles.record(b, "orders-app");

        assertEquals(List.of(a, b), GeneratedFiles.deleteRecorded(directory, "orders-app"));

        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(directory.resolve(".cip-generated-orders-app")));
        assertTrue(Files.exists(values));
    }

    @Test
    void skipsAListedFileThatIsGone() throws IOException {
        GeneratedFiles.record(directory.resolve("a.yaml"), "orders-app");

        assertEquals(List.of(), GeneratedFiles.deleteRecorded(directory, "orders-app"));

        assertFalse(Files.exists(directory.resolve(".cip-generated-orders-app")));
    }

    @Test
    void doesNothingWithoutAList() throws IOException {
        assertEquals(List.of(), GeneratedFiles.deleteRecorded(directory.resolve("missing"), "orders-app"));
    }

    @Test
    void keepsAnotherModulesFiles() throws IOException {
        Path a = Files.createFile(directory.resolve("a.yaml"));
        Path b = Files.createFile(directory.resolve("b.yaml"));
        GeneratedFiles.record(a, "module-a");
        GeneratedFiles.record(b, "module-b");

        assertEquals(List.of(b), GeneratedFiles.deleteRecorded(directory, "module-b"));

        assertTrue(Files.exists(a));
        assertTrue(Files.exists(directory.resolve(".cip-generated-module-a")));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(directory.resolve(".cip-generated-module-b")));
    }
}

package org.qubership.integration.platform.runtime.catalog.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChainUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void mapHashEqualsFileHash() throws Exception {
        String version = "test-version";
        Files.write(tempDir.resolve("b.yaml"), "content-b".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("a.yaml"), "content-a".getBytes(StandardCharsets.UTF_8));

        String fileHash = ChainUtils.getChainFilesHash(tempDir.toFile(), version);

        Map<String, byte[]> filesByName = new HashMap<>();
        filesByName.put("b.yaml", "content-b".getBytes(StandardCharsets.UTF_8));
        filesByName.put("a.yaml", "content-a".getBytes(StandardCharsets.UTF_8));

        String mapHash = ChainUtils.getChainFilesHash(filesByName, version);

        assertEquals(fileHash, mapHash);
    }

    @Test
    void emptyMapReturnsZero() throws Exception {
        assertEquals("0", ChainUtils.getChainFilesHash(Map.of(), "v"));
        File emptyDir = tempDir.toFile();
        // tempDir is empty at start of this test (fresh @TempDir per method)
        assertEquals("0", ChainUtils.getChainFilesHash(emptyDir, "v"));
    }
}

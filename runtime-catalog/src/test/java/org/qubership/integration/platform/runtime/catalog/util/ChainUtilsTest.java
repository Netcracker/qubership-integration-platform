package org.qubership.integration.platform.runtime.catalog.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChainUtilsTest {

    private static final String VERSION = "test-version";

    @Test
    void emptyDirectoryReturnsZero(@TempDir Path chainDir) throws Exception {
        assertEquals("0", ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION));
    }

    @Test
    void scriptInResourcesSubdirectoryAffectsHash(@TempDir Path chainDir) throws Exception {
        Files.writeString(chainDir.resolve("chain-id.chain.cip.yaml"), "id: chain-id");
        Path resources = chainDir.resolve("resources");
        Files.createDirectories(resources);
        Path script = resources.resolve("element-id.element.script.cip.groovy");
        Files.writeString(script, "println 'v1'");

        String before = ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION);

        Files.writeString(script, "println 'v2'");

        assertNotEquals(before, ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION));
    }

    @Test
    void topLevelChangeAffectsHash(@TempDir Path chainDir) throws Exception {
        Path chainFile = chainDir.resolve("chain-id.chain.cip.yaml");
        Files.writeString(chainFile, "id: chain-id");

        String before = ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION);

        Files.writeString(chainFile, "id: chain-id-changed");

        assertNotEquals(before, ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION));
    }

    @Test
    void hashIsDeterministic(@TempDir Path chainDir) throws Exception {
        Files.writeString(chainDir.resolve("chain-id.chain.cip.yaml"), "id: chain-id");
        Path resources = chainDir.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("element-id.element.script.cip.groovy"), "println 'v1'");

        assertEquals(
                ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION),
                ChainUtils.getChainFilesHash(chainDir.toFile(), VERSION));
    }
}

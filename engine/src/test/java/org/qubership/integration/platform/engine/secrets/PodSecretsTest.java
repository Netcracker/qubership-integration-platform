package org.qubership.integration.platform.engine.secrets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PodSecretsTest {

    @TempDir
    Path basePath;

    private Path mount;

    @BeforeEach
    void createMount() throws IOException {
        mount = Files.createDirectory(basePath.resolve(PodSecrets.DEFAULT_SECRET_FILE));
    }

    @Test
    void readsTheLowerCaseFileOfAnUpperCaseProperty() throws IOException {
        Files.writeString(mount.resolve("consul_admin_token"), "from-the-mount");

        assertEquals("from-the-mount", read("CONSUL_ADMIN_TOKEN"));
    }

    @Test
    void readsTheFileNamedAsTheProperty() throws IOException {
        Files.writeString(mount.resolve("CONSUL_ADMIN_TOKEN"), "from-the-mount");

        assertEquals("from-the-mount", read("CONSUL_ADMIN_TOKEN"));
    }

    @Test
    void keepsTheBytesOfTheFile() throws IOException {
        Files.writeString(mount.resolve("consul_admin_token"), " token \n");

        assertEquals(" token \n", read("CONSUL_ADMIN_TOKEN"));
    }

    @Test
    void answersNullForAPropertyNeitherTheMountNorTheEnvironmentCarries() {
        assertNull(read("QIP_SECRET_NO_ONE_SETS"));
    }

    @Test
    void refusesANameThatLeavesTheMount() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> read("../../etc/passwd"));

        Path outsideTheMount = basePath.getParent().resolve("etc").resolve("passwd");
        assertEquals("Failed to read secret file " + outsideTheMount
                + " for key ../../etc/passwd. Reason: Directory injection or path traversal detected",
                exception.getMessage());
    }

    @Test
    void refusesANameCarryingASeparator() {
        assertThrows(IllegalStateException.class, () -> read("nested/consul_admin_token"));
    }

    private String read(String propertyName) {
        return PodSecrets.read(propertyName, PodSecrets.DEFAULT_SECRET_FILE, basePath.toString());
    }
}

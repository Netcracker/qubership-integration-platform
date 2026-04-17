package org.qubership.integration.platform.engine.component.profile;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ConsulTestResource implements QuarkusTestResourceLifecycleManager {

    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("hashicorp/consul:1.15.4");
    private static final int CONSUL_HTTP_PORT = 8500;

    private GenericContainer<?> consul;
    private String managementToken;
    private Path consulAclConfigPath;

    @Override
    public Map<String, String> start() {
        managementToken = UUID.randomUUID().toString();
        consulAclConfigPath = createConsulAclConfig(managementToken);

        consul = new GenericContainer<>(CONSUL_IMAGE)
                .withExposedPorts(CONSUL_HTTP_PORT)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("consul/server.json"),
                        "/consul/config/server.json"
                )
                .withCopyFileToContainer(
                        MountableFile.forHostPath(consulAclConfigPath),
                        "/consul/config/consul-acl.json"
                )
                .withCommand("agent", "-bootstrap-expect=1")
                .waitingFor(
                        Wait.forHttp("/v1/status/leader")
                                .forStatusCode(200)
                )
                .withStartupTimeout(Duration.ofSeconds(90));

        consul.start();

        String consulUrl = "http://" + consul.getHost() + ":" + consul.getMappedPort(CONSUL_HTTP_PORT);

        return Map.of(
                "CONSUL_URL", consulUrl,
                "consul.url", consulUrl,
                "quarkus.consul-source-config.agent.url", consulUrl,
                "consul.token", managementToken,
                "CONSUL_HTTP_TOKEN", managementToken
        );
    }

    @Override
    public void stop() {
        if (consul != null) {
            consul.stop();
            consul = null;
        }

        if (consulAclConfigPath != null) {
            try {
                Files.deleteIfExists(consulAclConfigPath);
            } catch (IOException ignored) {
                // no-op
            }
            consulAclConfigPath = null;
        }

        managementToken = null;
    }

    private static Path createConsulAclConfig(String managementToken) {
        String consulAclJson = """
                {
                  "acl": {
                    "enabled": true,
                    "default_policy": "deny",
                    "down_policy": "extend-cache",
                    "enable_token_persistence": true,
                    "tokens": {
                      "initial_management": "%s"
                    }
                  }
                }
                """.formatted(managementToken);

        try {
            Path tempFile = Files.createTempFile("consul-acl-", ".json");
            Files.writeString(tempFile, consulAclJson);

            try {
                Files.setPosixFilePermissions(
                        tempFile,
                        Set.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.OTHERS_READ
                        )
                );
            } catch (UnsupportedOperationException ignored) {
                // no-op
            }

            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary consul ACL config", e);
        }
    }
}

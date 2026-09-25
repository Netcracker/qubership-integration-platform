package org.qubership.integration.platform.engine.routes.fixture;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/** Keeps fixture administration available when the sender rejects the TLS certificate. */
final class KafkaTlsContainer extends KafkaSnapshotContainer {
    private static final int ADMIN_PORT = 9095;
    private static final String BROKER_KEYSTORE = "/tmp/kafka-fixture.p12";

    private final int proxyPort;

    KafkaTlsContainer(String image, int proxyPort, KafkaTlsMaterial material) {
        super(image);
        this.proxyPort = proxyPort;
        KafkaSaslSupport.configure(this);
        KafkaSaslSupport.configureListener(this, "ADMIN");
        withExposedPorts(9092, ADMIN_PORT);
        withEnv("KAFKA_LISTENERS",
                "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094,ADMIN://0.0.0.0:9095");
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                "PLAINTEXT:SASL_SSL,ADMIN:SASL_PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT");
        withEnv("KAFKA_SSL_KEYSTORE_LOCATION", BROKER_KEYSTORE);
        withEnv("KAFKA_SSL_KEYSTORE_TYPE", "PKCS12");
        withEnv("KAFKA_SSL_KEYSTORE_PASSWORD", material.keyStorePassword());
        withEnv("KAFKA_SSL_KEY_PASSWORD", material.keyStorePassword());
        withEnv("KAFKA_SSL_CLIENT_AUTH", "none");
        withCopyFileToContainer(MountableFile.forHostPath(material.brokerKeyStore(), 0444), BROKER_KEYSTORE);
    }

    @Override
    public String getBootstrapServers() {
        return "127.0.0.1:" + proxyPort;
    }

    String adminBootstrapServers() {
        return getHost() + ':' + getMappedPort(ADMIN_PORT);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        // Advertise the plaintext ADMIN listener alongside the sender's TLS proxy.
        String advertisedListeners = "PLAINTEXT://" + getBootstrapServers()
                + ",BROKER://" + containerInfo.getConfig().getHostName() + ":9093"
                + ",ADMIN://" + adminBootstrapServers();
        String script = "#!/bin/bash\nexport KAFKA_ADVERTISED_LISTENERS='"
                + advertisedListeners.replace("'", "'\"'\"'") + "'\nexec /etc/kafka/docker/run\n";
        copyFileToContainer(Transferable.of(script, 0755), STARTER_SCRIPT);
    }
}

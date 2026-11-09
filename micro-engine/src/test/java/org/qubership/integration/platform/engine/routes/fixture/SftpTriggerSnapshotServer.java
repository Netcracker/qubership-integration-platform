package org.qubership.integration.platform.engine.routes.fixture;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SftpTriggerSnapshotServer implements AutoCloseable {
    private static final String DIRECTORY = "/upload/snapshot-trigger";
    private static final String INSPECTOR = "snapshot-inspector";
    private static final int SSH_PORT = 22;
    private static final Logger LOG = LoggerFactory.getLogger(SftpTriggerSnapshotServer.class);
    private final Map<String, Object> restartEvidence = new LinkedHashMap<>();
    private final AtomicLong inspectorConnections = new AtomicLong();
    private GenericContainer<?> container;
    private SftpTriggerSnapshotProxy proxy;
    private volatile int backendPort;
    private volatile boolean filesReadable = true;
    private boolean closed;

    void start() throws Exception {
        container = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
                .withExposedPorts(SSH_PORT)
                .withCommand("snapshot:snapshot:1001:1001:upload", INSPECTOR + ":inspector:1002:1001:upload")
                .withCopyToContainer(Transferable.of("""
                        #!/bin/sh
                        set -eu
                        sed -i 's/ForceCommand internal-sftp$/ForceCommand internal-sftp -u 0002/' /etc/ssh/sshd_config
                        if ! grep -q '^Match User snapshot-inspector$' /etc/ssh/sshd_config; then
                            cat >> /etc/ssh/sshd_config <<'CONFIG'
                        Match User snapshot-inspector
                            ChrootDirectory /home/snapshot
                        CONFIG
                        fi
                        mkdir -p /home/snapshot/upload/snapshot-trigger
                        chown -R 1001:1001 /home/snapshot/upload
                        chmod 0770 /home/snapshot/upload
                        chmod 0770 /home/snapshot/upload/snapshot-trigger
                        """, 0755), "/etc/sftp.d/10-snapshot.sh")
                .waitingFor(Wait.forLogMessage(".*Server listening on .* port 22.*", 1))
                .withStartupTimeout(Duration.ofSeconds(90));
        container.start();
        backendPort = container.getMappedPort(SSH_PORT);
        proxy = new SftpTriggerSnapshotProxy(container.getHost(), backendPort);
        try (Client client = connect()) {
            assertTrue(client.channel.stat(DIRECTORY).isDir(), "SFTP fixture directory must exist.");
        }
    }

    String host() {
        return "127.0.0.1";
    }

    int port() {
        return proxy.port();
    }

    String directory() {
        return DIRECTORY;
    }

    void upload(String relativeName, byte[] body, Integer mtimeSeconds) throws Exception {
        String destination = path(relativeName);
        int slash = destination.lastIndexOf('/');
        String temporary = destination.substring(0, slash + 1) + ".snapshot-" + UUID.randomUUID();
        try (Client client = connect()) {
            mkdirs(client.channel, destination.substring(0, slash));
            client.channel.put(new ByteArrayInputStream(body), temporary);
            if (mtimeSeconds != null) {
                client.channel.setMtime(temporary, mtimeSeconds);
            }
            client.channel.chmod(filesReadable ? 0660 : 0600, temporary);
            client.channel.rename(temporary, destination);
        }
    }

    SftpATTRS stat(String relativeName) throws Exception {
        try (Inspection inspection = inspect()) {
            return inspection.stat(relativeName);
        }
    }

    boolean exists(String relativeName) throws Exception {
        try (Inspection inspection = inspect()) {
            return inspection.exists(relativeName);
        }
    }

    private static boolean exists(ChannelSftp channel, String path) throws SftpException {
        try {
            channel.stat(path);
            return true;
        } catch (SftpException exception) {
            if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw exception;
        }
    }

    Inspection inspect() throws Exception {
        return new Inspection(connect());
    }

    long inspectorConnections() {
        return inspectorConnections.get();
    }

    static final class Inspection implements AutoCloseable {
        private final Client client;

        private Inspection(Client client) {
            this.client = client;
        }

        byte[] read(String relativeName) throws Exception {
            try (InputStream stream = client.channel.get(path(relativeName))) {
                return stream.readAllBytes();
            }
        }

        SftpATTRS stat(String relativeName) throws SftpException {
            return client.channel.stat(path(relativeName));
        }

        boolean exists(String relativeName) throws SftpException {
            return SftpTriggerSnapshotServer.exists(client.channel, path(relativeName));
        }

        @Override
        public void close() {
            client.close();
        }
    }

    void mkdir(String relativeDirectory) throws Exception {
        try (Client client = connect()) {
            mkdirs(client.channel, path(relativeDirectory));
        }
    }

    void remove(String relativeName) throws Exception {
        try (Client client = connect()) {
            String selected = path(relativeName);
            if (client.channel.stat(selected).isDir()) {
                client.channel.rmdir(selected);
            } else {
                client.channel.rm(selected);
            }
        }
    }

    void directoryPresent(boolean present) throws Exception {
        if (present) {
            execute("mkdir", "-p", "/home/snapshot" + DIRECTORY);
            execute("chown", "1001:1001", "/home/snapshot" + DIRECTORY);
            execute("chmod", "0770", "/home/snapshot" + DIRECTORY);
            permissions(true);
        } else {
            execute("rm", "-rf", "/home/snapshot" + DIRECTORY);
        }
    }

    void permissions(boolean allowed) throws Exception {
        filesReadable = allowed;
        String directory = "/home/snapshot" + DIRECTORY;
        execute("find", directory, "-type", "f", "-exec", "chown", "1002:1001", "{}", ";");
        execute("find", directory, "-type", "f", "-exec", "chmod", allowed ? "0660" : "0600", "{}", ";");
    }

    int probeDownloadFailure(String relativeName) throws Exception {
        try (Client client = connect("snapshot", "snapshot")) {
            client.channel.cd(DIRECTORY);
            client.channel.ls(".");
            try (InputStream input = client.channel.get(path(relativeName))) {
                input.readAllBytes();
                return ChannelSftp.SSH_FX_OK;
            } catch (SftpException exception) {
                return exception.id;
            }
        }
    }

    void password(String acceptedPassword) throws Exception {
        if (!acceptedPassword.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "SFTP fixture password must contain letters, digits, underscores, or hyphens.");
        }
        execute("sh", "-c", "printf '%s\\n' \"$1\" | chpasswd", "snapshot-password", "snapshot:" + acceptedPassword);
    }

    void listenerAvailable(boolean available) throws Exception {
        proxy.listenerAvailable(available);
    }

    void disconnect() {
        proxy.disconnect();
    }

    void interruptAfterServerBytes(long threshold) {
        proxy.interruptAfterServerBytes(threshold);
    }

    long interruptionCount() {
        return proxy.interruptionCount();
    }

    long interruptedServerBytes() {
        return proxy.interruptedServerBytes();
    }

    long forwardedServerBytes() {
        return proxy.forwardedServerBytes();
    }

    void restart() throws Exception {
        proxy.listenerAvailable(false);
        restartEvidence.put("portBeforeRestart", backendPort);
        restartEvidence.put("cachedPort", container.getMappedPort(SSH_PORT));
        container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
        InspectContainerResponse inspected = container.getDockerClient()
                .inspectContainerCmd(container.getContainerId()).exec();
        var bindings = inspected.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(SSH_PORT));
        assertNotNull(bindings, "Restarted SFTP container must publish its SSH port.");
        assertTrue(bindings.length > 0, "Restarted SFTP container must have an SSH host binding.");
        backendPort = Integer.parseInt(bindings[0].getHostPortSpec());
        restartEvidence.put("inspectedPort", backendPort);
        restartEvidence.put("running", inspected.getState().getRunning());
        LOG.info("SFTP server restarted: cached SSH port {}, inspected SSH port {}, running {}.",
                restartEvidence.get("cachedPort"), backendPort, inspected.getState().getRunning());
        var limiter = RateLimiterBuilder.newBuilder().withRate(10, TimeUnit.SECONDS).withConstantThroughput().build();
        try {
            Unreliables.retryUntilSuccess(20, TimeUnit.SECONDS, () -> limiter.getWhenReady(() -> {
                try (Client client = connect()) {
                    assertTrue(client.channel.stat("/upload").isDir(), "Restarted SFTP server must expose the upload directory.");
                    return true;
                }
            }));
        } catch (RuntimeException exception) {
            InspectContainerResponse failed = container.getDockerClient()
                    .inspectContainerCmd(container.getContainerId()).exec();
            restartEvidence.put("runningAfterFailure", failed.getState().getRunning());
            restartEvidence.put("containerError", String.valueOf(failed.getState().getError()));
            throw new IllegalStateException("Restarted SFTP server did not become ready: " + restartEvidence
                    + "\nServer logs:\n" + container.getLogs(), exception);
        }
        proxy.target(container.getHost(), backendPort);
        proxy.listenerAvailable(true);
    }

    Map<String, Object> restartEvidence() {
        return Map.copyOf(restartEvidence);
    }

    String logs() {
        return container.getLogs();
    }

    long authenticationFailures() {
        return logs().lines().filter(line -> line.contains("Failed password for snapshot ")
                || line.contains("Failed password for snapshot from")).count();
    }

    long acceptedConnections() {
        return proxy.acceptedConnections();
    }

    long closedConnections() {
        return proxy.closedConnections();
    }

    int activeConnections() {
        return proxy.activeConnections();
    }

    void verify() {
        proxy.verify();
        LOG.info("SFTP fixture used {} inspector connections.", inspectorConnections.get());
    }

    private Client connect() throws Exception {
        Client client = connect(INSPECTOR, "inspector");
        inspectorConnections.incrementAndGet();
        return client;
    }

    private Client connect(String username, String password) throws Exception {
        Session session = new JSch().getSession(username, container.getHost(), backendPort);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password");
        session.setTimeout(5000);
        try {
            session.connect(5000);
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            try {
                channel.connect(5000);
                return new Client(session, channel);
            } catch (Exception exception) {
                channel.disconnect();
                throw exception;
            }
        } catch (Exception exception) {
            session.disconnect();
            throw exception;
        }
    }

    private static String path(String relativeName) {
        Path selected = Path.of(DIRECTORY).resolve(relativeName).normalize();
        if (!selected.startsWith("/upload")) {
            throw new IllegalArgumentException("SFTP fixture paths must stay under /upload: " + relativeName);
        }
        return selected.toString();
    }

    private static void mkdirs(ChannelSftp channel, String path) throws SftpException {
        String current = "";
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                current += '/' + segment;
                if (!exists(channel, current)) {
                    channel.mkdir(current);
                }
            }
        }
    }

    private void execute(String... command) throws Exception {
        Container.ExecResult result = container.execInContainer(command);
        assertEquals(0, result.getExitCode(), () -> "SFTP server control failed: " + result.getStderr());
    }

    @Override
    public void close() throws Exception {
        if (!closed) {
            closed = true;
            try {
                if (proxy != null) {
                    proxy.close();
                }
            } finally {
                if (container != null) {
                    container.stop();
                }
            }
        }
    }

    private record Client(Session session, ChannelSftp channel) implements AutoCloseable {
        @Override
        public void close() {
            channel.disconnect();
            session.disconnect();
        }
    }
}

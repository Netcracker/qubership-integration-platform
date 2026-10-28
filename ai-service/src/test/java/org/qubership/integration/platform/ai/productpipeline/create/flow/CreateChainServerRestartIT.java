package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Kills a complete Quarkus test server while its PostgreSQL and artifact directory stay intact.
 * Excluded from the default build because it runs for about ten minutes; run it on demand.
 */
@Tag("server-restart")
class CreateChainServerRestartIT {

  @TempDir Path directory;

  @Test
  void disposableDatabaseFixtureRemovesItsAnonymousVolume() throws Exception {
    String container = "qip-recovery-cleanup-" + UUID.randomUUID();
    docker("run", "-d", "--name", container, "--entrypoint", "/bin/sleep", "postgres:18", "120");
    removeFixture(container);
  }

  @Test
  void serverRestartContinuesCommittedWorkWithoutAnotherAuthorCommand() throws Exception {
    restart(CreateChainServerRestartProbe.class.getName(), "flow");
  }

  @Test
  void serializedClarificationRestartsAfterTheDurableAnswer() throws Exception {
    restart(SerializedClarificationRestartProbe.class.getName(), "clarification", 180);
  }

  @ParameterizedTest
  @ValueSource(strings = {"AFTER_CLAIM", "AFTER_DISPATCHING", "TERMINAL_COMMIT", "AFTER_FIRST_PERSIST", "AFTER_COMPLETED"})
  void receiptRestartReplaysTheSameMessage(String window) throws Exception {
    restart(CreateChainReceiptRestartProbe.class.getName(), window);
  }

  @ParameterizedTest
  @CsvSource({"CLARIFY, AFTER_CLAIM", "CLARIFY, TERMINAL_COMMIT", "CLARIFY, AFTER_COMPLETED",
      "APPROVE, AFTER_CLAIM", "APPROVE, TERMINAL_COMMIT", "APPROVE, AFTER_COMPLETED",
      "TEXT_APPROVE, AFTER_CLAIM", "TEXT_APPROVE, TERMINAL_COMMIT", "TEXT_APPROVE, AFTER_COMPLETED"})
  void continuationRestartReplaysTheSameCommand(String command, String window) throws Exception {
    restart(CreateChainReceiptRestartProbe.class.getName(), command + ":" + window);
  }

  private void restart(String probeClass, String window) throws Exception {
    restart(probeClass, window, 60);
  }

  private void restart(String probeClass, String window, int timeoutSeconds) throws Exception {
    String container = "qip-recovery-" + UUID.randomUUID();
    docker("run", "-d", "--name", container, "-p", "127.0.0.1::5432",
        "-e", "POSTGRES_USER=recovery", "-e", "POSTGRES_PASSWORD=recovery",
        "-e", "POSTGRES_DB=recovery", "--health-cmd",
        "pg_isready -h 127.0.0.1 -U recovery -d recovery", "--health-interval", "1s", "postgres:18");
    try {
      long databaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      String health;
      do {
        health = docker("inspect", "--format", "{{.State.Health.Status}}", container).trim();
        if (!"healthy".equals(health)) { Thread.sleep(100); }
      } while (!"healthy".equals(health) && System.nanoTime() < databaseDeadline);
      assertEquals("healthy", health, "PostgreSQL fixture must be ready before the server starts");
      String port = docker("inspect", "--format",
          "{{(index (index .NetworkSettings.Ports \"5432/tcp\") 0).HostPort}}", container).trim();
      String jdbc = "jdbc:postgresql://127.0.0.1:" + port + "/recovery";
      Path firstLog = directory.resolve("prepare.log");
      Process first = child("prepare", jdbc, firstLog, probeClass, window);
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!Files.exists(directory.resolve("ready")) && first.isAlive()
            && System.nanoTime() < deadline) {
          Thread.sleep(50);
        }
        assertTrue(Files.exists(directory.resolve("ready")), () -> read(firstLog));
      } finally {
        first.destroyForcibly();
        assertTrue(first.waitFor(10, TimeUnit.SECONDS), "prepared server must terminate");
      }
      Path resumedLog = directory.resolve("resume.log");
      Process resumed = child("resume", jdbc, resumedLog, probeClass, window);
      try {
        assertTrue(resumed.waitFor(timeoutSeconds, TimeUnit.SECONDS), "resumed server must finish its checks");
        assertEquals(0, resumed.exitValue(), () -> read(resumedLog));
        assertEquals("COMPLETED", Files.readString(directory.resolve("result")));
      } finally {
        resumed.destroyForcibly();
        resumed.waitFor(10, TimeUnit.SECONDS);
      }
    } finally {
      try {
        Path evidence = Path.of("target", "server-restart", container);
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("postgres.log"), docker("logs", container));
        try (var files = Files.list(directory)) {
          for (Path file : files.filter(Files::isRegularFile).toList()) {
            Files.copy(file, evidence.resolve(file.getFileName()));
          }
        }
      } finally {
        removeFixture(container);
      }
    }
  }

  private static void removeFixture(String container) throws Exception {
    String volume = docker("inspect", "--format",
        "{{range .Mounts}}{{if eq .Type \"volume\"}}{{.Name}}{{end}}{{end}}", container).trim();
    docker("rm", "-f", "-v", container);
    assertTrue(docker("volume", "ls", "--quiet", "--filter", "name=" + volume).isBlank(),
        "PostgreSQL fixture must remove its anonymous volume: " + volume);
  }

  private Process child(String mode, String jdbc, Path log, String probeClass, String window) throws Exception {
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
        "-Dtenant.default.id=default",
        "-Drecovery.probe.mode=" + mode,
        "-Drecovery.probe.directory=" + directory,
        "-Drecovery.probe.jdbc=" + jdbc,
        "-Drecovery.probe.window=" + window,
        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        probeClass)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
  }

  private static String docker(String... arguments) throws Exception {
    var command = new java.util.ArrayList<>(List.of("docker"));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "local PostgreSQL fixture command must finish");
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      return output;
    } finally {
      process.destroyForcibly();
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception failure) {
      return failure.toString();
    }
  }
}

package org.qubership.integration.platform.engine.routes.fixture;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.engine.routes.fixture.KafkaSnapshotContainer.STARTER_SCRIPT;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaSnapshotContainerIT {
    private static final String IMAGE = "apache/kafka:3.8.0";
    private static final String ENTERED_MARKER = "/tmp/kafka-starter-entered";
    private static final String COMPLETED_MARKER = "/tmp/kafka-starter-completed";
    private static final String READY_MESSAGE = "SNAPSHOT_STARTER_COMPLETE";
    private static final String OBSERVER_READY = "SNAPSHOT_COPY_OBSERVER_READY";
    private static final String COPY_BLOCKED = "SNAPSHOT_STARTER_COPY_BLOCKED";
    private static final String PUBLISHED_EARLY = "SNAPSHOT_STARTER_PUBLISHED_EARLY";

    @ParameterizedTest
    @ValueSource(ints = {0755, 0777})
    void shouldPublishCompleteScriptWhenTransferFinishes(int fileMode) throws Exception {
        BlockedScript script = new BlockedScript(fileMode);
        CountDownLatch observerReady = new CountDownLatch(1);
        CompletableFuture<Boolean> copyBlocked = new CompletableFuture<>();
        KafkaSnapshotContainer container = new KafkaSnapshotContainer(IMAGE) {
            @Override
            protected void containerIsStarting(InspectContainerResponse containerInfo) {
                try {
                    startObserver(this, observerReady);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while starting the copy observer.", exception);
                } catch (IOException exception) {
                    throw new UncheckedIOException("Cannot start the copy observer.", exception);
                }
                copyFileToContainer(script, STARTER_SCRIPT);
            }
        };
        StringBuffer output = new StringBuffer();
        container.withLogConsumer(frame -> {
            output.append(frame.getUtf8String());
            if (output.indexOf(OBSERVER_READY) >= 0) {
                observerReady.countDown();
            }
            if (output.indexOf(COPY_BLOCKED) >= 0) {
                copyBlocked.complete(true);
            }
            if (output.indexOf(PUBLISHED_EARLY) >= 0) {
                copyBlocked.complete(false);
            }
        });
        container.waitingFor(Wait.forLogMessage(".*" + READY_MESSAGE + ".*", 1))
                .withStartupTimeout(Duration.ofSeconds(45));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch closeRequested = new CountDownLatch(1);
        CompletableFuture<Void> startup = new CompletableFuture<>();
        Future<?> lifecycle = executor.submit(() -> {
            try (container) {
                container.start();
                startup.complete(null);
                if (!closeRequested.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The test did not request container cleanup.");
                }
                return null;
            } catch (Exception | AssertionError failure) {
                startup.completeExceptionally(failure);
                throw failure;
            }
        });
        Throwable testFailure = null;
        try {
            assertTrue(script.partialWritten.await(30, TimeUnit.SECONDS), "The partial startup script was not transferred.");
            assertTrue(copyBlocked.get(15, TimeUnit.SECONDS),
                    "The startup script became visible or executed before its copy finished.");
            assertFalse(startup.isDone());

            script.release.countDown();
            startup.get(30, TimeUnit.SECONDS);

            Container.ExecResult completed = container.execInContainer("cat", COMPLETED_MARKER);
            assertEquals(0, completed.getExitCode(), completed::getStderr);
            assertEquals("complete\n", completed.getStdout());
            assertEquals(1, container.execInContainer("test", "-e", STARTER_SCRIPT + ".pending").getExitCode());
        } catch (Exception | AssertionError failure) {
            testFailure = failure;
            throw failure;
        } finally {
            script.release.countDown();
            closeRequested.countDown();
            executor.shutdown();
            try {
                try {
                    lifecycle.get(60, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "The container startup worker did not stop.");
                }
            } catch (Exception | AssertionError failure) {
                if (testFailure != null) {
                    testFailure.addSuppressed(failure);
                } else {
                    throw failure;
                }
            }
        }
    }

    private static void startObserver(KafkaSnapshotContainer container, CountDownLatch observerReady)
            throws IOException, InterruptedException {
        // Open the observer and log stream before the archive upload; Docker exec can block during extraction.
        String observer = "(echo " + OBSERVER_READY
                + "; until test -s " + STARTER_SCRIPT + ".pending; do sleep 0.05; done; "
                + "if test -e " + STARTER_SCRIPT + " || test -e " + ENTERED_MARKER
                + "; then echo " + PUBLISHED_EARLY + "; else echo " + COPY_BLOCKED + "; fi)"
                + " </dev/null >/proc/1/fd/1 2>/proc/1/fd/2 &";
        Container.ExecResult result = container.execInContainer("sh", "-c", observer);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Cannot start the copy observer: " + result.getStderr());
        }
        if (!observerReady.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("The copy observer did not start.");
        }
    }

    private static final class BlockedScript implements Transferable {
        private final CountDownLatch partialWritten = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final byte[] content;
        private final int fileMode;

        private BlockedScript(int fileMode) {
            this.fileMode = fileMode;
            // Cross Docker's streaming buffers before blocking in the middle of this comment.
            String script = "#!/bin/sh\nprintf 'entered\\n' > " + ENTERED_MARKER + "\n#"
                    + "x".repeat(128 * 1024)
                    + "\nprintf 'complete\\n' > " + COMPLETED_MARKER
                    + "\necho " + READY_MESSAGE + "\nexec sleep 300\n";
            content = script.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public void transferTo(TarArchiveOutputStream archive, String destination) {
            TarArchiveEntry entry = new TarArchiveEntry(destination);
            entry.setSize(content.length);
            entry.setMode(fileMode);
            try {
                archive.putArchiveEntry(entry);
                int split = content.length / 2;
                archive.write(content, 0, split);
                archive.flush();
                partialWritten.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The test did not release the startup script transfer.");
                }
                archive.write(content, split, content.length - split);
                archive.closeArchiveEntry();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while copying the test startup script.", exception);
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot copy the test startup script.", exception);
            }
        }
    }
}

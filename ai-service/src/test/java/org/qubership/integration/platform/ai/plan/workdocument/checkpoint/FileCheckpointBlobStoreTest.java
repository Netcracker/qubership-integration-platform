package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckpointBlobStoreTest {
  @TempDir Path directory;

  @Test
  void readWaitsForTheWholeContentAndVersionWrite() throws Exception {
    var store = FileCheckpointBlobStore.open(directory);
    store.put("run", "before".getBytes(StandardCharsets.UTF_8));
    var started = new CountDownLatch(1);
    CompletableFuture<org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob> read;
    synchronized (store.root()) {
      // Pause a writer between truncating its content and publishing the matching version.
      Files.writeString(directory.resolve("run"), "");
      read = CompletableFuture.supplyAsync(() -> {
        started.countDown();
        return store.getVersioned("run").orElseThrow();
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> read.get(100, TimeUnit.MILLISECONDS));
      Files.writeString(directory.resolve("run"), "after");
      Files.writeString(directory.resolve("run.version"), "2");
    }
    var committed = read.get(5, TimeUnit.SECONDS);
    assertEquals("after", new String(committed.content(), StandardCharsets.UTF_8));
    assertEquals("2", committed.version());
  }
}

package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;

class CreateRunBindingStoreTest {

  private static final String PREFIX = "product-pipeline-create-bindings/";

  private InMemoryArtifactBlobStore blobs;
  private CreateRunBindingStore store;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    blobs = new InMemoryArtifactBlobStore();
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    store = new CreateRunBindingStore(blobs, mapper);
  }

  @Test
  void resetRebindsTheSameConversationToANewRunAcrossReload() {
    CreateRunBinding original = store.create(productBinding("reset-chat"));
    store.reset("reset-chat");
    store = new CreateRunBindingStore(blobs, mapper);
    assertTrue(store.load("reset-chat").isEmpty());

    CreateRunBinding replacement = store.create(productBinding("reset-chat"));
    assertNotEquals(original.productRunId(), replacement.productRunId());
    assertEquals(replacement.productRunId(), replacement.runManifest().runId());
    assertEquals(replacement, new CreateRunBindingStore(blobs, mapper).load("reset-chat").orElseThrow());

    store.reset("reset-chat");
    assertNotEquals(replacement.productRunId(), store.create(productBinding("reset-chat")).productRunId());
  }

  @Test
  void createPersistsAndLoadReturnsSameBinding() {
    CreateRunBinding binding = productBinding("conv-store-1");
    CreateRunBinding created = store.create(binding);
    Optional<CreateRunBinding> loaded = store.load("conv-store-1");
    assertTrue(loaded.isPresent());
    assertEquals(created, loaded.get());
    assertEquals(binding.productRunId(), loaded.get().productRunId());
  }

  @Test
  void concurrentCreateReturnsSingleWinner() throws Exception {
    CreateRunBinding first = productBinding("conv-race");
    CreateRunBinding second =
        new CreateRunBinding(
            "conv-race",
            "conv-race-create-chain-2-other",
            first.runManifest(),
            Instant.parse("2026-07-22T12:00:01Z"));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<CreateRunBinding> a =
          pool.submit(
              () -> {
                start.await();
                return store.create(first);
              });
      Future<CreateRunBinding> b =
          pool.submit(
              () -> {
                start.await();
                return store.create(second);
              });
      start.countDown();
      CreateRunBinding winnerA = a.get(5, TimeUnit.SECONDS);
      CreateRunBinding winnerB = b.get(5, TimeUnit.SECONDS);
      assertEquals(winnerA, winnerB);
      assertEquals(winnerA, store.load("conv-race").orElseThrow());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void rejectsProductBindingWithoutRunIdOrManifest() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CreateRunBinding(
                "conv-bad", null, null, Instant.parse("2026-07-22T12:00:00Z")));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void rejectsHistoricalCreateChainV1BindingWithoutRewrite(boolean retiredProfileRegistered) throws Exception {
    if (retiredProfileRegistered) {
      var profiles = List.of("1", "2").stream()
          .map(version -> new org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile(
              1, "create-chain", version, List.of(), List.of(),
              new org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy("finish", "PLAN_APPROVED"),
              List.of()))
          .toList();
      store = new CreateRunBindingStore(blobs, mapper,
          new org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog(profiles));
    }
    String conversationId = "conv-historical-product";
    String key = key(conversationId);
    byte[] historical = historicalProductJson(conversationId).getBytes(StandardCharsets.UTF_8);
    blobs.put(key, historical);
    VersionedBlob before = blobs.getVersioned(key).orElseThrow();

    UnsupportedCreateRunBindingException failure = assertThrows(
        UnsupportedCreateRunBindingException.class, () -> store.load(conversationId));
    assertEquals(UnsupportedCreateRunBindingException.ERROR_ID, failure.errorId());
    assertThrows(UnsupportedCreateRunBindingException.class,
        () -> store.plannedRunId(conversationId, conversationId + "-create-chain-2"));
    assertThrows(UnsupportedCreateRunBindingException.class,
        () -> store.create(productBinding(conversationId)));
    VersionedBlob after = blobs.getVersioned(key).orElseThrow();
    assertEquals(before.version(), after.version());
    assertArrayEquals(before.content(), after.content());
    CreateRunBinding current = store.create(productBinding("conv-current-profile"));
    assertEquals(current, store.load("conv-current-profile").orElseThrow());
  }

  @Test
  void writesProductOnlyBindingWithoutModeField() throws Exception {
    CreateRunBinding binding = productBinding("conv-write-no-mode");
    store.create(binding);

    byte[] raw = blobs.get(key("conv-write-no-mode")).orElseThrow();
    JsonNode node = mapper.readTree(raw);
    assertFalse(node.has("mode"), () -> "stored JSON must omit mode: " + new String(raw));
    assertEquals("conv-write-no-mode", node.get("conversationId").asText());
    assertEquals(binding.productRunId(), node.get("productRunId").asText());
    assertEquals("create-chain", node.get("runManifest").get("profileId").asText());
  }

  @Test
  void rejectsHistoricalLegacyBindingWithoutRewrite() {
    String conversationId = "conv-historical-legacy";
    String key = key(conversationId);
    byte[] historical =
        """
        {
          "conversationId":"%s",
          "mode":"LEGACY",
          "productRunId":null,
          "runManifest":null,
          "createdAt":"2026-07-22T12:00:00Z"
        }
        """
            .formatted(conversationId)
            .getBytes(StandardCharsets.UTF_8);
    blobs.put(key, historical);
    VersionedBlob before = blobs.getVersioned(key).orElseThrow();

    UnsupportedCreateRunBindingException thrown =
        assertThrows(
            UnsupportedCreateRunBindingException.class, () -> store.load(conversationId));

    assertEquals(UnsupportedCreateRunBindingException.ERROR_ID, thrown.errorId());
    assertEquals(
        UnsupportedCreateRunBindingException.ERROR_ID
            + ": "
            + UnsupportedCreateRunBindingException.DISPLAY_MESSAGE,
        thrown.sseMessage());
    VersionedBlob after = blobs.getVersioned(key).orElseThrow();
    assertEquals(before.version(), after.version());
    assertArrayEquals(before.content(), after.content());
  }

  @Test
  void rejectsHistoricalCreatePlanBindingWithoutRewrite() throws Exception {
    String conversationId = "conv-historical-create-plan";
    String key = key(conversationId);
    byte[] historical =
        historicalProductJson(conversationId)
            .replace("create-chain", "create-plan")
            .getBytes(StandardCharsets.UTF_8);
    blobs.put(key, historical);
    VersionedBlob before = blobs.getVersioned(key).orElseThrow();

    assertThrows(UnsupportedCreateRunBindingException.class, () -> store.load(conversationId));

    VersionedBlob after = blobs.getVersioned(key).orElseThrow();
    assertEquals(before.version(), after.version());
    assertArrayEquals(before.content(), after.content());
  }

  @Test
  void rejectsUnknownProductPinWithoutRewrite() throws Exception {
    String conversationId = "conv-unknown-pin";
    String key = key(conversationId);
    byte[] historical =
        historicalProductJson(conversationId)
            .replace("\"profileId\":\"create-chain\"", "\"profileId\":\"other-profile\"")
            .replace("\"profileVersion\":\"1\"", "\"profileVersion\":\"9\"")
            .getBytes(StandardCharsets.UTF_8);
    blobs.put(key, historical);
    VersionedBlob before = blobs.getVersioned(key).orElseThrow();

    assertThrows(UnsupportedCreateRunBindingException.class, () -> store.load(conversationId));

    VersionedBlob after = blobs.getVersioned(key).orElseThrow();
    assertEquals(before.version(), after.version());
    assertArrayEquals(before.content(), after.content());
  }

  @Test
  void createRacePropagatesUnsupportedWinningBinding() {
    String conversationId = "conv-race-unsupported";
    String key = key(conversationId);
    byte[] unsupported =
        """
        {
          "conversationId":"%s",
          "mode":"LEGACY",
          "productRunId":null,
          "runManifest":null,
          "createdAt":"2026-07-22T12:00:00Z"
        }
        """
            .formatted(conversationId)
            .getBytes(StandardCharsets.UTF_8);
    blobs.put(key, unsupported);
    VersionedBlob before = blobs.getVersioned(key).orElseThrow();

    assertThrows(
        UnsupportedCreateRunBindingException.class,
        () -> store.create(productBinding(conversationId)));

    VersionedBlob after = blobs.getVersioned(key).orElseThrow();
    assertEquals(before.version(), after.version());
    assertArrayEquals(before.content(), after.content());
  }

  private static String key(String conversationId) {
    return PREFIX + conversationId + ".json";
  }

  private static String historicalProductJson(String conversationId) throws java.io.IOException {
    String runId = conversationId + "-create-chain-1";
    try (var in = CreateRunBindingStoreTest.class.getResourceAsStream(
        "/product-pipelines/historical-create-chain-v1-binding.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).formatted(conversationId, runId, runId);
    }
  }

  private static CreateRunBinding productBinding(String conversationId) {
    FakeKnowledgeClient knowledge = FakeKnowledgeClient.defaultFixture();
    RunManifest manifest =
        new RunManifest(
            conversationId + "-create-chain-2",
            null,
            List.of(),
            "product",
            "create-chain",
            "2",
            "create-chain@2",
            "reference-baseline-v1",
            "reference-baseline-v1",
            List.of(),
            "closure",
            knowledge.context().packageRef(),
            "2026.1",
            List.of(),
            null);
    return new CreateRunBinding(
        conversationId,
        conversationId + "-create-chain-2",
        manifest,
        Instant.parse("2026-07-22T12:00:00Z"));
  }
}

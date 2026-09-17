package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogQuery;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;

/**
 * Maven spy gate for the five binding-policy create-chain scenarios exercised in product-pipeline
 * E2E. Direct native triggers and senders must not search the catalog; implemented-service HTTP and
 * AsyncAPI consume must keep a catalog binding identity during capture.
 */
class CreateChainBindingPolicyCatalogSpyTest {

  private static final String CONVERSATION_ID = "binding-policy-spy";

  private final RequirementDraftStore store = new RequirementDraftStore();
  private final ConversationApiResolutions resolutions = new ConversationApiResolutions();
  private final CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
  private final ConversationApiHubCache apiHubCache = mock(ConversationApiHubCache.class);
  private final RequirementDraftTool captureTool =
      new RequirementDraftTool(store, null, null, apiHubCache, resolutions, null, lookup);

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void greetingsCaptureDoesNotSearchCatalog() {
    stubNoCatalogHits();
    beginTurn();

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Create chain with GET /greetings returning Hello from a script. No service calls.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "greetings-http",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Expose GET /greetings",
                        "",
                        "",
                        "",
                        "GET",
                        "/greetings"),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.BEHAVIOR,
                        "",
                        "Return Hello as plain text from a script")),
                null,
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "greetings-http",
                            Direction.INBOUND,
                            "Caller",
                            "GET /greetings",
                            "")),
                    List.of())));

    RequirementDraft draft = store.get(CONVERSATION_ID).orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains(RequirementDraftTool.BINDING_SOFT_DOWNGRADE_PREFIX), result);
    assertTrue(draft.readyForPlan(), draft.toString());
    assertTrue(draft.catalogBindings().isEmpty());
    verifyNoInteractions(lookup);
    assertApiHubCacheBackfillOnly();
  }

  @Test
  void kafkaTriggerCaptureDoesNotSearchCatalog() {
    stubNoCatalogHits();
    beginTurn();

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Consume kafka-trigger-2 on topic classifier cip-auto-tests-topic1, then script.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "kafka-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "kafka-trigger-2",
                        "Consume user events on cip-auto-tests-topic1",
                        "",
                        "",
                        "cip-auto-tests-topic1",
                        "",
                        ""),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.BEHAVIOR,
                        "",
                        "Return plain text consumed")),
                null,
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "kafka-entry",
                            Direction.INBOUND,
                            "Kafka",
                            "consume",
                            "")),
                    List.of())));

    RequirementDraft draft = store.get(CONVERSATION_ID).orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains(RequirementDraftTool.BINDING_SOFT_DOWNGRADE_PREFIX), result);
    assertTrue(draft.readyForPlan(), draft.toString());
    assertTrue(draft.catalogBindings().isEmpty());
    verifyNoInteractions(lookup);
    assertApiHubCacheBackfillOnly();
  }

  @Test
  void httpSenderCaptureDoesNotSearchCatalog() {
    stubNoCatalogHits();
    beginTurn();

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "POST /relay through http-trigger then http-sender to https://example.invalid/relay.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "relay-http",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Expose POST /relay",
                        "",
                        "",
                        "",
                        "POST",
                        "/relay"),
                    new RequirementFact(
                        "relay-send",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-sender",
                        "Forward POST to https://example.invalid/relay",
                        "",
                        "",
                        "",
                        "POST",
                        "https://example.invalid/relay",
                        "")),
                null,
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "relay-http", Direction.INBOUND, "Caller", "POST /relay", ""),
                        new Interaction(
                            "relay-send",
                            Direction.OUTBOUND,
                            "Relay",
                            "POST https://example.invalid/relay",
                            "")),
                    List.of(
                        new RequirementFlow.Transition("relay-http", "relay-send")))));

    RequirementDraft draft = store.get(CONVERSATION_ID).orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains(RequirementDraftTool.BINDING_SOFT_DOWNGRADE_PREFIX), result);
    assertTrue(draft.readyForPlan(), draft.toString());
    assertTrue(draft.catalogBindings().isEmpty());
    verifyNoInteractions(lookup);
    assertApiHubCacheBackfillOnly();
  }

  @Test
  void implementedServiceHttpCaptureResolvesCatalogBinding() {
    when(lookup.resolve(any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              CatalogQuery query = invocation.getArgument(0);
              if ("findPetsByStatus".equals(query.operationHint())) {
                return new CatalogLookupResult.Exact(petstoreFindByStatusMatch());
              }
              return new CatalogLookupResult.None();
            });
    beginTurn();

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Expose Petstore Ext GET /pet/findByStatus as implemented-service HTTP trigger.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "petstore-http",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Implement Petstore Ext findPetsByStatus",
                        "Petstore Ext",
                        "findPetsByStatus",
                        "",
                        "GET",
                        ""),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.BEHAVIOR,
                        "",
                        "Return JSON unchanged")),
                null,
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "petstore-http",
                            Direction.INBOUND,
                            "Petstore Ext",
                            "findPetsByStatus",
                            "")),
                    List.of())));

    RequirementDraft draft = store.get(CONVERSATION_ID).orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains(RequirementDraftTool.BINDING_SOFT_DOWNGRADE_PREFIX), result);
    assertFalse(draft.catalogBindings().isEmpty(), draft.catalogBindings().toString());
    assertEqualsBindingInteraction("petstore-http", draft);
    verify(lookup, atLeastOnce()).resolve(any());
    assertApiHubCacheBackfillOnly();
  }

  @Test
  void asyncApiTriggerCaptureResolvesCatalogBinding() {
    when(lookup.resolve(any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              CatalogQuery query = invocation.getArgument(0);
              if ("onTaskStart".equals(query.operationHint())) {
                return new CatalogLookupResult.Exact(asyncApiConsumeMatch());
              }
              return new CatalogLookupResult.None();
            });
    beginTurn();

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Consume Auto tests kafka service onTaskStart through async-api-trigger.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "async-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "async-api-trigger",
                        "Consume onTaskStart",
                        "Auto tests kafka service",
                        "onTaskStart",
                        "cip-auto-tests-topic2",
                        "",
                        "",
                        ""),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.BEHAVIOR,
                        "",
                        "Return plain text ack")),
                null,
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "async-entry",
                            Direction.INBOUND,
                            "Auto tests kafka service",
                            "onTaskStart",
                            "")),
                    List.of())));

    RequirementDraft draft = store.get(CONVERSATION_ID).orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains(RequirementDraftTool.BINDING_SOFT_DOWNGRADE_PREFIX), result);
    assertFalse(draft.catalogBindings().isEmpty(), draft.catalogBindings().toString());
    assertEqualsBindingInteraction("async-entry", draft);
    verify(lookup, atLeastOnce()).resolve(any());
    assertApiHubCacheBackfillOnly();
  }

  private void beginTurn() {
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
    store.beginTurn(CONVERSATION_ID);
  }

  private void stubNoCatalogHits() {
    when(lookup.resolve(any(CatalogQuery.class))).thenReturn(new CatalogLookupResult.None());
  }

  /**
   * Capture reads {@link ConversationApiHubCache#latestCandidate} for backfill only; it must not
   * search API Hub or write cache entries during binding-policy scenarios.
   */
  private void assertApiHubCacheBackfillOnly() {
    verify(apiHubCache).latestCandidate(CONVERSATION_ID);
    verifyNoMoreInteractions(apiHubCache);
  }

  private static void assertEqualsBindingInteraction(String interactionId, RequirementDraft draft) {
    assertTrue(
        draft.catalogBindings().stream()
            .anyMatch(hint -> interactionId.equals(hint.interactionId())),
        draft.catalogBindings().toString());
  }

  private static CatalogMatch petstoreFindByStatusMatch() {
    return new CatalogMatch(
        "sys-petstore",
        "sg-petstore",
        "spec-petstore",
        "op-findByStatus",
        "Petstore Ext",
        "http",
        "GET",
        "/pet/findByStatus",
        "findPetsByStatus",
        "catalog-read:petstore-findByStatus");
  }

  private static CatalogMatch asyncApiConsumeMatch() {
    return new CatalogMatch(
        "sys-auto-kafka",
        "sg-auto-kafka",
        "spec-auto-kafka",
        "op-onTaskStart",
        "Auto tests kafka service",
        "kafka",
        "publish",
        "cip-auto-tests-topic2",
        "onTaskStart",
        "catalog-read:auto-kafka-onTaskStart");
  }
}

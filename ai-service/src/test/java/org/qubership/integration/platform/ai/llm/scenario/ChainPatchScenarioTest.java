package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditClarificationStore;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompiler;
import org.qubership.integration.platform.ai.chain.edit.ChainEditEscalationStore;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditOutcome;
import org.qubership.integration.platform.ai.chain.edit.ChainEditRequest;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.patch.ChainEditProposalAssembler;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchOwnership;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchSemanticValidator;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchStore;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContext;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

/** The interactive driver: compile, offer one card, and write only what the reader answered. */
class ChainPatchScenarioTest {

  private static final String CONVERSATION_ID = "conv-patch";
  private static final String CHAIN_ID = "chain-1";

  private ChainContextExtractor chainContextExtractor;
  private ChainCatalogFactsService factsService;
  private ChainEditCompiler editCompiler;
  private ChainPatchOwnership ownership;
  private ChainPatchWriter writer;
  private ChainPatchStore patchStore;
  private ChainPatchScenario scenario;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    chainContextExtractor = mock(ChainContextExtractor.class);
    factsService = mock(ChainCatalogFactsService.class);
    editCompiler = mock(ChainEditCompiler.class);
    ownership = mock(ChainPatchOwnership.class);
    writer = mock(ChainPatchWriter.class);
    patchStore = new ChainPatchStore();

    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(factsService.load(CHAIN_ID)).thenReturn(facts());
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                false, false, Set.of(), Set.of(), Map.of("script", Set.of("script"))));
    when(writer.write(any(), any()))
        .thenReturn(new ChainPatchWriteResult(List.of("element-script"), List.of(), null, null));
    ChainPatchSemanticValidator semanticValidator = mock(ChainPatchSemanticValidator.class);
    when(semanticValidator.introducedProblems(any(), any(), any())).thenReturn(List.of());
    when(editCompiler.compile(any(), any()))
        .thenReturn(new ChainEditOutcome.Unsupported(ChainEditAction.UNRESOLVED));

    scenario =
        new ChainPatchScenario(
            chainContextExtractor,
            factsService,
            new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper)),
            editCompiler,
            new ChainEditEscalationStore(),
            new ChainEditClarificationStore(),
            new ChainEditProposalAssembler(
                ownership,
                new ValidatedGraphPatchApplier(
                    new GraphPatchOwnershipValidator(), new GraphPatchApplier()),
                semanticValidator),
            patchStore,
            writer,
            new CanonicalGraphDigest(objectMapper),
            new KnownFailureMapper(),
            new PinnedFailureStore(),
            mock(ChainQuestionScenario.class));
  }

  @Test
  void readsTheCatalogOffTheSseSubscriptionThread() {
    String subscriberThread = Thread.currentThread().getName();
    AtomicReference<String> loadThread = new AtomicReference<>();
    when(factsService.load(CHAIN_ID))
        .thenAnswer(
            invocation -> {
              loadThread.set(Thread.currentThread().getName());
              return facts();
            });

    // SSE bindBackoffSinkForTurn subscribes the scenario on the caller thread (the Vert.x event
    // loop in production). Catalog RestClient must not run there.
    AssertSubscriber<ChatEvent> sub =
        Multi.createFrom()
            .<ChatEvent>emitter(
                emitter ->
                    scenario
                        .handle(
                            request("change the HTTP trigger endpoint"),
                            CONVERSATION_ID,
                            ScenarioType.COMPARE_AND_PATCH)
                        .subscribe()
                        .with(emitter::emit, emitter::fail, emitter::complete))
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    sub.awaitCompletion();

    assertNotNull(loadThread.get(), "catalog load did not run");
    assertNotEquals(
        subscriberThread,
        loadThread.get(),
        "catalog load must leave the SSE subscriber thread, was: " + loadThread.get());
    assertFalse(
        loadThread.get().contains("eventloop"),
        "catalog load must not run on a Vert.x event loop: " + loadThread.get());
  }

  @Test
  void fixItPassesThePinnedCatalogFailureIntoTheEditRequest() {
    String pin =
        "Couldn't take a catalog snapshot: Missing required property httpUri. Element trigger-http (id).";
    ChatRequest chat = request("fix it");
    chat.setOpenChainTurnContext(
        new OpenChainTurnContext(
            CONVERSATION_ID,
            CHAIN_ID,
            "fix it",
            "user: show graph\nassistant: " + pin,
            Optional.of(new PinnedFailure(CONVERSATION_ID, CHAIN_ID, pin, "httpUri")),
            Optional.empty(),
            false));

    run(chat);

    ArgumentCaptor<ChainEditRequest> captured = ArgumentCaptor.forClass(ChainEditRequest.class);
    verify(editCompiler).compile(captured.capture(), any());
    assertEquals("fix it", captured.getValue().userRequest());
    assertEquals(pin, captured.getValue().pinnedFailureSafeText());
    assertEquals("user: show graph\nassistant: " + pin, captured.getValue().transcriptWindow());
  }

  @Test
  void compileCatalogTimeoutEmitsSanitizedTokenNotCompilerMessage() {
    when(editCompiler.compile(any(), any()))
        .thenThrow(
            new TimeoutException("CatalogRestClient$$CDIWrapper#getChain timed out"));

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(request("fix it"), CONVERSATION_ID, ScenarioType.COMPARE_AND_PATCH)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    sub.awaitCompletion();

    assertEquals(1, sub.getItems().size(), () -> "expected one event, got " + sub.getItems());
    ChatEvent event = sub.getItems().get(0);
    assertInstanceOf(ChatEvent.Token.class, event, () -> "expected Token, got " + event);
    String text = ((ChatEvent.Token) event).text();
    assertEquals(KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, text);
    assertFalse(text.contains("timed out"));
    assertFalse(text.contains("CDIWrapper"));
  }

  @Test
  void compileNpeDoesNotBecomeToken() {
    when(editCompiler.compile(any(), any())).thenThrow(new NullPointerException("x"));

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(request("fix it"), CONVERSATION_ID, ScenarioType.COMPARE_AND_PATCH)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    sub.awaitFailure();

    assertInstanceOf(NullPointerException.class, sub.getFailure());
    assertFalse(sub.getItems().stream().anyMatch(ChatEvent.Token.class::isInstance));
  }

  @Test
  void saysSoWhenNoChainIsOpen() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());

    List<ChatEvent> events = run(request("fix the script"));

    assertTrue(text(events).contains("No chain context"), text(events));
  }

  @Test
  void streamsSkillStepsWhileTheCompilerRuns() {
    compiles(propertyPatch("element-script", "script", "return 201"));

    List<ChatEvent> events = run(request("fix the script in Normalize payload"));

    List<ChatEvent.Step> skills =
        events.stream()
            .filter(ChatEvent.Step.class::isInstance)
            .map(ChatEvent.Step.class::cast)
            .filter(step -> "skill".equals(step.kind()))
            .toList();
    assertEquals(
        List.of(
            new ChatEvent.Step(
                "skill:cip-script-generator",
                "skill",
                "running",
                "Writing scripts",
                null),
            new ChatEvent.Step(
                "skill:cip-script-generator",
                "skill",
                "completed",
                "Writing scripts",
                null)),
        skills);
  }

  @Test
  void offersTheCompilersNetPatchAsADecisionCard() {
    compiles(propertyPatch("element-script", "script", "return 201"));

    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    assertEquals(ChatEvent.CHAIN_PATCH_ARTIFACT, card.artifactType());
    assertEquals(
        List.of(ChatEvent.APPLY_CHAIN_PATCH_ACTION, ChatEvent.REQUEST_CHANGES_ACTION),
        card.actions());
    assertTrue(card.question().contains("Normalize payload"), card.question());
  }

  @Test
  void showsTheOldAndTheNewValueOnTheCard() {
    compiles(propertyPatch("element-script", "script", "return 201"));

    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    assertTrue(card.question().contains("return 200"), card.question());
    assertTrue(card.question().contains("return 201"), card.question());
  }

  @Test
  void writesNothingUntilTheCardIsAnswered() {
    compiles(propertyPatch("element-script", "script", "return 201"));

    run(request("fix the script in Normalize payload"));

    verify(writer, never()).write(any(), any());
  }

  @Test
  void writesTheCompilersNetPatchWhenTheCardIsAnswered() {
    compiles(propertyPatch("element-script", "script", "return 201"));
    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    List<ChatEvent> events = run(answer(card.artifactHash()));

    ArgumentCaptor<GraphPatch> written = ArgumentCaptor.forClass(GraphPatch.class);
    verify(writer).write(any(), written.capture());
    assertEquals(
        List.of("return 201"),
        written.getValue().propertyPatches().stream().map(p -> p.property().value()).toList());
    assertTrue(text(events).contains("Normalize payload"), text(events));
  }

  @Test
  void refusesToWriteOverAChainThatChangedWhileTheCardWasOpen() {
    compiles(propertyPatch("element-script", "script", "return 201"));
    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));
    when(factsService.load(CHAIN_ID))
        .thenReturn(
            factsWith(
                new ChainCatalogElement(
                    "element-script",
                    "script",
                    "Normalize payload",
                    null,
                    Map.of("script", "return 500 // edited by hand"))));

    List<ChatEvent> events = run(answer(card.artifactHash()));

    verify(writer, never()).write(any(), any());
    assertTrue(text(events).toLowerCase().contains("changed"), text(events));
  }

  @Test
  void refusesAnAnswerThatNamesAPatchTheConversationHasMovedPast() {
    compiles(propertyPatch("element-script", "script", "return 201"));
    run(request("fix the script in Normalize payload"));

    List<ChatEvent> events = run(answer("some-other-patch"));

    verify(writer, never()).write(any(), any());
    assertTrue(text(events).toLowerCase().contains("no longer"), text(events));
  }

  @Test
  void refusesAPatchThatReachesOutsideWhatTheSkillOwns() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(GraphPatchOwnershipPolicy.denyAll());
    compiles(propertyPatch("element-script", "script", "return 201"));

    List<ChatEvent> events = run(request("fix the script in Normalize payload"));

    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance), "no card should be shown");
    assertTrue(text(events).contains("outside what I may edit"), text(events));
  }

  @Test
  void asksTheCompilersQuestionAndProposesNothing() {
    when(editCompiler.compile(any(), any())).thenReturn(clarification());

    List<ChatEvent> events = run(request("change the operation on the order call"));

    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance), "no card should be shown");
    assertTrue(text(events).contains("Get order status"), text(events));
  }

  @Test
  void answeringTheClarificationContinuesTheSameEditWithoutRestatingIt() {
    ChainEditOutcome.Clarification clarification = clarification();
    when(editCompiler.compile(any(), any())).thenReturn(clarification);
    run(request("change the operation on the order call"));

    when(editCompiler.resumeAfterClarification(
            any(), eq(clarification.heldIntent()), eq(clarification.question()), any()))
        .thenReturn(new ChainEditOutcome.ResolutionFailure("resumed the clarified edit"));

    List<ChatEvent> events = run(request("the second one"));

    verify(editCompiler)
        .resumeAfterClarification(
            any(), eq(clarification.heldIntent()), eq(clarification.question()), any());
    verify(editCompiler, times(1)).compile(any(), any());
    assertTrue(text(events).contains("resumed the clarified edit"), text(events));
  }

  @Test
  void aClarificationLeftUnansweredWhileTheReaderAsksSomethingElseStartsThatRequestClean() {
    ChainEditOutcome.Clarification clarification = clarification();
    when(editCompiler.compile(any(), any())).thenReturn(clarification);
    run(request("change the operation on the order call"));

    when(editCompiler.resumeAfterClarification(any(), any(), any(), any()))
        .thenReturn(new ChainEditOutcome.Unsupported(ChainEditAction.UNRESOLVED));
    run(request("actually, never mind, shuffle the branches"));

    // The held clarification does not survive: the next plain turn compiles fresh rather than
    // resuming, because the previous turn already consumed and discarded it.
    when(editCompiler.compile(any(), any()))
        .thenReturn(new ChainEditOutcome.ResolutionFailure("compiled fresh"));
    List<ChatEvent> events = run(request("fix the script in Normalize payload"));

    verify(editCompiler, times(1))
        .resumeAfterClarification(any(), any(), any(), any());
    assertTrue(text(events).contains("compiled fresh"), text(events));
  }

  @Test
  void nothingIsWrittenWhileAClarificationIsOutstanding() {
    when(editCompiler.compile(any(), any())).thenReturn(clarification());

    run(request("change the operation on the order call"));

    verify(writer, never()).write(any(), any());
  }

  private static ChainEditOutcome.Clarification clarification() {
    return new ChainEditOutcome.Clarification(
        "Several operations match. Which one do you mean?",
        List.of("Get order status (GET /orders/{id}/status) in Orders"),
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "change the operation",
            "order status",
            List.of("Get order status (GET /orders/{id}/status) in Orders")));
  }

  @Test
  void reportsAnEditNoCompilerSkillOwns() {
    when(editCompiler.compile(any(), any()))
        .thenReturn(new ChainEditOutcome.Unsupported(ChainEditAction.REORDER));

    List<ChatEvent> events = run(request("shuffle the branches"));

    verify(writer, never()).write(any(), any());
    assertTrue(text(events).contains("REORDER"), text(events));
  }

  @Test
  void offersAnImportAsItsOwnDecisionAndImportsNothingUntilItIsAnswered() {
    when(editCompiler.compile(any(), any())).thenReturn(escalation());

    ChatEvent.Decision card = decision(run(request("point the order call at the status operation")));

    assertEquals(List.of(ChatEvent.IMPORT_ACTION), card.actions());
    verify(editCompiler, never()).resumeAfterImport(any(), any(), any(), any());
    verify(writer, never()).write(any(), any());
  }

  @Test
  void approvingTheImportResumesTheSameEdit() {
    ChainEditOutcome.Escalation escalation = escalation();
    when(editCompiler.compile(any(), any())).thenReturn(escalation);
    run(request("point the order call at the status operation"));
    when(editCompiler.resumeAfterImport(
            any(), eq(escalation.intent()), eq(escalation.refs()), any()))
        .thenReturn(new ChainEditOutcome.ResolutionFailure("resumed"));

    List<ChatEvent> events = run(approveImport());

    verify(editCompiler)
        .resumeAfterImport(any(), eq(escalation.intent()), eq(escalation.refs()), any());
    assertTrue(text(events).contains("resumed"), text(events));
  }

  @Test
  void anImportLeftUnansweredWhileTheReaderSaysSomethingElseImportsNothing() {
    when(editCompiler.compile(any(), any())).thenReturn(escalation());
    run(request("point the order call at the status operation"));

    when(editCompiler.compile(any(), any()))
        .thenReturn(new ChainEditOutcome.Unsupported(ChainEditAction.UNRESOLVED));
    run(request("actually, never mind"));
    List<ChatEvent> events = run(approveImport());

    verify(editCompiler, never()).resumeAfterImport(any(), any(), any(), any());
    assertTrue(text(events).toLowerCase().contains("no change waiting"), text(events));
  }

  private static ChainEditOutcome.Escalation escalation() {
    return new ChainEditOutcome.Escalation(
        "'order status' is not in the local catalog.",
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of("element-script"),
            "rebind",
            "order status",
            List.of()),
        new ApiHubRequirementRefs(
            "pkg-1", "2026.1", "op-1", "doc-1", "rest", "Orders", "Orders API"));
  }

  private void compiles(PropertyPatch propertyPatch) {
    when(editCompiler.compile(any(), any()))
        .thenAnswer(
            invocation -> {
              ChainEditRequest editRequest = invocation.getArgument(0);
              @SuppressWarnings("unchecked")
              BiConsumer<String, String> progress = invocation.getArgument(1);
              progress.accept("cip-script-generator", "running");
              progress.accept("cip-script-generator", "completed");
              ChainPlanGraph base = editRequest.imported().graph();
              return new ChainEditOutcome.Proposal(
                  new GraphPatch(
                      "net-1",
                      "cip-script-generator",
                      List.of(),
                      List.of(),
                      List.of(propertyPatch),
                      List.of(),
                      List.of(),
                      "rewrites the script"),
                  base,
                  base,
                  new ChainEditIntent(
                      ChainEditAction.CONFIGURE,
                      List.of(propertyPatch.targetNodeId()),
                      "rewrite the script",
                      null,
                      null,
                      null,
                      List.of("script"),
                      List.of()),
                  List.of(),
                  List.of("cip-script-generator"),
                  null);
            });
  }

  private static PropertyPatch propertyPatch(String nodeId, String key, String value) {
    return new PropertyPatch(GraphPatchOperation.UPDATE, nodeId, new PlanProperty(key, value));
  }

  private List<ChatEvent> run(ChatRequest request) {
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(request, CONVERSATION_ID, ScenarioType.COMPARE_AND_PATCH)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    sub.awaitCompletion();
    return sub.getItems();
  }

  private static ChatRequest request(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
  }

  private static ChatRequest approveImport() {
    ChatRequest request = request("Import it");
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.IMPORT_ACTION);
    request.setDecision(command);
    return request;
  }

  private static ChatRequest answer(String patchHash) {
    ChatRequest request = request("Apply the proposed change to the chain");
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.APPLY_CHAIN_PATCH_ACTION);
    command.setArtifactType(ChatEvent.CHAIN_PATCH_ARTIFACT);
    command.setArtifactHash(patchHash);
    request.setDecision(command);
    return request;
  }

  private static ChatEvent.Decision decision(List<ChatEvent> events) {
    return events.stream()
        .filter(ChatEvent.Decision.class::isInstance)
        .map(ChatEvent.Decision.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no decision card was offered: " + text(events)));
  }

  private static String text(List<ChatEvent> events) {
    return events.stream()
        .filter(ChatEvent.Token.class::isInstance)
        .map(event -> ((ChatEvent.Token) event).text())
        .reduce("", String::concat);
  }

  private static ChainCatalogFacts facts() {
    return factsWith(
        new ChainCatalogElement(
            "element-script", "script", "Normalize payload", null, Map.of("script", "return 200")));
  }

  private static ChainCatalogFacts factsWith(ChainCatalogElement script) {
    return new ChainCatalogFacts(
        CHAIN_ID,
        "Order sync",
        "Syncs orders",
        2,
        0,
        "",
        List.of(
            new ChainCatalogElement(
                "element-trigger", "http-trigger", "Receive order", null, Map.of()),
            script),
        List.of(),
        "built_in_catalog");
  }
}

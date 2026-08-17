package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditRequest;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompiler;
import org.qubership.integration.platform.ai.chain.edit.ChainEditOutcome;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchCapture;
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
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainPatchAgent;
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
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;

class ChainPatchScenarioTest {

  private static final String CONVERSATION_ID = "conv-patch";
  private static final String CHAIN_ID = "chain-1";

  private ChainContextExtractor chainContextExtractor;
  private ChainCatalogFactsService factsService;
  private ChainPatchAgent agent;
  private ChainPatchOwnership ownership;
  private ChainPatchSemanticValidator semanticValidator;
  private ChainPatchWriter writer;
  private ChainPatchStore patchStore;
  private ChainEditCompiler editCompiler;
  private ChainPatchScenario scenario;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    chainContextExtractor = mock(ChainContextExtractor.class);
    factsService = mock(ChainCatalogFactsService.class);
    agent = mock(ChainPatchAgent.class);
    ownership = mock(ChainPatchOwnership.class);
    writer = mock(ChainPatchWriter.class);
    patchStore = new ChainPatchStore();

    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(factsService.load(CHAIN_ID)).thenReturn(facts());
    when(agent.chat(eq(CONVERSATION_ID), any())).thenReturn(Multi.createFrom().empty());
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                false, false, Set.of(), Set.of(), Map.of("script", Set.of("script"))));
    when(writer.write(any(), any()))
        .thenReturn(new ChainPatchWriteResult(List.of("element-script"), List.of(), null, null));
    semanticValidator = mock(ChainPatchSemanticValidator.class);
    when(semanticValidator.introducedProblems(any(), any(), any())).thenReturn(List.of());
    editCompiler = mock(ChainEditCompiler.class);
    when(editCompiler.compile(any()))
        .thenReturn(new ChainEditOutcome.Unsupported(ChainEditAction.EDIT_CONFIGURATION));

    scenario =
        new ChainPatchScenario(
            chainContextExtractor,
            factsService,
            new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper)),
            editCompiler,
            agent,
            patchStore,
            ownership,
            new ValidatedGraphPatchApplier(
                new GraphPatchOwnershipValidator(), new GraphPatchApplier()),
            semanticValidator,
            writer,
            new CanonicalGraphDigest(objectMapper),
            new ChainElementCatalog(objectMapper),
            objectMapper);
  }

  @Test
  void saysSoWhenNoChainIsOpen() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());

    List<ChatEvent> events = run(request("fix the script in Normalize payload"));

    assertTrue(text(events).contains("No chain context found"));
    verify(writer, never()).write(any(), any());
  }

  @Test
  void offersTheChangeAsADecisionCard() {
    captures(propertyPatch("element-script", "script", "return 201"));

    List<ChatEvent> events = run(request("fix the script in Normalize payload"));

    ChatEvent.Decision card = decision(events);
    assertEquals(ChatEvent.CHAIN_PATCH_ARTIFACT, card.artifactType());
    assertEquals(
        List.of(ChatEvent.APPLY_CHAIN_PATCH_ACTION, ChatEvent.REQUEST_CHANGES_ACTION),
        card.actions());
  }

  @Test
  void showsTheOldAndTheNewValueOnTheCard() {
    captures(propertyPatch("element-script", "script", "return 201"));

    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    assertTrue(card.question().contains("Normalize payload"), card.question());
    assertTrue(card.question().contains("return 200"), card.question());
    assertTrue(card.question().contains("return 201"), card.question());
  }

  @Test
  void writesNothingUntilTheCardIsAnswered() {
    captures(propertyPatch("element-script", "script", "return 201"));

    run(request("fix the script in Normalize payload"));

    verify(writer, never()).write(any(), any());
  }

  @Test
  void writesTheChangeWhenTheCardIsAnswered() {
    captures(propertyPatch("element-script", "script", "return 201"));
    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    List<ChatEvent> events = run(answer(card.artifactHash()));

    verify(writer).write(any(), any());
    assertTrue(text(events).contains("Normalize payload"), text(events));
  }

  @Test
  void refusesToWriteOverAChainThatChangedWhileTheCardWasOpen() {
    captures(propertyPatch("element-script", "script", "return 201"));
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
    captures(propertyPatch("element-script", "script", "return 201"));
    run(request("fix the script in Normalize payload"));

    List<ChatEvent> events = run(answer("some-other-patch"));

    verify(writer, never()).write(any(), any());
    assertTrue(text(events).toLowerCase().contains("no longer"), text(events));
  }

  @Test
  void refusesAPatchThatReachesOutsideWhatTheSkillOwns() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                false, false, Set.of(), Set.of(), Map.of("script", Set.of("script"))));
    captures(propertyPatch("element-trigger", "externalRoute", "true"));

    List<ChatEvent> events = run(request("make the trigger external"));

    assertTrue(text(events).contains("not owned"), text(events));
    verify(writer, never()).write(any(), any());
    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance), "no card should be offered");
  }

  @Test
  void refusesAStructurallyBrokenPatchWithoutCallingItAnOwnershipProblem() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                true,
                true,
                Set.of("script", "http-trigger"),
                Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of())));
    capturesStructuralEdgeWithoutAnEdgeId();

    List<ChatEvent> events = run(request("add an enrichment step after the trigger"));

    assertTrue(text(events).contains("could not be applied"), text(events));
    assertTrue(!text(events).contains("outside what I may edit"), text(events));
    verify(writer, never()).write(any(), any());
    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance), "no card should be offered");
  }

  @Test
  void refusesAPatchThatWouldBreakTheChainAndOffersNoCard() {
    captures(propertyPatch("element-script", "script", "return 201"));
    when(semanticValidator.introducedProblems(any(), any(), any()))
        .thenReturn(List.of("VR-G-004: element 'element-script' is unreachable"));

    List<ChatEvent> events = run(request("fix the script in Normalize payload"));

    assertTrue(text(events).contains("would leave the chain broken"), text(events));
    assertTrue(text(events).contains("unreachable"), text(events));
    // Distinct from both the ownership refusal and the structural one.
    assertTrue(!text(events).contains("outside what I may edit"), text(events));
    assertTrue(!text(events).contains("could not be applied"), text(events));
    verify(writer, never()).write(any(), any());
    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance), "no card should be offered");
  }

  @Test
  void listsTheElementsAndConnectionsItWouldAddOnTheCard() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                true,
                true,
                Set.of("script", "http-trigger"),
                Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of())));
    capturesStructural();

    ChatEvent.Decision card = decision(run(request("add an enrichment step after the trigger")));

    assertTrue(card.question().contains("Adds Enrich payload"), card.question());
    assertTrue(card.question().contains("Connects Receive order"), card.question());
    assertTrue(card.question().contains("to Enrich payload"), card.question());
  }

  /**
   * A removal changes no element, so the reply has to be built from what went rather than from what
   * changed -- otherwise a delete the reader just approved comes back as "nothing needed changing".
   */
  @Test
  void namesWhatItRemovedWhenTheCardIsAnswered() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                true, true, true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script"))));
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of(), List.of(), null, null, List.of("element-script")));
    capturesRemovalOf("element-script");
    ChatEvent.Decision card = decision(run(request("delete the Normalize payload step")));

    List<ChatEvent> events = run(answer(card.artifactHash()));

    assertTrue(card.question().contains("Removes Normalize payload"), card.question());
    assertTrue(card.question().contains("cannot be undone"), card.question());
    assertTrue(text(events).contains("Removed Normalize payload"), text(events));
  }

  @Test
  void saysTheChainWasPutBackWhenTheWriteFailedAndWasUnwound() {
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of(),
                List.of("element-script"),
                "schema said no",
                null,
                List.of(),
                ChainPatchWriteResult.RollbackOutcome.COMPLETED));
    captures(propertyPatch("element-script", "script", "return 201"));
    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    List<ChatEvent> events = run(answer(card.artifactHash()));

    assertTrue(text(events).contains("put the chain back as it was"), text(events));
  }

  /** The one case the reader has to be told plainly, because nothing here can fix it for them. */
  @Test
  void namesWhatIsGoneWhenTheChainCannotBePutBack() {
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of(),
                List.of("element-trigger"),
                "catalog down",
                null,
                List.of("element-script"),
                ChainPatchWriteResult.RollbackOutcome.REFUSED));
    captures(propertyPatch("element-script", "script", "return 201"));
    ChatEvent.Decision card = decision(run(request("fix the script in Normalize payload")));

    List<ChatEvent> events = run(answer(card.artifactHash()));

    assertTrue(text(events).contains("could not put the chain back"), text(events));
    assertTrue(text(events).contains("Normalize payload"), text(events));
  }

  @Test
  void saysSoWhenTheModelProposedNothing() {
    List<ChatEvent> events = run(request("do something vague"));

    assertTrue(text(events).contains("no change"), text(events));
    verify(writer, never()).write(any(), any());
  }

  @Test
  void asksWhichElementItShouldChangeWhenSeveralMatch() {
    asks("Two elements match: Normalize payload and Normalize headers. Which one did you mean?");

    List<ChatEvent> events = run(request("fix the normalize step"));

    assertTrue(text(events).contains("Normalize headers"), text(events));
    verify(writer, never()).write(any(), any());
    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance),
        "no card should be offered: " + text(events));
  }

  @Test
  void asksForAnExactNameOrIdWhenNothingMatches() {
    asks("No element in this chain matches that. Give me the exact element name or id.");

    List<ChatEvent> events = run(request("fix the deduplicate step"));

    assertTrue(text(events).contains("exact element name or id"), text(events));
    verify(writer, never()).write(any(), any());
    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance),
        "no card should be offered: " + text(events));
  }

  @Test
  void patchesTheElementNamedInTheAnswerToItsQuestion() {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenReturn(
            Multi.createFrom()
                .item("Two elements match: Normalize payload and Normalize headers. Which one?"))
        .thenAnswer(capturing(propertyPatch("element-script", "script", "return 201")));

    List<ChatEvent> question = run(request("fix the normalize step"));
    ChatEvent.Decision card = decision(run(request("Normalize payload")));

    assertTrue(text(question).contains("Normalize headers"), text(question));
    assertTrue(card.question().contains("Normalize payload"), card.question());
  }

  @Test
  void resolvesFromAPastedLogThroughTheSameRequestPath() {
    ArgumentCaptor<String> agentMessage = ArgumentCaptor.forClass(String.class);
    captures(propertyPatch("element-script", "script", "return 201"));

    ChatEvent.Decision card =
        decision(
            run(
                request(
                    "ERROR element-script: customer id missing from the outgoing body\n"
                        + "fix this script")));

    verify(agent).chat(eq(CONVERSATION_ID), agentMessage.capture());
    assertTrue(agentMessage.getValue().contains("customer id missing"), agentMessage.getValue());
    assertTrue(card.question().contains("Normalize payload"), card.question());
  }

  @Test
  void offersTheCompilersNetPatchAsTheCardAndWritesThatSamePatch() {
    compiles(propertyPatch("element-script", "script", "return 201"));

    ChatEvent.Decision card = decision(run(request("change the operation on the order call")));
    run(answer(card.artifactHash()));

    verify(agent, never()).chat(any(), any());
    ArgumentCaptor<GraphPatch> written = ArgumentCaptor.forClass(GraphPatch.class);
    verify(writer).write(any(), written.capture());
    assertEquals(
        List.of("return 201"),
        written.getValue().propertyPatches().stream().map(p -> p.property().value()).toList());
  }

  @Test
  void asksTheCompilersQuestionAndProposesNothing() {
    when(editCompiler.compile(any()))
        .thenReturn(
            new ChainEditOutcome.Clarification(
                "Several operations match. Which one do you mean?",
                List.of("Get order status (GET /orders/{id}/status) in Orders")));

    List<ChatEvent> events = run(request("change the operation on the order call"));

    verify(agent, never()).chat(any(), any());
    assertTrue(
        events.stream().noneMatch(ChatEvent.Decision.class::isInstance), "no card should be shown");
    assertTrue(text(events).contains("Get order status"), text(events));
  }

  private void compiles(PropertyPatch propertyPatch) {
    when(editCompiler.compile(any()))
        .thenAnswer(
            invocation -> {
              ChainEditRequest editRequest = invocation.getArgument(0);
              ChainPlanGraph base = editRequest.imported().graph();
              GraphPatch netPatch =
                  new GraphPatch(
                      "net-1",
                      "cip-service-call-generator",
                      List.of(),
                      List.of(),
                      List.of(propertyPatch),
                      List.of(),
                      List.of(),
                      "rebind");
              return new ChainEditOutcome.Proposal(
                  netPatch,
                  base,
                  base,
                  new ChainEditIntent(
                      ChainEditAction.REBIND_SERVICE_CALL,
                      List.of(propertyPatch.targetNodeId()),
                      "rebind",
                      null,
                      List.of()),
                  List.of(),
                  List.of("cip-service-call-generator"),
                  null);
            });
  }

  private void captures(PropertyPatch propertyPatch) {
    when(agent.chat(eq(CONVERSATION_ID), any())).thenAnswer(capturing(propertyPatch));
  }

  private void capturesRemovalOf(String nodeId) {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenAnswer(
            invocation -> {
              patchStore.putCapture(
                  CONVERSATION_ID,
                  new ChainPatchCapture(
                      "patch-4",
                      List.of(
                          new org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch(
                              GraphPatchOperation.REMOVE, null, nodeId)),
                      List.of(),
                      List.of(),
                      "the step is no longer needed"));
              return Multi.createFrom().<String>empty();
            });
  }

  private void asks(String question) {
    when(agent.chat(eq(CONVERSATION_ID), any())).thenReturn(Multi.createFrom().item(question));
  }

  private Answer<Multi<String>> capturing(PropertyPatch propertyPatch) {
    return invocation -> {
      patchStore.putCapture(
          CONVERSATION_ID,
          new ChainPatchCapture(
              "patch-1", List.of(), List.of(), List.of(propertyPatch), "keeps the customer id"));
      return Multi.createFrom().empty();
    };
  }

  /** Ownership passes (both node and edge adds are owned); GraphPatchApplier's own edge-id
   * check is what refuses this one, exercising the non-ownership branch of a failed apply. */
  private void capturesStructuralEdgeWithoutAnEdgeId() {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenAnswer(
            invocation -> {
              patchStore.putCapture(
                  CONVERSATION_ID,
                  new ChainPatchCapture(
                      "patch-3",
                      List.of(
                          new org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch(
                              GraphPatchOperation.ADD,
                              new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                                  "node-new-script",
                                  "script",
                                  "Enrich payload",
                                  null,
                                  null,
                                  List.of(new PlanProperty("script", "return 42"))),
                              null)),
                      List.of(
                          new org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch(
                              GraphPatchOperation.ADD,
                              new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                                  null, "element-trigger", "node-new-script", null),
                              null)),
                      List.of(),
                      "adds an enrichment step"));
              return Multi.createFrom().empty();
            });
  }

  private void capturesStructural() {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenAnswer(
            invocation -> {
              patchStore.putCapture(
                  CONVERSATION_ID,
                  new ChainPatchCapture(
                      "patch-2",
                      List.of(
                          new org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch(
                              GraphPatchOperation.ADD,
                              new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                                  "node-new-script",
                                  "script",
                                  "Enrich payload",
                                  null,
                                  null,
                                  List.of(new PlanProperty("script", "return 42"))),
                              null)),
                      List.of(
                          new org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch(
                              GraphPatchOperation.ADD,
                              new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                                  "edge-new", "element-trigger", "node-new-script", null),
                              null)),
                      List.of(),
                      "adds an enrichment step"));
              return Multi.createFrom().empty();
            });
  }

  private static PropertyPatch propertyPatch(String nodeId, String key, String value) {
    return new PropertyPatch(
        GraphPatchOperation.UPDATE, nodeId, new PlanProperty(key, value));
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

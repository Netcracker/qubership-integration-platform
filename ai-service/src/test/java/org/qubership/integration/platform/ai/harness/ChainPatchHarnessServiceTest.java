package org.qubership.integration.platform.ai.harness;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.llm.agent.ChainPatchAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;

class ChainPatchHarnessServiceTest {

  private static final String CONVERSATION_ID = "conv-harness-patch";
  private static final String CHAIN_ID = "chain-1";

  private ChainCatalogFactsService factsService;
  private ChainPatchAgent agent;
  private ChainPatchOwnership ownership;
  private ChainPatchSemanticValidator semanticValidator;
  private ChainPatchWriter writer;
  private ChainPatchStore patchStore;
  private ChainPatchHarnessService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    factsService = mock(ChainCatalogFactsService.class);
    agent = mock(ChainPatchAgent.class);
    ownership = mock(ChainPatchOwnership.class);
    writer = mock(ChainPatchWriter.class);
    patchStore = new ChainPatchStore();

    when(factsService.load(CHAIN_ID)).thenReturn(facts());
    when(agent.chat(eq(CONVERSATION_ID), any())).thenReturn(Multi.createFrom().empty());
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                true, true, Set.of("script", "http-trigger"), Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of())));

    semanticValidator = mock(ChainPatchSemanticValidator.class);
    when(semanticValidator.introducedProblems(any(), any(), any())).thenReturn(List.of());

    service =
        new ChainPatchHarnessService(
            factsService,
            new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper)),
            agent,
            patchStore,
            ownership,
            new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier()),
            semanticValidator,
            writer,
            new ChainElementCatalog(objectMapper),
            objectMapper);
  }

  @Test
  void appliesAPropertyChangeWithoutWaitingOnADecision() {
    captures(propertyPatch("element-script", "script", "return 201"));
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of("element-script"),
                List.of(),
                null,
                new MaterializationMap(CHAIN_ID, Map.of("element-script", "element-script"))));

    ChainPatchHarnessResponse response = service.run(request("fix the script in Normalize payload"));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    assertEquals(List.of("element-script"), response.changedElementIds());
    assertTrue(response.failedElementIds().isEmpty());
  }

  @Test
  void resolvesANewElementToItsRealCatalogId() {
    capturesStructural();
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of("node-new-script"),
                List.of(),
                null,
                new MaterializationMap(
                    CHAIN_ID,
                    Map.of(
                        "element-trigger", "element-trigger",
                        "element-script", "element-script",
                        "node-new-script", "catalog-new-script"))));

    ChainPatchHarnessResponse response = service.run(request("add an enrichment step"));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    assertEquals(List.of("catalog-new-script"), response.changedElementIds());
  }

  @Test
  void failsWithoutWritingWhenTheModelProposedNothing() {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenReturn(Multi.createFrom().item("Two elements match. Which one did you mean?"));

    ChainPatchHarnessResponse response = service.run(request("fix the normalize step"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(response.message().contains("Which one did you mean"), response.message());
    assertTrue(response.changedElementIds().isEmpty());
  }

  @Test
  void flagsAScopeViolationSeparatelyFromAnOrdinaryFailure() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                false, false, Set.of(), Set.of(), Map.of("script", Set.of("script"))));
    captures(propertyPatch("element-trigger", "externalRoute", "true"));

    ChainPatchHarnessResponse response = service.run(request("make the trigger external"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(response.scopeViolation());
  }

  @Test
  void flagsAStructurallyBrokenPatchAsAnOrdinaryFailureNotAScopeViolation() {
    // Default ownership from setUp() already allows adding a script node and an edge; the missing
    // edge id is what GraphPatchApplier itself refuses, after ownership has already passed.
    capturesStructuralEdgeWithoutAnEdgeId();

    ChainPatchHarnessResponse response = service.run(request("add an enrichment step"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(!response.scopeViolation());
    assertTrue(response.message().contains("could not be applied"), response.message());
  }

  @Test
  void refusesAPatchThatWouldBreakTheChainWithoutWritingAnything() {
    captures(propertyPatch("element-script", "script", "return 201"));
    when(semanticValidator.introducedProblems(any(), any(), any()))
        .thenReturn(List.of("VR-G-004: element 'element-script' is unreachable"));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertEquals(ChainPatchRefusal.SEMANTIC, response.refusal());
    assertTrue(!response.scopeViolation());
    assertTrue(response.message().contains("unreachable"), response.message());
    verify(writer, never()).write(any(), any());
  }

  @Test
  void reportsAPartialWriteFailureWithoutScopeViolation() {
    captures(propertyPatch("element-script", "script", "return 201"));
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of(),
                List.of("element-script"),
                "schema said no",
                new MaterializationMap(CHAIN_ID, Map.of())));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertEquals(List.of("element-script"), response.failedElementIds());
    assertTrue(!response.scopeViolation());
    assertEquals("schema said no", response.message());
  }

  /** A removal changes no element, so without this the run would report as having done nothing. */
  @Test
  void namesWhatItRemovedRatherThanReportingAnEmptyRun() {
    when(ownership.forChain(any(), any(), eq(true)))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                true,
                true,
                true,
                true,
                Set.of("script", "http-trigger"),
                Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of())));
    capturesRemovalOf("element-script");
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(
                List.of(),
                List.of(),
                null,
                new MaterializationMap(CHAIN_ID, Map.of()),
                List.of("element-script")));

    ChainPatchHarnessResponse response = service.run(removalRequest("delete Normalize payload"));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    assertEquals(List.of("element-script"), response.removedElementIds());
    assertTrue(response.message().contains("removed 1"), response.message());
  }

  @Test
  void reportsAChainReadFailure() {
    when(factsService.load(CHAIN_ID)).thenThrow(new IllegalStateException("catalog unreachable"));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(response.message().contains("catalog unreachable"), response.message());
  }

  private void captures(PropertyPatch propertyPatch) {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenAnswer(
            invocation -> {
              patchStore.putCapture(
                  CONVERSATION_ID,
                  new ChainPatchCapture(
                      "patch-1", List.of(), List.of(), List.of(propertyPatch), "keeps the customer id"));
              return Multi.createFrom().<String>empty();
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
                          new NodePatch(
                              GraphPatchOperation.ADD,
                              new ChainPlanNode(
                                  "node-new-script",
                                  "script",
                                  "Enrich payload",
                                  null,
                                  null,
                                  List.of(new PlanProperty("script", "return 42"))),
                              null)),
                      List.of(
                          new EdgePatch(
                              GraphPatchOperation.ADD,
                              new ChainPlanEdge("edge-new", "element-trigger", "node-new-script", null),
                              null)),
                      List.of(),
                      "adds an enrichment step"));
              return Multi.createFrom().<String>empty();
            });
  }

  private void capturesStructuralEdgeWithoutAnEdgeId() {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenAnswer(
            invocation -> {
              patchStore.putCapture(
                  CONVERSATION_ID,
                  new ChainPatchCapture(
                      "patch-3",
                      List.of(
                          new NodePatch(
                              GraphPatchOperation.ADD,
                              new ChainPlanNode(
                                  "node-new-script",
                                  "script",
                                  "Enrich payload",
                                  null,
                                  null,
                                  List.of(new PlanProperty("script", "return 42"))),
                              null)),
                      List.of(
                          new EdgePatch(
                              GraphPatchOperation.ADD,
                              new ChainPlanEdge(null, "element-trigger", "node-new-script", null),
                              null)),
                      List.of(),
                      "adds an enrichment step"));
              return Multi.createFrom().<String>empty();
            });
  }

  private void capturesRemovalOf(String nodeId) {
    when(agent.chat(eq(CONVERSATION_ID), any()))
        .thenAnswer(
            invocation -> {
              patchStore.putCapture(
                  CONVERSATION_ID,
                  new ChainPatchCapture(
                      "patch-4",
                      List.of(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId)),
                      List.of(),
                      List.of(),
                      "the step is no longer needed"));
              return Multi.createFrom().<String>empty();
            });
  }

  private static PropertyPatch propertyPatch(String nodeId, String key, String value) {
    return new PropertyPatch(GraphPatchOperation.UPDATE, nodeId, new PlanProperty(key, value));
  }

  private static ChainPatchHarnessRequest request(String prompt) {
    return new ChainPatchHarnessRequest(CONVERSATION_ID, CHAIN_ID, prompt);
  }

  private static ChainPatchHarnessRequest removalRequest(String prompt) {
    return new ChainPatchHarnessRequest(CONVERSATION_ID, CHAIN_ID, prompt, true);
  }

  private static ChainCatalogFacts facts() {
    return new ChainCatalogFacts(
        CHAIN_ID,
        "Order sync",
        "Syncs orders",
        2,
        0,
        "",
        List.of(
            new ChainCatalogElement("element-trigger", "http-trigger", "Receive order", null, Map.of()),
            new ChainCatalogElement(
                "element-script", "script", "Normalize payload", null, Map.of("script", "return 200"))),
        List.of(),
        "built_in_catalog");
  }
}

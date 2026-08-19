package org.qubership.integration.platform.ai.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompiler;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditOutcome;
import org.qubership.integration.platform.ai.chain.edit.ChainEditRequest;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.patch.ChainEditProposalAssembler;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchOwnership;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchSemanticValidator;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

/**
 * The regression driver. It answers no card, so what it must not do is diverge from the
 * interactive path in anything before the write.
 */
class ChainPatchHarnessServiceTest {

  private static final String CONVERSATION_ID = "conv-harness-patch";
  private static final String CHAIN_ID = "chain-1";

  private ChainCatalogFactsService factsService;
  private ChainEditCompiler editCompiler;
  private ChainPatchOwnership ownership;
  private ChainPatchWriter writer;
  private ChainPatchHarnessService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    factsService = mock(ChainCatalogFactsService.class);
    editCompiler = mock(ChainEditCompiler.class);
    ownership = mock(ChainPatchOwnership.class);
    writer = mock(ChainPatchWriter.class);

    when(factsService.load(CHAIN_ID)).thenReturn(facts());
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(
            new GraphPatchOwnershipPolicy(
                true,
                true,
                true,
                true,
                Set.of("script", "http-trigger"),
                Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of())));
    when(writer.write(any(), any()))
        .thenReturn(new ChainPatchWriteResult(List.of("element-script"), List.of(), null, null));
    ChainPatchSemanticValidator semanticValidator = mock(ChainPatchSemanticValidator.class);
    when(semanticValidator.introducedProblems(any(), any(), any())).thenReturn(List.of());

    service =
        new ChainPatchHarnessService(
            factsService,
            new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper)),
            editCompiler,
            new ChainEditProposalAssembler(
                ownership,
                new ValidatedGraphPatchApplier(
                    new GraphPatchOwnershipValidator(), new GraphPatchApplier()),
                semanticValidator),
            writer);
  }

  @Test
  void appliesTheCompilersNetPatchWithoutWaitingOnADecision() {
    compiles(propertyPatch("element-script", "script", "return 201"));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    assertEquals(List.of("element-script"), response.changedElementIds());
    verify(writer).write(any(), any());
  }

  @Test
  void failsWithoutWritingWhenTheCompilerProposedNothing() {
    when(editCompiler.compile(any()))
        .thenReturn(new ChainEditOutcome.ResolutionFailure("No element matched."));

    ChainPatchHarnessResponse response = service.run(request("fix something"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertEquals("No element matched.", response.message());
    verify(writer, never()).write(any(), any());
  }

  @Test
  void reportsAClarificationRatherThanGuessing() {
    when(editCompiler.compile(any()))
        .thenReturn(
            new ChainEditOutcome.Clarification(
                "Which element?",
                List.of("a", "b"),
                new ChainEditIntent(
                    ChainEditAction.CONFIGURE,
                    List.of(),
                    "fix the script",
                    null,
                    null,
                    null,
                    List.of("script"),
                    List.of("a", "b"))));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(response.message().contains("Which element?"), response.message());
    assertFalse(response.scopeViolation());
    verify(writer, never()).write(any(), any());
  }

  @Test
  void flagsAScopeViolationSeparatelyFromAnOrdinaryFailure() {
    when(ownership.forChain(any(), any(), anyBoolean()))
        .thenReturn(GraphPatchOwnershipPolicy.denyAll());
    compiles(propertyPatch("element-script", "script", "return 201"));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(response.scopeViolation());
    verify(writer, never()).write(any(), any());
  }

  @Test
  void namesWhatItRemovedRatherThanReportingAnEmptyRun() {
    compilesPatch(
        new GraphPatch(
            "net-remove",
            "chain-edit-transform",
            List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "element-script")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "removes the script"));
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
  void reportsAPartialWriteFailure() {
    compiles(propertyPatch("element-script", "script", "return 201"));
    when(writer.write(any(), any()))
        .thenReturn(
            new ChainPatchWriteResult(List.of(), List.of("element-script"), "schema said no", null));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertEquals(List.of("element-script"), response.failedElementIds());
    assertFalse(response.scopeViolation());
    assertEquals("schema said no", response.message());
  }

  @Test
  void reportsAChainReadFailure() {
    when(factsService.load(CHAIN_ID)).thenThrow(new IllegalStateException("catalog unreachable"));

    ChainPatchHarnessResponse response = service.run(request("fix the script"));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertTrue(response.message().contains("catalog unreachable"), response.message());
  }

  private void compiles(PropertyPatch propertyPatch) {
    compilesPatch(
        new GraphPatch(
            "net-1",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(propertyPatch),
            List.of(),
            List.of(),
            "rewrites the script"));
  }

  private void compilesPatch(GraphPatch netPatch) {
    when(editCompiler.compile(any(ChainEditRequest.class)))
        .thenAnswer(
            invocation -> {
              ChainEditRequest editRequest = invocation.getArgument(0);
              ChainPlanGraph base = editRequest.imported().graph();
              return new ChainEditOutcome.Proposal(
                  netPatch,
                  base,
                  base,
                  new ChainEditIntent(
                      ChainEditAction.CONFIGURE,
                      List.of("element-script"),
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

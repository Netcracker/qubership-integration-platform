package org.qubership.integration.platform.ai.chat.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinatorTest;
import org.qubership.integration.platform.ai.productpipeline.create.ProductPipelineRunView;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class ProductPipelineRunResourceTest {

  private CreateProductPipelineCoordinatorTest.FixtureHelper helper;
  private CreateProductPipelineCoordinator coordinator;
  private ConversationEvidenceStore evidenceStore;
  private ProductPipelineRunResource resource;

  @BeforeEach
  void setUp() throws Exception {
    helper = CreateProductPipelineCoordinatorTest.FixtureHelper.create();
    coordinator = helper.coordinator();
    evidenceStore = new ConversationEvidenceStore();
    resource =
        new ProductPipelineRunResource(
            true,
            helper.selectionService(),
            coordinator,
            helper.artifactStore(),
            evidenceStore,
            helper.objectMapper());
  }

  @Test
  void disabledEvidenceReturnsNotFound() {
    ProductPipelineRunResource disabled =
        new ProductPipelineRunResource(
            false,
            helper.selectionService(),
            coordinator,
            helper.artifactStore(),
            evidenceStore,
            helper.objectMapper());
    assertThrows(NotFoundException.class, () -> disabled.get("conv-evidence"));
  }

  @Test
  void terminalRunExposesDurableEvidenceWithoutBundle() {
    String conversationId = "conv-evidence-terminal";
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(request, conversationId).collect().asList().await().indefinitely();
    coordinator.approveCurrent(conversationId).collect().asList().await().indefinitely();

    for (int i = 0; i < 8; i++) {
      var doc = coordinator.loadRun(conversationId).orElseThrow();
      if (doc.run().status() == RunStatus.PLAN_APPROVED
          || doc.run().status() == RunStatus.WAITING_FOR_IMPLEMENT) {
        break;
      }
      if (doc.run().status() == RunStatus.WAITING_FOR_APPROVAL) {
        coordinator.approveCurrent(conversationId).collect().asList().await().indefinitely();
      } else if (doc.run().status() == RunStatus.WAITING_FOR_INPUT) {
        ChatRequest followUp = new ChatRequest();
        followUp.setResolvedEffectiveUserText("more details");
        coordinator.handle(followUp, conversationId).collect().asList().await().indefinitely();
      } else {
        ChatRequest nudge = new ChatRequest();
        nudge.setResolvedEffectiveUserText("continue");
        coordinator.handle(nudge, conversationId).collect().asList().await().indefinitely();
      }
    }

    ProductPipelineRunView view = resource.get(conversationId);
    assertTrue(
        Set.of("WAITING_FOR_IMPLEMENT", "WAITING_FOR_INPUT", "PLAN_APPROVED")
            .contains(view.currentState()),
        () -> "kinds=" + view.committedArtifactKinds() + " status=" + view.currentState());
    assertTrue(view.runRevision() > 0);
    assertFalse(view.transitions().isEmpty());
    assertFalse(view.attempts().isEmpty());
    assertTrue(
        view.committedArtifactKinds().contains("IMPLEMENTATION_PLAN"),
        () -> "kinds=" + view.committedArtifactKinds());
    assertTrue(
        view.committedArtifactKinds().contains("PLAN_VALIDATION_RESULT"),
        () -> "kinds=" + view.committedArtifactKinds());
    assertTrue(
        view.committedArtifactKinds().contains("APPROVAL_RECORD"),
        () -> "kinds=" + view.committedArtifactKinds());
    assertFalse(view.committedArtifactKinds().contains("GENERATED_CHAIN_BUNDLE"));
    assertFalse(view.decodedArtifacts().isEmpty());
    var pinnedPackage = view.runManifest().knowledgePackage();
    evidenceStore
        .getOrCreate(conversationId)
        .recordKnowledge(pinnedPackage, List.of("CIP:GEN-000049"), 120);
    evidenceStore
        .getOrCreate(conversationId)
        .recordKnowledge(pinnedPackage, List.of("CIP:RULE-000001"), 80);
    ProductPipelineRunView refreshed = resource.get(conversationId);
    assertEquals(pinnedPackage, refreshed.knowledgeContext().packageRef());
    assertEquals(
        List.of("CIP:GEN-000049", "CIP:RULE-000001"), refreshed.knowledgeContext().objectIds());
    assertEquals(200, refreshed.knowledgeContext().contentChars());
    assertTrue(
        refreshed.approvedPlanContentHash() == null
            || !refreshed.approvedPlanContentHash().isBlank());
  }

  @Test
  void graphPatchEvidenceOmitsRawPatchAndPropertyBodies() {
    var mapper = helper.objectMapper();
    var patchPayload =
        mapper
            .createObjectNode()
            .put("ownerCapabilityId", "cip-script-generator")
            .put("baseGraphDigest", "base-digest")
            .put("resultGraphDigest", "result-digest")
            .put("applicability", "APPLICABLE")
            .put("invocationKey", "script-1")
            .set(
                "patch",
                mapper
                    .createObjectNode()
                    .set(
                        "propertyPatches",
                        mapper
                            .createArrayNode()
                            .add(
                                mapper
                                    .createObjectNode()
                                    .put("nodeId", "script-1")
                                    .put("value", "return 'secret'"))));

    Map<String, Object> summary = ProductPipelineRunResource.summarizeGraphPatch(patchPayload);
    assertEquals("cip-script-generator", summary.get("ownerCapabilityId"));
    assertEquals("APPLICABLE", summary.get("applicability"));
    assertEquals("base-digest", summary.get("baseGraphDigest"));
    assertEquals("result-digest", summary.get("resultGraphDigest"));
    assertEquals(1, summary.get("propertyOperationCount"));
    assertFalse(summary.containsKey("patch"));
    assertFalse(summary.values().stream().anyMatch(v -> String.valueOf(v).contains("secret")));

    var snapshotPayload =
        mapper
            .createObjectNode()
            .put("chainId", "chain-1")
            .put("chainName", "Greetings")
            .set(
                "elements",
                mapper
                    .createArrayNode()
                    .add(
                        mapper
                            .createObjectNode()
                            .put("elementId", "el-1")
                            .put("type", "script")
                            .put("name", "Handler")
                            .set(
                                "properties",
                                mapper.createObjectNode().put("script", "return 'secret'"))));
    @SuppressWarnings("unchecked")
    Map<String, Object> redacted =
        (Map<String, Object>)
            ProductPipelineRunResource.redactCatalogSnapshot(mapper, snapshotPayload);
    assertEquals("chain-1", redacted.get("chainId"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> elements = (List<Map<String, Object>>) redacted.get("elements");
    assertEquals(1, elements.size());
    assertEquals(Map.of(), elements.get(0).get("properties"));
  }

  @Test
  void chainPlanGraphEvidenceClearsNodePropertiesFailClosed() {
    var mapper = helper.objectMapper();
    var nestedSecrets =
        mapper
            .createObjectNode()
            .put("privateToken", "tok-secret")
            .set("nested", mapper.createObjectNode().put("script", "return 'secret'"));
    var graphPayload =
        mapper
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .set(
                "nodes",
                mapper
                    .createArrayNode()
                    .add(
                        mapper
                            .createObjectNode()
                            .put("nodeId", "script-1")
                            .put("type", "script")
                            .put("label", "Handler")
                            .putNull("parentNodeId")
                            .set(
                                "properties",
                                mapper
                                    .createArrayNode()
                                    .add(
                                        mapper
                                            .createObjectNode()
                                            .put("key", "script")
                                            .put("value", "return 'secret'"))
                                    .add(
                                        mapper
                                            .createObjectNode()
                                            .put("key", "privateToken")
                                            .set("value", nestedSecrets)))));

    @SuppressWarnings("unchecked")
    Map<String, Object> redacted =
        (Map<String, Object>)
            ProductPipelineRunResource.redactChainPlanGraphEvidence(mapper, graphPayload);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) redacted.get("nodes");
    assertEquals(1, nodes.size());
    assertEquals("script-1", nodes.get(0).get("nodeId"));
    assertEquals("script", nodes.get(0).get("type"));
    assertEquals(Map.of(), nodes.get(0).get("properties"));
    String asText = String.valueOf(redacted);
    assertFalse(asText.contains("secret"));
    assertFalse(asText.contains("privateToken"));
    assertFalse(asText.contains("tok-secret"));
  }

  @Test
  void graphAssemblyResultEvidenceClearsNestedGraphNodeProperties() {
    var mapper = helper.objectMapper();
    var node =
        mapper
            .createObjectNode()
            .put("nodeId", "script-1")
            .put("type", "script")
            .put("label", "Handler");
    node.set(
        "properties",
        mapper.createObjectNode().put("script", "return 'secret'").put("privateToken", "tok-secret"));
    var graph = mapper.createObjectNode().put("schemaVersion", "1.0");
    graph.set("nodes", mapper.createArrayNode().add(node));
    var assemblyPayload = mapper.createObjectNode().put("schemaVersion", 1).put("graphDigest", "digest-abc");
    assemblyPayload.set("graph", graph);
    assemblyPayload.set("orderedPatchReferences", mapper.createArrayNode());
    assemblyPayload.set("ownershipFacts", mapper.createArrayNode());
    assemblyPayload.set("rejectedPatches", mapper.createArrayNode());

    @SuppressWarnings("unchecked")
    Map<String, Object> redacted =
        (Map<String, Object>)
            ProductPipelineRunResource.redactChainPlanGraphEvidence(mapper, assemblyPayload);

    assertEquals("digest-abc", redacted.get("graphDigest"));
    @SuppressWarnings("unchecked")
    Map<String, Object> redactedGraph = (Map<String, Object>) redacted.get("graph");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) redactedGraph.get("nodes");
    assertEquals(1, nodes.size());
    assertEquals("script-1", nodes.get(0).get("nodeId"));
    assertEquals(Map.of(), nodes.get(0).get("properties"));
    String asText = String.valueOf(redacted);
    assertFalse(asText.contains("secret"));
    assertFalse(asText.contains("privateToken"));
    assertFalse(asText.contains("tok-secret"));
  }

  @Test
  void evidenceIncludesOrderedPatchReferencesFromAssembly() {
    var mapper = helper.objectMapper();
    var store = helper.artifactStore();
    String runId = "run-evidence-patches";
    var provenance =
        new org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance(
            runId,
            "planning",
            "create-chain-v1",
            "1",
            "digest",
            "cip-script-generator",
            "1",
            "closure");
    var patch1 =
        store.append(
            new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts
                .AppendCommand(
                runId,
                org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                    .GRAPH_PATCH_ARTIFACT,
                "1",
                "cip-script-generator",
                "1",
                mapper
                    .createObjectNode()
                    .put("ownerCapabilityId", "cip-script-generator")
                    .put("applicability", "APPLICABLE")
                    .put("baseGraphDigest", "b1")
                    .put("resultGraphDigest", "r1")
                    .put("invocationKey", "inv-1")
                    .put("patchId", "p1"),
                List.of(),
                null,
                provenance));
    var patch2 =
        store.append(
            new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts
                .AppendCommand(
                runId,
                org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                    .GRAPH_PATCH_ARTIFACT,
                "1",
                "cip-timeout-generator",
                "1",
                mapper
                    .createObjectNode()
                    .put("ownerCapabilityId", "cip-timeout-generator")
                    .put("applicability", "APPLICABLE")
                    .put("baseGraphDigest", "b2")
                    .put("resultGraphDigest", "r2")
                    .put("invocationKey", "inv-2")
                    .put("patchId", "p2"),
                List.of(),
                null,
                provenance));
    var assemblyPayload = mapper.createObjectNode().put("schemaVersion", 1).put("graphDigest", "g");
    assemblyPayload.set("graph", mapper.createObjectNode().put("schemaVersion", "1.0"));
    assemblyPayload.set(
        "orderedPatchReferences",
        mapper
            .createArrayNode()
            .add(
                mapper
                    .createObjectNode()
                    .put("kind", "GRAPH_PATCH_ARTIFACT")
                    .put("artifactId", patch1.artifactId())
                    .put("contentHash", patch1.contentHash()))
            .add(
                mapper
                    .createObjectNode()
                    .put("kind", "GRAPH_PATCH_ARTIFACT")
                    .put("artifactId", patch2.artifactId())
                    .put("contentHash", patch2.contentHash())));
    assemblyPayload.set("ownershipFacts", mapper.createArrayNode());
    assemblyPayload.set("rejectedPatches", mapper.createArrayNode());
    store.append(
        new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts
            .AppendCommand(
            runId,
            org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                .GRAPH_ASSEMBLY_RESULT,
            "1",
            "graph-assembly",
            "1",
            assemblyPayload,
            List.of(),
            null,
            provenance));

    // Decode path used by evidence: walk assembly ordered refs
    var assemblyRev = store.latest(runId, org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind.GRAPH_ASSEMBLY_RESULT).orElseThrow();
    var assembly =
        store.payload(
            assemblyRev,
            org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult.class);
    assertEquals(2, assembly.orderedPatchReferences().size());

    java.util.LinkedHashSet<org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference>
        committed = new java.util.LinkedHashSet<>();
    committed.add(assemblyRev.reference());
    committed.addAll(assembly.orderedPatchReferences());
    assertEquals(3, committed.size());
    assertTrue(
        committed.stream()
            .filter(
                r ->
                    r.kind()
                        == org.qubership.integration.platform.ai.compiler.artifact
                            .CompilationArtifacts.Kind.GRAPH_PATCH_ARTIFACT)
            .count()
            == 2);

    Map<String, Object> summary1 =
        ProductPipelineRunResource.summarizeGraphPatch(patch1.payload());
    Map<String, Object> summary2 =
        ProductPipelineRunResource.summarizeGraphPatch(patch2.payload());
    assertEquals("inv-1", summary1.get("invocationKey"));
    assertEquals("inv-2", summary2.get("invocationKey"));
  }

  @Test
  void safeDecodeKindsRetainIdsBypassAndExposeDesignArtifacts() throws Exception {
    var field = ProductPipelineRunResource.class.getDeclaredField("SAFE_DECODE_KINDS");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Set<org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind> kinds =
        (Set<org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind>)
            field.get(null);
    assertTrue(
        kinds.contains(
            org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                .IDS_BYPASS));
    assertTrue(
        kinds.contains(
            org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                .IDS_DOCUMENT));
    assertTrue(
        kinds.contains(
            org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                .DESIGN_PLAN_REPORT));
    assertTrue(
        kinds.contains(
            org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                .DESIGN_EXECUTION_PLAN));
    assertTrue(
        kinds.contains(
            org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
                .DESIGN_EXECUTION_RESULT));
  }
}

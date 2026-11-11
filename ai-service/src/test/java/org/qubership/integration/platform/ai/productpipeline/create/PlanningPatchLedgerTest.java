package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphOwnershipFact;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;

class PlanningPatchLedgerTest {

  private ProductPipelineArtifactStore artifactStore;
  private GraphAssemblyService assemblyService;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.fixed(java.time.Instant.parse("2026-07-25T12:00:00Z"), java.time.ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    assemblyService = new GraphAssemblyService(new CanonicalGraphDigest(mapper));
  }

  @Test
  void twoApplicablePatchesPopulateOrderedRefsAndOwnershipFacts() {
    Reference ref1 = appendPatch("run-1", patch("p1", "cip-script-generator", "key-1", true));
    Reference ref2 = appendPatch("run-1", patch("p2", "cip-timeout-generator", "key-2", true));

    PlanningPatchLedger.Builder ledger = new PlanningPatchLedger.Builder();
    ledger.addApplicable(
        ref1,
        new GraphOwnershipFact(
            "cip-script-generator",
            "APPLY_GRAPH_PATCH",
            "p1",
            "compiler-node-ownership:cip-script-generator"));
    ledger.addApplicable(
        ref2,
        new GraphOwnershipFact(
            "cip-timeout-generator",
            "APPLY_GRAPH_PATCH",
            "p2",
            "compiler-node-ownership:cip-timeout-generator"));

    GraphAssemblyResult assembly =
        assemblyService.assembleFromGraph(
            graph(), ledger.build().orderedReferences(), ledger.build().ownershipFacts());

    assertEquals(2, assembly.orderedPatchReferences().size());
    assertEquals(2, assembly.ownershipFacts().size());
    assertEquals("APPLY_GRAPH_PATCH", assembly.ownershipFacts().get(0).operationKind());
    assertEquals("p1", assembly.ownershipFacts().get(0).target());
    assertEquals(
        "compiler-node-ownership:cip-script-generator",
        assembly.ownershipFacts().get(0).ruleSource());
  }

  @Test
  void notApplicablePatchEntersLedgerWithoutOwnershipFact() {
    GraphPatchArtifact na = patch("p-na", "cip-script-generator", "key-na", false);
    Reference ref = appendPatch("run-na", na);
    PlanningPatchLedger.Builder ledger = new PlanningPatchLedger.Builder();
    ledger.addNotApplicable(ref);

    GraphAssemblyResult assembly =
        assemblyService.assembleFromGraph(
            graph(), ledger.build().orderedReferences(), ledger.build().ownershipFacts());

    assertEquals(1, assembly.orderedPatchReferences().size());
    assertTrue(assembly.ownershipFacts().isEmpty());
    assertEquals(na.baseGraphDigest(), na.resultGraphDigest());
  }

  @Test
  void restartResolvesPersistedPatchReferences() {
    Reference ref1 = appendPatch("run-restart", patch("p1", "cip-script-generator", "key-1", true));
    Reference ref2 = appendPatch("run-restart", patch("p2", "cip-timeout-generator", "key-2", true));

    // Discard in-memory ledger; resolve only via durable store references.
    assertTrue(artifactStore.get("run-restart", ref1).isPresent());
    assertTrue(artifactStore.get("run-restart", ref2).isPresent());
    GraphPatchArtifact loaded1 =
        artifactStore.payload(
            artifactStore.get("run-restart", ref1).orElseThrow(), GraphPatchArtifact.class);
    GraphPatchArtifact loaded2 =
        artifactStore.payload(
            artifactStore.get("run-restart", ref2).orElseThrow(), GraphPatchArtifact.class);
    assertEquals("p1", loaded1.patchId());
    assertEquals("p2", loaded2.patchId());
    assertEquals(
        ref1.contentHash(), artifactStore.get("run-restart", ref1).orElseThrow().contentHash());
    assertEquals(
        ref2.contentHash(), artifactStore.get("run-restart", ref2).orElseThrow().contentHash());
  }

  @Test
  void crashWindowIdempotencyReusesSameInvocationKey() {
    GraphPatchArtifact payload = patch("p1", "cip-script-generator", "key-crash", true);
    Reference first = appendPatch("run-crash", payload);
    Reference second = appendOrReuse("run-crash", payload);
    assertEquals(first, second);
    assertEquals(1, artifactStore.history("run-crash", Kind.GRAPH_PATCH_ARTIFACT).size());
  }

  @Test
  void sameInvocationKeyDifferentPayloadIsContractFailure() {
    GraphPatchArtifact first = patch("p1", "cip-script-generator", "key-conflict", true);
    appendPatch("run-conflict", first);
    GraphPatchArtifact conflict =
        new GraphPatchArtifact(
            first.schemaVersion(),
            "p1-other",
            first.ownerCapabilityId(),
            first.baseGraphDigest(),
            "different-digest",
            first.patch(),
            first.consumedArtifacts(),
            first.sourceRequirementFactIds(),
            first.knowledgeCitations(),
            first.rationale(),
            first.applicability(),
            first.invocationKey());
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> appendOrReuse("run-conflict", conflict));
    assertTrue(ex.getMessage().contains("contract failure"));
  }

  private Reference appendOrReuse(String runId, GraphPatchArtifact payload) {
    var existing = artifactStore.findGraphPatchByInvocationKey(runId, payload.invocationKey());
    if (existing.isPresent()) {
      GraphPatchArtifact stored =
          artifactStore.payload(existing.orElseThrow(), GraphPatchArtifact.class);
      if (java.util.Objects.equals(stored, payload)) {
        return existing.orElseThrow().reference();
      }
      throw new IllegalStateException(
          "contract failure: GRAPH_PATCH_ARTIFACT invocationKey conflict for '"
              + payload.invocationKey()
              + "'");
    }
    return appendPatch(runId, payload);
  }

  private Reference appendPatch(String runId, GraphPatchArtifact payload) {
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                runId,
                Kind.GRAPH_PATCH_ARTIFACT,
                "1",
                payload.ownerCapabilityId(),
                "1",
                payload,
                List.of(),
                null,
                new ArtifactProvenance(
                    runId,
                    "planning",
                    "create-chain-v1",
                    "1",
                    "digest",
                    payload.ownerCapabilityId(),
                    "1",
                    "closure")));
    return revision.reference();
  }

  private static GraphPatchArtifact patch(
      String patchId, String owner, String invocationKey, boolean applicable) {
    String digest = applicable ? "base-" + patchId : "same-digest";
    String result = applicable ? "result-" + patchId : "same-digest";
    return new GraphPatchArtifact(
        1,
        patchId,
        owner,
        digest,
        result,
        new GraphPatch(
            patchId,
            owner,
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode("n1", "script", "S", null, null, List.of()),
                    "n1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "rationale"),
        List.of(),
        List.of(),
        List.of(),
        "rationale",
        applicable ? PatchApplicability.APPLICABLE : PatchApplicability.NOT_APPLICABLE,
        invocationKey);
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(new ChainPlanNode("n1", "script", "S", null, null, List.of())),
        List.of());
  }
}

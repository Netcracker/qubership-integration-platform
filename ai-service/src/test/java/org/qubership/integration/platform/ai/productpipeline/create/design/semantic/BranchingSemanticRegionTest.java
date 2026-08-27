package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchingSemanticRegionTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final ChainSemanticCanonicalizer canonicalizer = new ChainSemanticCanonicalizer();

  @Test
  void roundTripsSharedDownstreamReconvergence() throws Exception {
    SemanticRegion.Condition region =
        new SemanticRegion.Condition(
            "region-condition", "condition-1",
            List.of(branch("approved", "call-a"), branch("rejected", "call-b")),
            "script-common");
    assertEquals("script-common", roundTrip(region).reconvergenceNodeId());
  }

  @Test
  void roundTripsConditionWithoutReconvergence() throws Exception {
    SemanticRegion.Condition region =
        new SemanticRegion.Condition(
            "region-condition",
            "condition-1",
            List.of(branch("approved", "call-a"), elseBranch("fallback", "call-b")),
            null);
    assertNull(roundTrip(region).reconvergenceNodeId());
  }

  @Test
  void roundTripsSingleBranchAsyncSplit() throws Exception {
    SemanticRegion.Split region = SemanticFixtures.asyncSplitOneBranch();
    assertEquals(region, roundTrip(region));
    assertEquals(SplitMode.ASYNC, roundTrip(region).mode());
    assertEquals(1, roundTrip(region).branches().size());
    assertNull(roundTrip(region).reconvergenceNodeId());
  }

  @Test
  void roundTripsTwoBranchSyncSplit() throws Exception {
    SemanticRegion.Split region = SemanticFixtures.syncSplitTwoBranches();
    assertEquals(region, roundTrip(region));
    assertEquals(SplitMode.SYNC, roundTrip(region).mode());
    assertEquals(2, roundTrip(region).branches().size());
  }

  @Test
  void roundTripsSequenceRegion() throws Exception {
    SemanticRegion.Sequence region =
        new SemanticRegion.Sequence(
            "region-seq", List.of("trigger-http", "op-shared", "node-call"));
    assertEquals(region, roundTrip(region));
    assertEquals(
        List.of("trigger-http", "op-shared", "node-call"), roundTrip(region).memberNodeIds());
  }

  @Test
  void roundTripsTypedExecutionRoutes() throws Exception {
    assertEquals(
        new SemanticRoute.Sequence(), roundTrip(new SemanticRoute.Sequence()));
    assertEquals(
        new SemanticRoute.ConditionBranch("approved"),
        roundTrip(new SemanticRoute.ConditionBranch("approved")));
    assertEquals(
        new SemanticRoute.SplitBranch("notify"),
        roundTrip(new SemanticRoute.SplitBranch("notify")));
    SemanticRoute.Reconverge reconverge =
        new SemanticRoute.Reconverge(List.of("approved", "rejected"));
    assertEquals(reconverge, roundTrip(reconverge));
    SemanticExecutionEdge edge =
        new SemanticExecutionEdge(
            "edge-approved",
            "condition-1",
            "call-a",
            "region-condition",
            new SemanticRoute.ConditionBranch("approved"),
            null);
    assertEquals(
        edge, mapper.readValue(mapper.writeValueAsBytes(edge), SemanticExecutionEdge.class));
  }

  @Test
  void rejectsRegionKindAlias() throws Exception {
    SemanticRegion.Condition region =
        new SemanticRegion.Condition(
            "region-condition",
            "condition-1",
            List.of(branch("approved", "call-a")),
            "script-common");
    ObjectNode tree = mapper.valueToTree(region);
    tree.put("kind", "condition");
    assertThrows(JsonMappingException.class, () -> mapper.treeToValue(tree, SemanticRegion.class));
    tree.put("kind", "choice");
    assertThrows(JsonMappingException.class, () -> mapper.treeToValue(tree, SemanticRegion.class));
  }

  @Test
  void canonicalHashIgnoresRegionAndBranchListOrder() {
    ChainSemanticRevision base =
        revisionWithRegions(List.of(conditionRegion(), SemanticFixtures.syncSplitTwoBranches()));
    SemanticRegion.Split shuffledSplit =
        new SemanticRegion.Split(
            "region-sync-split",
            "split-1",
            SplitMode.SYNC,
            List.of(
                new SemanticBranch.Split("right", 1, "call-right", List.of("call-right")),
                new SemanticBranch.Split("left", 0, "call-left", List.of("call-left"))),
            null);
    ChainSemanticRevision shuffled =
        revisionWithRegions(
            List.of(
                shuffledSplit,
                new SemanticRegion.Condition(
                    "region-condition",
                    "condition-1",
                    List.of(branch("rejected", "call-b"), branch("approved", "call-a")),
                    "script-common")));
    assertEquals(canonicalizer.sha256(base), canonicalizer.sha256(shuffled));
  }

  private static SemanticRegion.Condition conditionRegion() {
    return new SemanticRegion.Condition(
        "region-condition",
        "condition-1",
        List.of(branch("approved", "call-a"), branch("rejected", "call-b")),
        "script-common");
  }

  private static ChainSemanticRevision revisionWithRegions(List<SemanticRegion> regions) {
    ChainSemanticRevision base =
        SemanticFixtures.revision(List.of(SemanticFixtures.entry("http-in", "trigger-http")));
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        base.nodes(),
        regions,
        base.executionEdges(),
        base.containment(),
        base.mappingIntents(),
        base.constraints(),
        base.assumptions(),
        base.citations());
  }

  private static SemanticBranch.Condition branch(String branchId, String entryNodeId) {
    return new SemanticBranch.Condition(
        branchId,
        ConditionBranchRole.IF,
        branchId,
        "approved".equals(branchId) ? 1 : 2,
        entryNodeId,
        List.of(entryNodeId));
  }

  private static SemanticBranch.Condition elseBranch(String branchId, String entryNodeId) {
    return new SemanticBranch.Condition(
        branchId,
        ConditionBranchRole.ELSE,
        null,
        0,
        entryNodeId,
        List.of(entryNodeId));
  }

  private SemanticRegion.Condition roundTrip(SemanticRegion.Condition region) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(region), SemanticRegion.Condition.class);
  }

  private SemanticRegion.Split roundTrip(SemanticRegion.Split region) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(region), SemanticRegion.Split.class);
  }

  private SemanticRegion.Sequence roundTrip(SemanticRegion.Sequence region) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(region), SemanticRegion.Sequence.class);
  }

  private SemanticRoute roundTrip(SemanticRoute route) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(route), SemanticRoute.class);
  }
}

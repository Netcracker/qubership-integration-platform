package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

class DesignPlanCaptureAdapterTest {

  private final DesignPlanCaptureAdapter adapter = new DesignPlanCaptureAdapter();

  @Test
  void ownsReleaseAndKeepsMachineIdentityIndependentOfSummaryWording() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanContract first =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Resolve endpoint", "Connect service"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");
    DesignPlanContract second =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Publish the endpoint", "Anything at all"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");

    assertEquals("2026.1", first.apiRelease());
    assertEquals(first.contractId(), second.contractId());
    assertNotEquals(first.steps().get(1).summary(), second.steps().get(1).summary());
  }

  @Test
  void rejectsDuplicateNotesBeforeProjection() {
    DesignPlanCapture valid = DesignPlanTestFixtures.validCapture("Trigger", "Call");
    List<DesignPlanCapture.Note> duplicate = new ArrayList<>(valid.notes());
    duplicate.add(valid.notes().getFirst());
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.adapt(
                new DesignPlanCapture(duplicate),
                revision, DesignPlanTestFixtures.brief(),
                DesignPlanTestFixtures.planningPin(revision),
                "2026.1"));
  }

  @Test
  void emptyNotesStillProduceAllTargetsAndSupportSteps() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanContract contract = adapter.adapt(new DesignPlanCapture(List.of()), revision,
        DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision), "2026.1");

    assertTrue(contract.steps().stream().anyMatch(step -> step.claims().stream().anyMatch(claim ->
        claim.targetKind() == TargetKind.ENTRY_POINT && claim.role() == ClaimRole.PRODUCER)));
    assertTrue(contract.steps().stream().anyMatch(step -> step.claims().stream().anyMatch(claim ->
        claim.targetKind() == TargetKind.SERVICE_CALL && claim.role() == ClaimRole.PRODUCER)));
    assertEquals("cip-chain-validator", contract.steps().getLast().owner().id());
    assertTrue(new DesignPlanContractValidator().findings(contract, revision,
        DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision)).isEmpty());
  }
  @Test
  void approvalShowsTheOwnerOfAnApprovedBehaviorFact() {
    var revision = DesignPlanTestFixtures.revision();
    var pin = DesignPlanTestFixtures.planningPin(revision);
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("sender-auth",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "",
        "Disable context propagation", "", "", "", "", "", "", List.of("call-1"));
    var facts = new ArrayList<>(DesignPlanTestFixtures.brief().facts());
    facts.add(fact);
    var brief = DesignPlanTestFixtures.brief().withFacts(facts);
    var contract = adapter.adapt(new DesignPlanCapture(List.of()), revision, brief, pin, "2026.1");
    var report = new DesignPlanReportRenderer().render(contract);
    var projection = new DesignPlanProjector().project(contract, report, revision, pin, brief);
    var approval = new DesignImplementationPlanRenderer().render(report, projection, revision, brief);
    assertTrue(approval.planText().contains("interactionIds=[call-1]"), approval.planText());
    assertTrue(approval.planText().contains("sourceFactId=" + fact.sourceFactId()), approval.planText());
  }

  @Test
  void chainCallUsesAnElementTargetOwnedByComposition() {
    var base = DesignPlanTestFixtures.revision();
    var call = base.nodes().stream()
        .filter(org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode.ServiceCall.class::isInstance)
        .findFirst().orElseThrow();
    var nodes = new ArrayList<>(base.nodes());
    nodes.set(nodes.indexOf(call),
        new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode.Operation(
            call.nodeId(), "chain-call-2", call.provenance()));
    var revision = new ChainSemanticRevision(base.schemaVersion(), base.revisionId(), base.chainIdentity(),
        base.compilerContractVersion(), base.entryPoints(), nodes, base.regions(), base.executionEdges(),
        base.containment(), base.mappingIntents(), base.constraints(), base.assumptions(), base.citations());
    var pin = DesignPlanTestFixtures.pinWithSkills(revision, "cip-http-trigger-endpoint-generator",
        "cip-composition-generator", "cip-structure-generator", "cip-chain-assembler");
    var capture = new DesignPlanCapture(List.of(
        new DesignPlanCapture.Note(TargetKind.ELEMENT_NODE, call.nodeId(), "Call the approved chain")));
    var contract = adapter.adapt(capture, revision, DesignPlanTestFixtures.brief(), pin, "2026.1");
    assertTrue(contract.steps().stream().anyMatch(step -> step.owner().id().equals("cip-composition-generator")
        && step.claims().stream().anyMatch(claim -> claim.targetKind() == TargetKind.ELEMENT_NODE
            && claim.targetId().equals(call.nodeId()))));
    assertThrows(IllegalArgumentException.class, () -> adapter.adapt(new DesignPlanCapture(List.of(
        new DesignPlanCapture.Note(TargetKind.SERVICE_CALL, call.nodeId(), "Wrong target kind"))),
        revision, DesignPlanTestFixtures.brief(), pin, "2026.1"));
  }

}

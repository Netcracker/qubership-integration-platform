package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Claim;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Owner;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Step;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class DesignPlanContractValidatorTest {

  private static final Map<String, String> OWNER_BY_TARGET =
      Map.ofEntries(
          Map.entry("entry-1", "cip-http-trigger-endpoint-generator"),
          Map.entry("call-1", "cip-service-call-generator"),
          Map.entry("map-init", "cip-script-generator"),
          Map.entry("sequence", "cip-structure-generator"),
          Map.entry("condition", "cip-structure-generator"),
          Map.entry("split", "cip-structure-generator"),
          Map.entry("loop", "cip-loop-generator"),
          Map.entry("retry", "cip-retry-generator"),
          Map.entry("error", "cip-error-handling-generator"),
          Map.entry("reuse-block", "cip-composition-generator"),
          Map.entry("reuse-ref", "cip-composition-generator"),
          Map.entry("send-kafka", "cip-service-call-generator"),
          Map.entry("script-behavior", "cip-script-generator"));

  private final DesignPlanContractValidator validator = new DesignPlanContractValidator();

  @Test
  void validatesEverySupportedSemanticTargetKindWithItsExactOwner() {
    Fixture fixture = completeFixture();

    assertTrue(
        validator
            .findings(fixture.contract(), fixture.revision(), fixture.brief(), fixture.pin())
            .isEmpty());
  }

  @Test
  void reportsMissingDuplicateAndWrongProducerForEveryTarget() {
    Fixture fixture = completeFixture();
    for (Step targetStep :
        fixture.contract().steps().stream().filter(step -> !step.claims().isEmpty()).toList()) {
      Claim target = targetStep.claims().getFirst();
      List<Step> missing =
          fixture.contract().steps().stream()
              .filter(step -> !step.stepId().equals(targetStep.stepId()))
              .toList();
      assertFinding(
          contract(fixture.revision(), missing),
          fixture,
          DesignPlanContractFinding.Code.MISSING_TARGET_PRODUCER,
          target.targetKind(),
          target.targetId());

      List<Step> duplicate = new ArrayList<>(fixture.contract().steps());
      duplicate.add(
          new Step(
              targetStep.stepId() + "-duplicate",
              "Duplicate",
              targetStep.owner(),
              targetStep.claims(),
              targetStep.dependsOnStepIds()));
      assertFinding(
          contract(fixture.revision(), duplicate),
          fixture,
          DesignPlanContractFinding.Code.DUPLICATE_TARGET_PRODUCER,
          target.targetKind(),
          target.targetId());

      List<Step> wrongOwner = new ArrayList<>(fixture.contract().steps());
      int index = wrongOwner.indexOf(targetStep);
      wrongOwner.set(
          index,
          new Step(
              targetStep.stepId(),
              targetStep.summary(),
              new Owner(OwnerKind.SKILL, "cip-chain-assembler"),
              targetStep.claims(),
              targetStep.dependsOnStepIds()));
      assertFinding(
          contract(fixture.revision(), wrongOwner),
          fixture,
          DesignPlanContractFinding.Code.OWNER_TARGET_MISMATCH,
          target.targetKind(),
          target.targetId());
    }
  }

  @Test
  void distinguishesAnUnknownTargetFromAWrongTargetKind() {
    Fixture fixture = completeFixture();
    List<Step> steps = new ArrayList<>(fixture.contract().steps());
    steps.add(
        step(
            "wrong-kind",
            "cip-trigger-generator",
            TargetKind.SERVICE_CALL,
            "entry-1"));
    steps.add(
        step("unknown", "cip-trigger-generator", TargetKind.ENTRY_POINT, "ghost"));
    List<DesignPlanContractFinding> findings =
        validator.findings(
            contract(fixture.revision(), steps),
            fixture.revision(),
            fixture.brief(),
            fixture.pin());

    assertTrue(
        findings.stream()
            .anyMatch(finding -> finding.code() == DesignPlanContractFinding.Code.TARGET_KIND_MISMATCH));
    assertTrue(
        findings.stream()
            .anyMatch(finding -> finding.code() == DesignPlanContractFinding.Code.UNKNOWN_TARGET));
    assertTrue(findings.stream().allMatch(DesignPlanContractFinding::blocking));
  }

  @Test
  void validatesUnknownSelfCyclicAndPinnedDependencies() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    RequirementBrief brief = DesignPlanTestFixtures.brief();
    CompilerRunPin pin = DesignPlanTestFixtures.pin(revision);
    Step trigger =
        step(
            "trigger",
            "cip-http-trigger-endpoint-generator",
            TargetKind.ENTRY_POINT,
            "entry-1");
    Step call =
        new Step(
            "call",
            "Call",
            new Owner(OwnerKind.SKILL, "cip-service-call-generator"),
            List.of(new Claim(TargetKind.SERVICE_CALL, "call-1", ClaimRole.PRODUCER)),
            List.of("ghost", "call"));
    List<DesignPlanContractFinding> malformed =
        validator.findings(contract(revision, List.of(trigger, call)), revision, brief, pin);
    assertCodes(
        malformed,
        DesignPlanContractFinding.Code.UNKNOWN_STEP_DEPENDENCY,
        DesignPlanContractFinding.Code.SELF_STEP_DEPENDENCY);

    Step cyclicTrigger =
        new Step(
            trigger.stepId(),
            trigger.summary(),
            trigger.owner(),
            trigger.claims(),
            List.of("call"));
    Step cyclicCall =
        new Step(
            call.stepId(),
            call.summary(),
            call.owner(),
            call.claims(),
            List.of("trigger"));
    List<DesignPlanContractFinding> cyclic =
        validator.findings(
            contract(revision, List.of(cyclicTrigger, cyclicCall)), revision, brief, pin);
    assertCodes(cyclic, DesignPlanContractFinding.Code.STEP_DEPENDENCY_CYCLE);
  }

  @Test
  void catalogOnlyNeedsNoBindingProducerAndForbidsApiHubSupportSteps() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    CompilerRunPin pin = DesignPlanTestFixtures.pin(revision);
    RequirementBrief unresolved =
        DesignPlanTestFixtures.brief()
            .withServiceCalls(
                List.of(
                    new RequirementServiceCall(
                        "call-1", "fact-call", "Orders", "createOrder")));
    DesignPlanContract base =
        new DesignPlanCaptureAdapter()
            .adapt(
                DesignPlanTestFixtures.validCapture("Trigger", "Call"),
                revision.revisionId(),
                "revision-hash",
                "2026.1");

    assertTrue(
        validator
            .findings(
                base,
                revision,
                unresolved,
                pin,
                DesignPlanContractValidator.BindingPolicy.CATALOG_ONLY)
            .isEmpty());
    assertCodes(
        validator.findings(base, revision, unresolved, pin),
        DesignPlanContractFinding.Code.MISSING_TARGET_PRODUCER);

    List<Step> withApiHub = new ArrayList<>(base.steps());
    withApiHub.add(
        new Step(
            "search",
            "Search",
            new Owner(OwnerKind.APIHUB_TOOL, "search_api_operations"),
            List.of(),
            List.of()));
    assertCodes(
        validator.findings(
            contract(revision, withApiHub),
            revision,
            unresolved,
            pin,
            DesignPlanContractValidator.BindingPolicy.CATALOG_ONLY),
        DesignPlanContractFinding.Code.OWNER_TARGET_MISMATCH);
  }

  @Test
  void httpSenderOperationExpectsServiceCallGeneratorNotApiHub() {
    ChainSemanticRevision base = DesignPlanTestFixtures.revision();
    List<SemanticNode> nodes = new ArrayList<>(base.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "send-http", "http-sender", new SemanticProvenance(List.of("fact-http"))));
    ChainSemanticRevision revision =
        new ChainSemanticRevision(
            base.schemaVersion(),
            base.revisionId(),
            base.chainIdentity(),
            base.compilerContractVersion(),
            base.entryPoints(),
            nodes,
            base.regions(),
            base.executionEdges(),
            base.containment(),
            base.mappingIntents(),
            base.constraints(),
            base.assumptions(),
            base.citations());
    CompilerRunPin pin =
        DesignPlanTestFixtures.pinWithSkills(
            revision, "cip-http-trigger-endpoint-generator", "cip-service-call-generator");
    List<Step> steps =
        List.of(
            step(
                "trigger",
                "cip-http-trigger-endpoint-generator",
                TargetKind.ENTRY_POINT,
                "entry-1"),
            step("call", "cip-service-call-generator", TargetKind.SERVICE_CALL, "call-1"),
            step("send-http", "cip-service-call-generator", TargetKind.ELEMENT_NODE, "send-http"));
    List<DesignPlanContractFinding> findings =
        validator.findings(
            contract(revision, steps), revision, DesignPlanTestFixtures.brief(), pin);
    assertTrue(findings.isEmpty());

    List<Step> apiHubOwner = new ArrayList<>(steps);
    apiHubOwner.set(
        2,
        new Step(
            "send-http",
            "Send HTTP",
            new Owner(OwnerKind.APIHUB_TOOL, "get_rest_api_operations_specification"),
            List.of(new Claim(TargetKind.ELEMENT_NODE, "send-http", ClaimRole.PRODUCER)),
            List.of()));
    assertFinding(
        contract(revision, apiHubOwner),
        new Fixture(revision, DesignPlanTestFixtures.brief(), pin, contract(revision, apiHubOwner)),
        DesignPlanContractFinding.Code.OWNER_TARGET_MISMATCH,
        TargetKind.ELEMENT_NODE,
        "send-http");
  }

  @Test
  void customHttpTriggerExpectsEndpointOwnerWithoutCatalogBinding() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    SemanticEntryPoint entry = revision.entryPoints().getFirst();
    RequirementBrief brief =
        DesignPlanTestFixtures.brief()
            .withCatalogBindings(List.of())
            .withFacts(List.of(RequirementFactFixtures.httpTriggerFact("entry-1", "POST", "/orders")));

    assertEquals(
        "cip-http-trigger-endpoint-generator",
        DesignPlanContractValidator.ownerForEntryPoint(entry, revision, brief));
  }

  @Test
  void implementedServiceHttpTriggerExpectsServiceCallOwnerWhenBindingPresent() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    SemanticEntryPoint entry = revision.entryPoints().getFirst();
    CatalogBindingHint binding =
        new CatalogBindingHint(
            CatalogBindingHint.SCHEMA_VERSION,
            "entry-1",
            "entry-1",
            "POST /orders",
            "sys-orders",
            "sg-orders",
            "spec-orders",
            "op-orders",
            "http",
            "POST",
            "/orders",
            "v1",
            Instant.EPOCH,
            "catalog-read:sys-orders/spec-orders/op-orders");
    RequirementBrief brief =
        DesignPlanTestFixtures.brief()
            .withFacts(
                List.of(
                    RequirementFactFixtures.implementedServiceHttpTriggerFact(
                        "entry-1", "Orders API", "createOrder")))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "entry-1", Direction.INBOUND, "Orders API", "createOrder", "")),
                    List.of()))
            .withCatalogBindings(List.of(binding));

    assertEquals(
        DesignPlanProjector.SERVICE_CALL_GENERATOR_SKILL_ID,
        DesignPlanContractValidator.ownerForEntryPoint(entry, revision, brief));
  }

  @Test
  void ambiguousHttpTriggerDoesNotDefaultToEndpointWhenBindingMissing() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    SemanticEntryPoint entry = revision.entryPoints().getFirst();
    RequirementBrief brief =
        new RequirementBrief("Orders", List.of(), List.of(), List.of(), List.of(), "summary")
            .withFacts(
                List.of(
                    new RequirementFact(
                        "entry-1",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Expose an HTTP API",
                        "",
                        "",
                        "",
                        "",
                        "")))
            .withFlow(
                new RequirementFlow(
                    List.of(new Interaction("entry-1", Direction.INBOUND, "Caller", "HTTP API", "")),
                    List.of()));

    assertNull(DesignPlanContractValidator.ownerForEntryPoint(entry, revision, brief));
    assertNotEquals(
        "cip-http-trigger-endpoint-generator",
        DesignPlanContractValidator.ownerForEntryPoint(entry, revision, brief));
  }

  @Test
  void requiresPinnedStructureAndAssemblerSupportOwners() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    CompilerRunPin pin =
        DesignPlanTestFixtures.pinWithSkills(
            revision,
            "cip-http-trigger-endpoint-generator",
            "cip-service-call-generator",
            "cip-structure-generator",
            "cip-chain-assembler");
    DesignPlanContract targetOnly =
        new DesignPlanCaptureAdapter()
            .adapt(
                DesignPlanTestFixtures.validCapture("Trigger", "Call"),
                revision.revisionId(),
                "revision-hash",
                "2026.1");

    long missingOwners =
        validator
            .findings(targetOnly, revision, DesignPlanTestFixtures.brief(), pin)
            .stream()
            .filter(
                finding ->
                    finding.code() == DesignPlanContractFinding.Code.MISSING_REQUIRED_OWNER)
            .count();

    assertEquals(2, missingOwners);
  }

  private void assertFinding(
      DesignPlanContract contract,
      Fixture fixture,
      DesignPlanContractFinding.Code code,
      TargetKind kind,
      String id) {
    assertTrue(
        validator
            .findings(contract, fixture.revision(), fixture.brief(), fixture.pin())
            .stream()
            .anyMatch(
                finding ->
                    finding.code() == code
                        && finding.targetKind() == kind
                        && finding.targetId().equals(id)),
        () -> code + " was not reported for " + kind + ":" + id);
  }

  private static void assertCodes(
      List<DesignPlanContractFinding> findings, DesignPlanContractFinding.Code... codes) {
    for (DesignPlanContractFinding.Code code : codes) {
      assertTrue(
          findings.stream().anyMatch(finding -> finding.code() == code),
          () -> code + " missing from " + findings);
    }
  }

  private static Fixture completeFixture() {
    ChainSemanticRevision base =
        org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures
            .linearOrdersWithMapping();
    List<SemanticNode> nodes = new ArrayList<>(base.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "script-behavior", "script", new SemanticProvenance(List.of("fact-behavior"))));
    nodes.add(
        new SemanticNode.Operation(
            "reuse-block", "reuse", new SemanticProvenance(List.of())));
    nodes.add(
        new SemanticNode.Operation(
            "reuse-ref", "reuse-reference", new SemanticProvenance(List.of())));
    nodes.add(
        new SemanticNode.Operation(
            "send-kafka", "kafka-sender-2", new SemanticProvenance(List.of())));
    List<SemanticRegion> regions =
        List.of(
            new SemanticRegion.Sequence("sequence", List.of("node-call")),
            new SemanticRegion.Condition("condition", "node-call", List.of(), null),
            new SemanticRegion.Split("split", "node-call", SplitMode.SYNC, List.of(), null),
            new SemanticRegion.Loop(
                "loop",
                "node-call",
                "node-call",
                List.of("node-call"),
                "node-call",
                new LoopPolicy(LoopMode.COPY, "${body}", 10)),
            new SemanticRegion.Retry(
                "retry",
                "node-call",
                "node-call",
                List.of("node-call"),
                "node-call",
                new RetryPolicy(3, 100)),
            new SemanticRegion.ErrorScope(
                "error", "node-call", "node-call", List.of(), null, List.of("node-call")));
    ChainSemanticRevision revision =
        new ChainSemanticRevision(
            base.schemaVersion(),
            base.revisionId(),
            base.chainIdentity(),
            base.compilerContractVersion(),
            base.entryPoints(),
            nodes,
            regions,
            base.executionEdges(),
            base.containment(),
            base.mappingIntents(),
            base.constraints(),
            base.assumptions(),
            base.citations());
    RequirementBrief brief = DesignPlanTestFixtures.brief().withMappingIntents(base.mappingIntents());
    CompilerRunPin pin =
        DesignPlanTestFixtures.pinWithSkills(
            revision,
            "cip-http-trigger-endpoint-generator",
            "cip-service-call-generator",
            "cip-script-generator",
            "cip-structure-generator",
            "cip-loop-generator",
            "cip-retry-generator",
            "cip-error-handling-generator",
            "cip-composition-generator",
            "cip-chain-assembler");
    List<Step> steps = new ArrayList<>();
    OWNER_BY_TARGET.forEach(
        (targetId, owner) -> steps.add(step(targetId, owner, kind(targetId), targetId)));
    steps.add(
        new Step(
            "assemble",
            "Assemble",
            new Owner(OwnerKind.SKILL, "cip-chain-assembler"),
            List.of(),
            List.of()));
    return new Fixture(revision, brief, pin, contract(revision, steps));
  }

  private static TargetKind kind(String targetId) {
    if ("entry-1".equals(targetId)) {
      return TargetKind.ENTRY_POINT;
    }
    if ("call-1".equals(targetId)) {
      return TargetKind.SERVICE_CALL;
    }
    if ("map-init".equals(targetId)) {
      return TargetKind.MAPPING_INTENT;
    }
    if ("script-behavior".equals(targetId)) {
      return TargetKind.BEHAVIOR_NODE;
    }
    if ("reuse-block".equals(targetId) || "reuse-ref".equals(targetId)) {
      return TargetKind.ELEMENT_NODE;
    }
    if ("send-kafka".equals(targetId)) {
      return TargetKind.ELEMENT_NODE;
    }
    return TargetKind.REGION;
  }

  private static Step step(String id, String owner, TargetKind kind, String targetId) {
    return new Step(
        id,
        "Produce " + targetId,
        new Owner(OwnerKind.SKILL, owner),
        List.of(new Claim(kind, targetId, ClaimRole.PRODUCER)),
        List.of());
  }

  private static DesignPlanContract contract(
      ChainSemanticRevision revision, List<Step> steps) {
    return new DesignPlanContract(
        DesignPlanCaptureAdapter.SCHEMA_VERSION,
        "plan",
        revision.revisionId(),
        "revision-hash",
        "2026.1",
        steps);
  }

  private record Fixture(
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin,
      DesignPlanContract contract) {}
}

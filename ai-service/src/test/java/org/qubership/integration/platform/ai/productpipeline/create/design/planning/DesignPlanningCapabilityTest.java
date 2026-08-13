package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class DesignPlanningCapabilityTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T12:00:00Z");
  private static final String RUN_ID = "run-design-planning-1";
  private static final String SEED_CAPABILITY = "seed-design-inputs";
  private static final String PINNED_SKILL_HASH = "pinned-cip-design-planner-hash";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRuntime runtime;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = designPlanningProfile();
    runtime =
        new ProductPipelineRuntime(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(new SeedDesignInputsCapability(), designPlanningCapability())),
            null,
            stubPinResolver(),
            Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  @Test
  void approvalTargetIsImplementationPlanWithCatalogFirstPolicyAndReusableInputs() {
    startRun();
    PipelineSignal.WaitingForApproval waiting =
        acceptInput("seed").stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected WaitingForApproval"));

    StageSnapshot beforeApproval = currentStage("design-planning");
    Set<Kind> beforeKinds =
        beforeApproval.outputRefs().stream().map(Reference::kind).collect(Collectors.toSet());
    assertFalse(beforeKinds.contains(Kind.CHAIN_PLAN_GRAPH));
    assertFalse(beforeKinds.contains(Kind.GRAPH_ASSEMBLY_RESULT));
    assertFalse(beforeKinds.contains(Kind.PLAN_VALIDATION_RESULT));
    assertFalse(beforeKinds.contains(Kind.COMPILER_VALIDATION_BUNDLE));

    Reference idsRef = refOf(beforeApproval, Kind.IDS_DOCUMENT);
    Reference flowRef = refOf(beforeApproval, Kind.NORMALIZED_DESIGN_FLOW);
    Reference reportRef = refOf(beforeApproval, Kind.DESIGN_PLAN_REPORT);
    Reference projectionRef = refOf(beforeApproval, Kind.DESIGN_EXECUTION_PLAN);
    Reference implementationPlanRef = refOf(beforeApproval, Kind.IMPLEMENTATION_PLAN);
    assertEquals(implementationPlanRef, waiting.candidate());

    // Same refs as the seed stage — no duplicate IDS/flow revisions.
    assertEquals(refOf(currentStage("seed"), Kind.IDS_DOCUMENT), idsRef);
    assertEquals(refOf(currentStage("seed"), Kind.NORMALIZED_DESIGN_FLOW), flowRef);

    runtime
        .approve(
            new ApproveCommand(
                RUN_ID, waiting.candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    ApprovalRecordV2 approval = latestApprovalV2();
    assertEquals(
        Set.of(idsRef, flowRef, reportRef, projectionRef, implementationPlanRef),
        Set.copyOf(approval.approvedCandidates()));
    assertEquals(implementationPlanRef, approval.target());
    assertEquals(ApprovalPolicy.CATALOG_FIRST_V1, approval.bindingResolutionPolicy());
    assertEquals(ApprovalPolicy.CATALOG_FIRST_V1_HASH, approval.bindingResolutionPolicyHash());
  }

  @Test
  void emitsDesignPlannerSkillProgressAroundExecution() {
    List<CapabilitySignal> signals =
        designPlanningCapability()
            .execute(sampleContext())
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && CipDesignPlannerAdapter.SKILL_ID.equals(sp.skillId())
                        && "running".equals(sp.status())));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && CipDesignPlannerAdapter.SKILL_ID.equals(sp.skillId())
                        && "completed".equals(sp.status())));
  }

  @Test
  void mapsPlannerContractFailureToContractFailureOutcome() {
    CipDesignPlannerAdapter failingPlanner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, pinnedSkillHash) -> {
              throw new PlannerContractException("forced contract failure");
            },
            new CipDesignPlannerReportParser());
    DesignPlanningCapability capability =
        new DesignPlanningCapability(
            failingPlanner, new DesignPlanProjector(), new DesignImplementationPlanRenderer());

    StageOutcome outcome =
        capability.execute(sampleContext()).collect().asList().await().indefinitely().stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("forced contract failure"));
  }

  @Test
  void rendererPreservesPlannerReportTextInOrder() {
    DesignPlanReport report = new DesignPlanReport("1", validReport());
    DesignExecutionPlan projection =
        new DesignPlanProjector()
            .project(
                report,
                sampleFlow(),
                sampleDag(),
                "catalog-hash",
                Map.of(CipDesignPlannerAdapter.SKILL_ID, PINNED_SKILL_HASH),
                Map.of(CipDesignPlannerAdapter.SKILL_ID, "addon-hash"));
    ImplementationPlan plan =
        new DesignImplementationPlanRenderer().render(report, projection, sampleFlow());

    int previousIndex = -1;
    for (DesignExecutionPlan.Step step : projection.steps()) {
      int index = plan.planText().indexOf(step.reportText());
      assertTrue(index >= 0, "missing reportText: " + step.reportText());
      assertTrue(index > previousIndex, "reportText order changed for " + step.stepId());
      previousIndex = index;
      assertTrue(plan.planText().contains(step.stepId()));
    }
    assertTrue(plan.planText().contains(ApprovalPolicy.CATALOG_FIRST_V1));
    assertTrue(plan.scriptOutcomes().isEmpty(), "pass-through mappings do not require scripts");
  }

  @Test
  void plannerInputDoesNotRequestScriptsForPassThroughMappings() {
    String input =
        DesignPlanningCapability.buildPlannerInput(sampleIds(), sampleFlow(), "2024.4");

    assertTrue(input.contains("No explicit data mappings. Do not plan mapping scripts."), input);
  }

  @Test
  void legacyApprovalRecordV2OmitsBindingResolutionPolicyFields() throws Exception {
    ApprovalRecordV2 v1Approval =
        new ApprovalRecordV2(
            new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
            "hash-plan",
            List.of(
                new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
                new Reference(Kind.PLAN_VALIDATION_RESULT, "val-1", "hash-val"),
                new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "hash-graph")),
            "user",
            null,
            Instant.parse("2026-07-22T12:00:00Z"));
    assertNull(v1Approval.bindingResolutionPolicy());
    assertNull(v1Approval.bindingResolutionPolicyHash());

    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    String serializedV1Approval =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(v1Approval);
    assertFalse(serializedV1Approval.contains("bindingResolutionPolicy"));
    assertEquals(
        readFixture("product-pipelines/approval/approval-record-v2-legacy.json").trim(),
        serializedV1Approval.trim());
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPinResolver resolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.doNothing().when(resolver).verifyAvailable(org.mockito.ArgumentMatchers.any());
    return resolver;
  }

  private DesignPlanningCapability designPlanningCapability() {
    CipDesignPlannerAdapter planner =
        new CipDesignPlannerAdapter(
            (conversationId, skillId, input, formatFailure, pinnedSkillHash) -> validReport(),
            new CipDesignPlannerReportParser());
    return new DesignPlanningCapability(
        planner, new DesignPlanProjector(), new DesignImplementationPlanRenderer());
  }

  private List<PipelineSignal> startRun() {
    return runtime
        .startOrResume(
            new StartOrResumeCommand("conv-design-planning", RUN_ID, profile, sampleManifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private List<PipelineSignal> acceptInput(String text) {
    return runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private StageSnapshot currentStage(String stageId) {
    return runStore.load(RUN_ID).orElseThrow().run().stages().stream()
        .filter(stage -> stage.stageId().equals(stageId))
        .findFirst()
        .orElseThrow();
  }

  private static Reference refOf(StageSnapshot stage, Kind kind) {
    return stage.outputRefs().stream()
        .filter(ref -> ref.kind() == kind)
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing " + kind + " in " + stage.outputRefs()));
  }

  private ApprovalRecordV2 latestApprovalV2() {
    return artifactStore.payload(
        artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
            .filter(item -> item.schemaVersion().equals("2"))
            .reduce((first, second) -> second)
            .orElseThrow(),
        ApprovalRecordV2.class);
  }

  private StageExecutionContext sampleContext() {
    IdsDocument ids = sampleIds();
    NormalizedDesignFlow flow = sampleFlow();
    Reference idsRef = new Reference(Kind.IDS_DOCUMENT, "ids-1", "ids-hash");
    Reference flowRef = new Reference(Kind.NORMALIZED_DESIGN_FLOW, "flow-1", "flow-hash");
    return new StageExecutionContext(
        RUN_ID,
        "conv-1",
        "design-planning",
        "exec-1",
        "attempt-1",
        profile,
        sampleManifest(),
        List.of(idsRef, flowRef),
        Map.of("idsDocument", ids, "normalizedDesignFlow", flow));
  }

  private ProductPipelineProfile designPlanningProfile() {
    return new ProductPipelineProfile(
        1,
        "test-design-planning",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "seed",
                SEED_CAPABILITY,
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(
                    new ArtifactTypeRef("ids-document", 1),
                    new ArtifactTypeRef("normalized-design-flow", 1)),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-planning",
                DesignPlanningCapability.CAPABILITY_ID,
                List.of(
                    new ArtifactTypeRef("ids-document", 1),
                    new ArtifactTypeRef("normalized-design-flow", 1),
                    new ArtifactTypeRef("run-manifest", 1)),
                List.of(),
                List.of(
                    new ArtifactTypeRef("design-plan-report", 1),
                    new ArtifactTypeRef("design-execution-plan", 1),
                    new ArtifactTypeRef("implementation-plan", 2)),
                List.of(),
                new ApprovalPolicy(
                    new ArtifactTypeRef("implementation-plan", 2),
                    List.of(
                        new ArtifactTypeRef("ids-document", 1),
                        new ArtifactTypeRef("normalized-design-flow", 1),
                        new ArtifactTypeRef("design-plan-report", 1),
                        new ArtifactTypeRef("design-execution-plan", 1),
                        new ArtifactTypeRef("implementation-plan", 2)),
                    ApprovalPolicy.CATALOG_FIRST_V1,
                    ApprovalPolicy.CATALOG_FIRST_V1_HASH),
                null,
                new RetryPolicy(0, 1L),
                null)),
        new TerminalPolicy("design-planning", "PLAN_APPROVED"),
        List.of(SEED_CAPABILITY, DesignPlanningCapability.CAPABILITY_ID));
  }

  private RunManifest sampleManifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry(DesignPlanningCapability.CAPABILITY_ID, "1", "c1")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        new CompilerRunPin(
            "compiler",
            "1",
            "digest",
            2,
            "1",
            "catalog-hash",
            sampleDag(),
            List.of(DesignPlanningCapability.CAPABILITY_ID),
            Map.of(CipDesignPlannerAdapter.SKILL_ID, PINNED_SKILL_HASH),
            Map.of(CipDesignPlannerAdapter.SKILL_ID, "addon-hash"),
            List.of()));
  }

  private static String readFixture(String path) throws Exception {
    try (InputStream in =
        DesignPlanningCapabilityTest.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static IdsDocument sampleIds() {
    return new IdsDocument(
        "1",
        IdsDocument.Mode.PROVIDED,
        "user-ids",
        "source-hash",
        "flow-hash",
        "ids-document-parser@1",
        """
        # IDS
        ## Integration flow for CIP Chain - Orders
        ```mermaid
        sequenceDiagram
          autonumber
          participant Client
          participant CIP
          participant Orders
          Client->>CIP: createOrder
          CIP->>Orders: createOrder
        ```
        """);
  }

  private static NormalizedDesignFlow sampleFlow() {
    return DesignPlanProjectorTestSupport.sampleFlow();
  }

  private static ResolvedCompilerDag sampleDag() {
    return DesignPlanProjectorTestSupport.sampleDag();
  }

  private static String validReport() {
    return DesignPlanProjectorTestSupport.validReport();
  }

  private static final class SeedDesignInputsCapability implements StageCapability {
    @Override
    public String capabilityId() {
      return SEED_CAPABILITY;
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      if (!context.attributes().containsKey("userText")) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need seed input")));
      }
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  new StageOutcome(
                      StageOutcomeClass.SUCCEEDED,
                      List.of(
                          new ArtifactCandidate(Kind.IDS_DOCUMENT, sampleIds(), List.of()),
                          new ArtifactCandidate(
                              Kind.NORMALIZED_DESIGN_FLOW, sampleFlow(), List.of())),
                      "seeded design inputs",
                      null)));
    }
  }

  /** Shared fixtures mirroring DesignPlanProjectorTest without package-private coupling. */
  private static final class DesignPlanProjectorTestSupport {
    private static String validReport() {
      return """
          1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
          2. Find API Orders API for Orders Service in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
          3. Get API operation specification Orders API for Orders Service in APIHub (APIHub MCP get_rest_api_operations_specification)
          4. Resolve External integration target Orders Service from the retrieved spec (binding for cip-service-call-generator)
          5. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
          6. Generate Service Call element for Orders Service.createOrder bound to the retrieved spec (cip-service-call-generator)
          7. Generate Script element for Initialization (cip-script-generator)
          8. Generate Script element for Response (cip-script-generator)
          9. Generate execution structure and element ordering (cip-structure-generator)
          10. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
          11. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
          12. Validate the assembled chain (cip-chain-validator)
          If you agree, reply **Agree** or **Execute plan** to proceed.
          """
          .trim();
    }

    private static NormalizedDesignFlow sampleFlow() {
      return new NormalizedDesignFlow(
          "1",
          "flow-1",
          "Orders",
          "Create order",
          new NormalizedDesignFlow.Trigger(
              "http",
              "p-client",
              "Orders API",
              "/orders",
              "createOrder",
              List.of("fact-trigger")),
          List.of(
              new NormalizedDesignFlow.Participant(
                  "p-client", "Client", "EXTERNAL", List.of("fact-p")),
              new NormalizedDesignFlow.Participant(
                  "p-orders", "Orders Service", "EXTERNAL", List.of("fact-p")),
              new NormalizedDesignFlow.Participant(
                  "p-orders-api", "Orders API", "EXTERNAL", List.of("fact-p"))),
          List.of(
              new NormalizedDesignFlow.Step(
                  "step-call",
                  "service-call",
                  "p-client",
                  "p-orders",
                  "createOrder",
                  "Create order",
                  List.of("fact-step"))),
          List.of(),
          List.of(),
          List.of(
              new NormalizedDesignFlow.DataMapping(
                  "map-init",
                  NormalizedDesignFlow.MappingStage.INITIALIZATION,
                  "step-trigger",
                  "step-call",
                  NormalizedDesignFlow.MappingMode.PASS_THROUGH,
                  List.of(),
                  List.of("fact-map")),
              new NormalizedDesignFlow.DataMapping(
                  "map-response",
                  NormalizedDesignFlow.MappingStage.RESPONSE,
                  "step-call",
                  "step-response",
                  NormalizedDesignFlow.MappingMode.PASS_THROUGH,
                  List.of(),
                  List.of("fact-map"))),
          List.of(),
          List.of());
    }

    private static ResolvedCompilerDag sampleDag() {
      return new ResolvedCompilerDag(
          List.of(
              node(
                  "cip-requirement-analyzer",
                  List.of(SkillArtifactType.RAW_USER_REQUEST.name()),
                  List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                  List.of(),
                  0),
              node(
                  "cip-naming-generator",
                  List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                  List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                  List.of("cip-requirement-analyzer"),
                  1),
              node(
                  "cip-trigger-generator",
                  List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                  List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                  List.of(),
                  2),
              node(
                  "cip-service-call-generator",
                  List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                  List.of(SkillArtifactType.GRAPH_PATCH.name()),
                  List.of("cip-trigger-generator"),
                  3),
              node(
                  "cip-script-generator",
                  List.of(SkillArtifactType.GRAPH_PATCH.name()),
                  List.of(SkillArtifactType.GRAPH_PATCH.name()),
                  List.of("cip-service-call-generator"),
                  4),
              node(
                  "cip-structure-generator",
                  List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                  List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                  List.of(
                      "cip-trigger-generator",
                      "cip-service-call-generator",
                      "cip-script-generator"),
                  5),
              node(
                  "cip-chain-assembler",
                  List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                  List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                  List.of("cip-structure-generator"),
                  6),
              node(
                  "cip-chain-validator",
                  List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                  List.of(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
                  List.of("cip-chain-assembler"),
                  7)),
          List.of(),
          "dag-digest");
    }

    private static ResolvedCompilerNode node(
        String skillId,
        List<String> consumes,
        List<String> produces,
        List<String> dependsOn,
        int level) {
      return new ResolvedCompilerNode(
          skillId,
          "Planning",
          null,
          consumes,
          produces,
          dependsOn,
          null,
          List.of(),
          List.of(),
          true,
          List.of(),
          level,
          0,
          true,
          CompilerNodeExecutionMode.LLM_SKILL,
          null);
    }
  }
}

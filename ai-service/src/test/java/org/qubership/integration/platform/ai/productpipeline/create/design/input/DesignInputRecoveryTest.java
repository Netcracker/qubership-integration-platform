package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRouteKind;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class DesignInputRecoveryTest {
  private static final String RUN = "capture-recovery";
  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  @ParameterizedTest
  @ValueSource(strings = {"regenerate", "missing", "ask", "invalid"})
  void rejectedTopologyReachesPlanningAfterRestartWithoutChangingTheBrief(String decisionMode) {
    verifyRecovery(decisionMode, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"regenerate", "missing"})
  void repeatedTopologyRejectionParksAfterRestartWithoutBlamingTheBrief(String decisionMode) {
    verifyRecovery(decisionMode, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"brief-question", "brief-revision", "park", "zero-budget"})
  void explicitRecoveryDecisionsAndDisabledRepairsDoNotRegenerate(String decisionMode) {
    verifyRecovery(decisionMode, false);
  }

  private void verifyRecovery(String decisionMode, boolean repeatFailure) {
    Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    ProductPipelineArtifactStore artifacts =
        new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, mapper, clock);
    RequirementBrief brief =
        MappingGapCoverage.skipUncovered(ChainSemanticCaptureFixtures.rockyBriefWithMapping());
    AtomicInteger calls = new AtomicInteger();
    ChainSemanticCaptureTool tool = captureTool();
    ChainSemanticCapture valid = ChainSemanticCaptureFixtures.rockyCapture();
    List<ChainSemanticCapture.CapturedEdge> edges = new ArrayList<>(valid.edges());
    var mapped = edges.remove(1);
    edges.add(
        new ChainSemanticCapture.CapturedEdge(
            mapped.sourceNodeId(),
            mapped.targetNodeId(),
            null,
            null,
            null,
            null,
            null,
            brief.mappingIntents().getFirst().mappingIntentId()));
    ChainSemanticCapture repaired =
        ChainSemanticCaptureFixtures.rockyCapture(valid.operations(), edges);
    edges = new ArrayList<>(edges);
    edges.add(
        new ChainSemanticCapture.CapturedEdge(
            "create-task",
            "task-result",
            null,
            SemanticRouteKind.CATCH_PATH,
            null,
            null,
            "catch-all",
            null));
    ChainSemanticCapture rejected =
        ChainSemanticCaptureFixtures.rockyCapture(valid.operations(), edges);
    DesignInputCapability design =
        new DesignInputCapability(
            (conversation, prompt) -> {
              int call = calls.incrementAndGet();
              if (call > 1) {
                assertTrue(prompt.contains("is missing a region"), prompt);
                assertTrue(prompt.contains("generic-barrier"), prompt);
                assertTrue(
                    prompt.contains(brief.mappingIntents().getFirst().mappingIntentId()), prompt);
              }
              String result =
                  tool.captureChainSemanticRevision(
                      call == 1 || repeatFailure ? rejected : repaired);
              if (call == 1) {
                assertTrue(result.contains("is missing a region"), result);
                assertTrue(result.contains("generic-barrier"), result);
              } else if (!repeatFailure) {
                assertTrue(result.contains("revision captured"), result);
              }
              return Multi.createFrom().item(result);
            },
            new DefaultChainSemanticIdsRenderer());
    StageCapability analysis =
        new StageCapability() {
          public String capabilityId() {
            return "analysis-cap";
          }

          public Multi<CapabilitySignal> execute(StageExecutionContext context) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(
                                new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())),
                            "brief ready",
                            null)));
          }
        };
    ArtifactTypeRef briefType = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef flowType = new ArtifactTypeRef("chain-semantic-revision", 1);
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "capture-recovery",
            "1",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "requirement-analysis",
                    "analysis-cap",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(briefType),
                    new ApprovalPolicy(briefType),
                    null,
                    new RetryPolicy(0, 0)),
                new ProfileStage(
                    "design-input",
                    design.capabilityId(),
                    List.of(briefType),
                    List.of(flowType, new ArtifactTypeRef("ids-document", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 0)),
                new ProfileStage(
                    "design-planning",
                    "planning-cap",
                    List.of(flowType),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 0))),
            new TerminalPolicy("design-planning", "PLAN_APPROVED"),
            List.of("analysis-cap", design.capabilityId(), "planning-cap"));
    RunManifest manifest =
        new RunManifest(
            RUN,
            null,
            List.of(),
            "product",
            profile.profileId(),
            "1",
            "sha",
            "baseline",
            "sha",
            List.of(),
            "sha",
            null,
            "24.4",
            List.of(),
            null);
    var agent = mock(FailureNarrativeAgent.class);
    var fake = FakeFailureNarrativeAgent.narrates("unused");
    fake.recoverRegenerates(Kind.CHAIN_SEMANTIC_REVISION, "Repair the generated topology.");
    when(agent.recover(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              if (decisionMode.equals("missing")) {
                return null;
              }
              var decision = fake.recover(invocation.getArgument(0), invocation.getArgument(1));
              if (decisionMode.equals("regenerate") || decisionMode.equals("zero-budget")) {
                return decision;
              }
              if (decisionMode.startsWith("brief-")) {
                var briefRef =
                    mapper.treeToValue(
                        mapper
                            .readTree((String) invocation.getArgument(1))
                            .path("evidence")
                            .path("approvedBriefRef"),
                        CompilationArtifacts.Reference.class);
                return new RecoveryDecision(
                    RecoveryCauseClass.BRIEF_DEFECT,
                    briefRef,
                    decision.evidenceRefs(),
                    decisionMode.equals("brief-question")
                        ? RecoveryAction.ASK_USER
                        : RecoveryAction.REVISE_BRIEF,
                    List.of(),
                    "Which response is required?",
                    "The response requirement needs clarification.");
              }
              if (decisionMode.equals("park")) {
                return new RecoveryDecision(
                    RecoveryCauseClass.DERIVATION_DEFECT,
                    decision.faultArtifactRef(),
                    decision.evidenceRefs(),
                    RecoveryAction.PARK,
                    List.of(),
                    "",
                    "Review the unsupported topology.");
              }
              return new RecoveryDecision(
                  decision.causeClass(),
                  decision.faultArtifactRef(),
                  decisionMode.equals("invalid")
                      ? List.of("unknown-evidence")
                      : decision.evidenceRefs(),
                  RecoveryAction.ASK_USER,
                  List.of(),
                  "How should this topology be repaired?",
                  "The generated topology is invalid.");
            });
    StageCapabilityRegistry registry = new StageCapabilityRegistry(List.of(analysis, design));
    ProductPipelineRunSupport support =
        ProductPipelineRunSupport.builder(runs, artifacts, registry, clock)
            .failureNarrative(new FailureNarrative(agent))
            .recoveryLedger(
                new RecoveryAttemptLedger(
                    new RecoveryAttemptLedger.Limits(
                        decisionMode.equals("zero-budget") ? 0 : 1, 1, 12)))
            .build();
    CreateChainTestOrchestrator runtime = new CreateChainTestOrchestrator(support, runs);
    runtime
        .startOrResume(
            new StartOrResumeCommand("capture-recovery-conversation", RUN, profile, manifest))
        .collect()
        .asList()
        .await()
        .indefinitely();
    runtime
        .recordInput(new AcceptInputCommand(RUN, "approved Rocky request"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    var approval =
        assertInstanceOf(
            StageDecision.WaitForApproval.class,
            support
                .stageExecutor()
                .execute(RUN, "requirement-analysis")
                .await()
                .indefinitely()
                .decision());
    runtime
        .recordApprove(
            new ApproveCommand(
                RUN, approval.candidate(), runs.load(RUN).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    var before = runs.load(RUN).orElseThrow().run().stages().getFirst();
    var first = support.stageExecutor().execute(RUN, "design-input").await().indefinitely();
    if (decisionMode.startsWith("brief-")
        || decisionMode.equals("park")
        || decisionMode.equals("zero-budget")) {
      var wait = assertInstanceOf(StageDecision.WaitForInput.class, first.decision());
      String expected =
          switch (decisionMode) {
            case "brief-question" -> PipelineGates.STAGE_CLARIFICATION;
            case "brief-revision" -> PipelineGates.RECOVERY_REVISE_BRIEF;
            case "park" -> PipelineGates.RECOVERY_UNCLASSIFIED;
            default -> PipelineGates.RECOVERY_REPEATED;
          };
      assertEquals(expected, PipelineGates.gateOf(wait.prompt()).orElseThrow());
      assertEquals(1, calls.get());
      assertEquals(before, runs.load(RUN).orElseThrow().run().stages().getFirst());
      assertEquals(1, artifacts.history(RUN, Kind.REQUIREMENT_BRIEF).size());
      return;
    }
    assertInstanceOf(StageDecision.Retry.class, first.decision());
    support.applyStageLifecycle(RUN, first).collect().asList().await().indefinitely();

    // Recreate runtime and stores so the retry depends on the persisted journal and evidence.
    runs = new ProductPipelineRunStore(blobs, mapper, clock);
    artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    support =
        ProductPipelineRunSupport.builder(runs, artifacts, registry, clock)
            .failureNarrative(new FailureNarrative(agent))
            .recoveryLedger(
                new RecoveryAttemptLedger(
                    new RecoveryAttemptLedger.Limits(
                        decisionMode.equals("zero-budget") ? 0 : 1, 1, 12)))
            .build();
    runtime = new CreateChainTestOrchestrator(support, runs);
    runtime
        .restoreForExternalWorkflow(
            new StartOrResumeCommand("capture-recovery-conversation", RUN, profile, manifest))
        .collect()
        .asList()
        .await()
        .indefinitely();
    var second = support.stageExecutor().execute(RUN, "design-input").await().indefinitely();
    if (repeatFailure) {
      var wait = assertInstanceOf(StageDecision.WaitForInput.class, second.decision());
      assertEquals(
          PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(wait.prompt()).orElseThrow());
      assertTrue(artifacts.latest(RUN, Kind.CHAIN_SEMANTIC_REVISION).isEmpty());
    } else {
      assertInstanceOf(StageDecision.Continue.class, second.decision());
      support.applyStageLifecycle(RUN, second).collect().asList().await().indefinitely();
      assertEquals("design-planning", runs.load(RUN).orElseThrow().run().currentStageId());
      assertTrue(artifacts.latest(RUN, Kind.CHAIN_SEMANTIC_REVISION).isPresent());
    }
    assertEquals(2, calls.get());
    assertEquals(before, runs.load(RUN).orElseThrow().run().stages().getFirst());
    assertEquals(1, artifacts.history(RUN, Kind.REQUIREMENT_BRIEF).size());
    assertEquals(
        brief,
        artifacts.payload(
            artifacts.latest(RUN, Kind.REQUIREMENT_BRIEF).orElseThrow(), RequirementBrief.class));
    assertTrue(artifacts.latest(RUN, Kind.MATERIALIZATION_RESULT).isEmpty());
  }

  private static ChainSemanticCaptureTool captureTool() {
    CatalogElementDescriptorLoader descriptors = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(descriptors);
    QipKnowledgePackRepository pack = mock(QipKnowledgePackRepository.class);
    Map<String, String> addons = new LinkedHashMap<>();
    for (String addonId : CONTRACT.requiredAddons()) {
      addons.put(addonId, "sha-" + addonId);
    }
    Map<String, String> files = new LinkedHashMap<>();
    files.put("knowledge/ai/validation-rules.yaml", "sha-validation-rules");
    files.put("knowledge/ai/GENERATOR_CONTRACTS.md", "sha-generator-contracts");
    files.put("knowledge/ai/generator-rule-mapping.md", "sha-generator-rule-mapping");
    when(pack.loadManifest())
        .thenReturn(
            new QipKnowledgePackManifest(
                new QipKnowledgePackVersion("v1", "v1"),
                "test",
                Instant.parse("2026-01-01T00:00:00Z"),
                files,
                List.of(),
                List.of(),
                List.of(),
                CONTRACT.contractVersion(),
                CONTRACT.sha256(),
                addons));
    return new ChainSemanticCaptureTool(
        new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer()),
        new DefaultChainSemanticRevisionValidator(),
        new ClasspathCompilerContractRepository(),
        pack,
        descriptors);
  }
}

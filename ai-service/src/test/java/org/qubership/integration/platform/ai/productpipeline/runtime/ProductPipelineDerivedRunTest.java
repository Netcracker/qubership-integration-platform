package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecord;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ProductPipelineDerivedRunTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T12:30:00Z");

  private CreateChainTestOrchestrator runtime;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunStore runStore;
  private ProductPipelineRunSupport runSupport;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runStore =
        new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runSupport =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(
                        FakeStageCapabilities.collector(), FakeStageCapabilities.finisher())),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .build();
    runtime =
        new CreateChainTestOrchestrator(runSupport, runStore);
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/two-stage-approval-v1.yaml")) {
      profile = ProductPipelineProfileParser.parse(in);
    }
  }

  @Test
  void derivedRunCopiesPinsAndParentReferences() {
    RunManifest parentManifest = sampleManifest("parent-run");
    runtime
        .startOrResume(
            new StartOrResumeCommand("conv-parent", "parent-run", profile, parentManifest))
        .collect()
        .asList()
        .await()
        .indefinitely();

    CompilationArtifacts.Reference source =
        artifactStore
            .latest("parent-run", CompilationArtifacts.Kind.RUN_MANIFEST)
            .orElseThrow()
            .reference();

    RunManifest childManifest =
        new RunManifest(
            "child-run",
            "parent-run",
            List.of(source),
            parentManifest.runtimeSelection(),
            parentManifest.profileId(),
            parentManifest.profileVersion(),
            parentManifest.profileDigest(),
            parentManifest.referenceBaselineId(),
            parentManifest.referenceBaselineDigest(),
            parentManifest.dependencyClosure(),
            parentManifest.dependencyClosureDigest(),
            parentManifest.knowledgePackage(),
            parentManifest.languageVersion(),
            parentManifest.artifactSchemaVersions(),
            parentManifest.compilerRunPin());
    runtime
        .startOrResume(
            new StartOrResumeCommand("conv-child", "child-run", profile, childManifest))
        .collect()
        .asList()
        .await()
        .indefinitely();

    RunManifest child =
        artifactStore
            .payload(
                artifactStore
                    .latest("child-run", CompilationArtifacts.Kind.RUN_MANIFEST)
                    .orElseThrow(),
                RunManifest.class);

    assertEquals("parent-run", child.parentRunId());
    assertEquals(List.of(source), child.sourceReferences());
    assertEquals(parentManifest.profileDigest(), child.profileDigest());
    assertEquals(parentManifest.knowledgePackage(), child.knowledgePackage());
    assertEquals(parentManifest.languageVersion(), child.languageVersion());
    assertEquals(parentManifest.dependencyClosureDigest(), child.dependencyClosureDigest());
    assertTrue(
        runtime
            .startOrResume(
                new StartOrResumeCommand("conv-parent", "parent-run", profile, parentManifest))
            .collect()
            .asList()
            .await()
            .indefinitely()
            .stream()
            .anyMatch(PipelineSignal.WaitingForInput.class::isInstance));
  }

  @Test
  void restartFromApprovedRequirementsClonesLocalApprovalAndInvalidatesDownstream()
      throws Exception {
    ProductPipelineProfile createProfile;
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      createProfile = ProductPipelineProfileParser.parse(in);
    }
    String parentRunId = "parent-checkpoint-run";
    String conversationId = "conv-checkpoint";
    RunManifest parentManifest = manifestFor(parentRunId, createProfile);
    CompilationArtifacts.Revision manifest =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.RUN_MANIFEST,
                "1",
                "test",
                "1",
                parentManifest,
                List.of(),
                null,
                provenance(parentRunId, "bootstrap")));
    CompilationArtifacts.Revision input =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.USER_INPUT,
                "1",
                "test",
                "1",
                new UserInput("input-1", "ids-entry", "Create a chain", FIXED),
                List.of(),
                null,
                provenance(parentRunId, "ids-entry")));
    CompilationArtifacts.Revision brief =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.REQUIREMENT_BRIEF,
                "1",
                "test",
                "1",
                java.util.Map.of("goal", "Create a chain"),
                List.of(input.reference()),
                null,
                provenance(parentRunId, "requirement-analysis")));
    CompilationArtifacts.Revision approval =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.APPROVAL_RECORD,
                "1",
                "test",
                "1",
                new ApprovalRecord(
                    brief.reference(), brief.contentHash(), "user", null, FIXED),
                List.of(brief.reference()),
                null,
                provenance(parentRunId, "requirement-analysis")));
    List<StageSnapshot> stages =
        createProfile.stages().stream()
            .map(
                stage -> {
                  if ("requirement-analysis".equals(stage.stageId())) {
                    return new StageSnapshot(
                        stage.stageId(),
                        StageStatus.SUCCEEDED,
                        List.of(brief.reference(), approval.reference()),
                        brief.artifactId(),
                        List.of(brief.reference()),
                        brief.reference(),
                        1);
                  }
                  if ("design-input".equals(stage.stageId())) {
                    return new StageSnapshot(
                        stage.stageId(), StageStatus.FAILED, List.of(), null);
                  }
                  StageStatus status =
                      createProfile.stages().indexOf(stage)
                              < createProfile.stages().stream()
                                  .map(s -> s.stageId())
                                  .toList()
                                  .indexOf("requirement-analysis")
                          ? StageStatus.SUCCEEDED
                          : StageStatus.PENDING;
                  return new StageSnapshot(stage.stageId(), status, List.of(), null);
                })
            .toList();
    runStore.create(
        new RunSnapshot(
            parentRunId,
            conversationId,
            7L,
            RunStatus.FAILED,
            "design-input",
            stages,
            manifest.reference(),
            "parent-flow"));

    assertTrue(
        runSupport
            .availableRestartCheckpoints(conversationId)
            .contains(RestartCheckpoint.APPROVED_REQUIREMENTS));
    PreparedRestart prepared =
        runSupport.prepareCheckpointRestart(
            new RestartRunCommand(
                conversationId,
                parentRunId,
                7L,
                "child-checkpoint-run",
                createProfile,
                RestartCheckpoint.APPROVED_REQUIREMENTS,
                "restart-command",
                "restart-payload"),
            "child-flow");

    assertEquals(parentRunId, prepared.runManifest().parentRunId());
    assertEquals("design-input", prepared.document().run().currentStageId());
    assertEquals(
        StageStatus.RUNNING,
        prepared.document().run().stages().stream()
            .filter(stage -> "design-input".equals(stage.stageId()))
            .findFirst()
            .orElseThrow()
            .status());
    assertEquals(
        StageStatus.PENDING,
        prepared.document().run().stages().stream()
            .filter(stage -> "design-planning".equals(stage.stageId()))
            .findFirst()
            .orElseThrow()
            .status());
    CompilationArtifacts.Revision childBrief =
        artifactStore
            .latest("child-checkpoint-run", CompilationArtifacts.Kind.REQUIREMENT_BRIEF)
            .orElseThrow();
    ApprovalRecord childApproval =
        artifactStore.payload(
            artifactStore
                .latest("child-checkpoint-run", CompilationArtifacts.Kind.APPROVAL_RECORD)
                .orElseThrow(),
            ApprovalRecord.class);
    assertNotEquals(brief.artifactId(), childBrief.artifactId());
    assertEquals(childBrief.reference(), childApproval.target());
    assertEquals(
        parentRunId,
        runStore.loadByConversation(conversationId).orElseThrow().run().runId());
    assertEquals(RunStatus.FAILED, runStore.load(parentRunId).orElseThrow().run().status());
  }

  @Test
  void restartFromApprovedPlanClonesTheV2CandidateSetIntoTheChildRun() throws Exception {
    ProductPipelineProfile createProfile;
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      createProfile = ProductPipelineProfileParser.parse(in);
    }
    String parentRunId = "parent-plan-run";
    String conversationId = "conv-plan-checkpoint";
    RunManifest parentManifest = manifestFor(parentRunId, createProfile);
    CompilationArtifacts.Revision manifest =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.RUN_MANIFEST,
                "1",
                "test",
                "1",
                parentManifest,
                List.of(),
                null,
                provenance(parentRunId, "bootstrap")));
    CompilationArtifacts.Revision plan =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.IMPLEMENTATION_PLAN,
                "2",
                "test",
                "1",
                java.util.Map.of("title", "Approved plan"),
                List.of(),
                null,
                provenance(parentRunId, "design-planning")));
    CompilationArtifacts.Revision graph =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.CHAIN_PLAN_GRAPH,
                "1",
                "test",
                "1",
                java.util.Map.of("nodes", List.of()),
                List.of(),
                null,
                provenance(parentRunId, "design-planning")));
    CompilationArtifacts.Revision approval =
        artifactStore.append(
            new AppendCommand(
                parentRunId,
                CompilationArtifacts.Kind.APPROVAL_RECORD,
                "2",
                "test",
                "1",
                new ApprovalRecordV2(
                    plan.reference(),
                    plan.contentHash(),
                    List.of(plan.reference(), graph.reference()),
                    "user",
                    null,
                    FIXED,
                    null,
                    null,
                    "implementation-plan",
                    "2",
                    "plan-revision-1",
                    plan.contentHash(),
                    "compiler-v1",
                    "compiler-sha"),
                List.of(plan.reference(), graph.reference()),
                null,
                provenance(parentRunId, "design-planning")));
    List<StageSnapshot> stages =
        createProfile.stages().stream()
            .map(
                stage -> {
                  if ("design-planning".equals(stage.stageId())) {
                    return new StageSnapshot(
                        stage.stageId(),
                        StageStatus.SUCCEEDED,
                        List.of(plan.reference(), graph.reference(), approval.reference()),
                        plan.artifactId(),
                        List.of(plan.reference(), graph.reference()),
                        plan.reference(),
                        1);
                  }
                  if ("design-execution".equals(stage.stageId())) {
                    return new StageSnapshot(
                        stage.stageId(), StageStatus.FAILED, List.of(), null);
                  }
                  StageStatus status =
                      createProfile.stages().indexOf(stage)
                              < createProfile.stages().stream()
                                  .map(s -> s.stageId())
                                  .toList()
                                  .indexOf("design-planning")
                          ? StageStatus.SUCCEEDED
                          : StageStatus.PENDING;
                  return new StageSnapshot(stage.stageId(), status, List.of(), null);
                })
            .toList();
    runStore.create(
        new RunSnapshot(
            parentRunId,
            conversationId,
            9L,
            RunStatus.FAILED,
            "design-execution",
            stages,
            manifest.reference(),
            "parent-flow"));

    PreparedRestart prepared =
        runSupport.prepareCheckpointRestart(
            new RestartRunCommand(
                conversationId,
                parentRunId,
                9L,
                "child-plan-run",
                createProfile,
                RestartCheckpoint.APPROVED_PLAN,
                "restart-plan-command",
                "restart-plan-payload"),
            "child-flow");

    ApprovalRecordV2 childApproval =
        artifactStore.payload(
            artifactStore
                .latest("child-plan-run", CompilationArtifacts.Kind.APPROVAL_RECORD)
                .orElseThrow(),
            ApprovalRecordV2.class);
    assertEquals("design-execution", prepared.document().run().currentStageId());
    assertNotEquals(plan.reference(), childApproval.target());
    assertEquals(childApproval.target().contentHash(), childApproval.targetContentHash());
    assertEquals(2, childApproval.approvedCandidates().size());
    assertTrue(
        childApproval.approvedCandidates().stream()
            .allMatch(
                reference ->
                    artifactStore.get("child-plan-run", reference).isPresent()));
  }

  private RunManifest sampleManifest(String runId) {
    return new RunManifest(
        runId,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha-fixed",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("fake-collector", "1", "c1")),
        "closure-sha-fixed",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private RunManifest manifestFor(String runId, ProductPipelineProfile selectedProfile) {
    RunManifest sample = sampleManifest(runId);
    return new RunManifest(
        runId,
        null,
        List.of(),
        sample.runtimeSelection(),
        selectedProfile.profileId(),
        selectedProfile.profileVersion(),
        selectedProfile.profileId() + "@" + selectedProfile.profileVersion(),
        sample.referenceBaselineId(),
        sample.referenceBaselineDigest(),
        sample.dependencyClosure(),
        sample.dependencyClosureDigest(),
        sample.knowledgePackage(),
        sample.languageVersion(),
        sample.artifactSchemaVersions(),
        sample.compilerRunPin());
  }

  private ArtifactProvenance provenance(String runId, String stageId) {
    return new ArtifactProvenance(
        runId, stageId, "create-chain", "2", "create-chain@2", "test", "1", "closure");
  }
}

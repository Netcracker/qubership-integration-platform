package org.qubership.integration.platform.ai.productpipeline.runtime;

import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;

import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;

import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainMaterializedSummary;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.SpecificationImportCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class ProductPipelineImplementationGateTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T12:00:00Z");
  private static final String RUN_ID = "run-implement-gate-1";
  private static final String CONVERSATION_ID = "conv-implement-gate-1";

  private static final ChainCatalogFacts MATERIALIZED_FACTS =
      new ChainCatalogFacts(
          "catalog-chain-1",
          "DemoChain",
          "",
          2,
          0,
          "",
          List.of(),
          List.of(),
          "built_in_catalog");

  private InMemoryArtifactBlobStore blobStore;
  private ObjectMapper mapper;
  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private StageCapability materializationCapability;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile createChainProfile;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    materializationCapability = mock(StageCapability.class);
    when(materializationCapability.capabilityId()).thenReturn("materialization");
    when(materializationCapability.execute(any()))
        .thenReturn(
            Multi.createFrom()
                .items(
                    new CapabilitySignal.Message(ChainMaterializedSummary.format(MATERIALIZED_FACTS)),
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.SUCCEEDED,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.MATERIALIZATION_RESULT, Map.of("ok", true), List.of()),
                                new ArtifactCandidate(
                                    Kind.CATALOG_CHAIN_SNAPSHOT, MATERIALIZED_FACTS, List.of()),
                                new ArtifactCandidate(
                                    Kind.RECONCILE_RESULT, Map.of("reconcile", "ok"), List.of())),
                            "materialized",
                            null))));
    createChainProfile = loadCreateChainProfileOrFallback();
    runtime =
        new CreateChainTestOrchestrator(
            CreateChainTestOrchestrator.supportBuilder(
                    runStore,
                    artifactStore,
                    new StageCapabilityRegistry(
                        List.of(
                            discovery(),
                            importStage(),
                            analysis(),
                            planning(),
                            materializationCapability)),
                    clock)
                .profileCatalog(new ProductPipelineProfileCatalog(List.of(createChainProfile)))
                .build(),
            runStore);
  }

  @Test
  void planApprovalWaitsForSeparateImplementCommand() {
    WaitingForApproval waiting = runCreateChainToPlanCandidate();
    runtime.approve(new ApproveCommand(RUN_ID, waiting.candidate(), loadRun().run().runRevision())).collect().asList().await().indefinitely();

    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    verify(materializationCapability, never()).execute(any());
    // This suite isolates approval gates. Shell product E2E pins @2 for new
    // CREATE; v2 cutover evidence lives in CreateChainSharedDesignRuntimeIT.
    assertEquals("2", createChainProfile.profileVersion());
  }

  @Test
  void staleImplementHashDoesNotAdvanceRun() {
    runCreateChainToWaitingForImplement();
    long revision = loadRun().run().runRevision();
    assertThrows(
        StaleApprovalException.class,
        () ->
            runtime
                .implement(new ImplementCommand(RUN_ID, "wrong-sha", revision))
                .collect()
                .asList()
                .await()
                .indefinitely());
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    assertEquals(revision, loadRun().run().runRevision());
    verify(materializationCapability, never()).execute(any());
  }

  @Test
  void negativeExpectedRevisionIsRejectedAsStale() {
    String planHash = runCreateChainToWaitingForImplement();

    assertThrows(
        StaleApprovalException.class,
        () ->
            runtime
                .implement(new ImplementCommand(RUN_ID, planHash, -1))
                .collect()
                .asList()
                .await()
                .indefinitely());

    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    verify(materializationCapability, never()).execute(any());
  }

  @Test
  void matchingImplementHashAdvancesToMaterialization() {
    String planHash = runCreateChainToWaitingForImplement();
    long revision = loadRun().run().runRevision();

    List<PipelineSignal> signals =
        runtime
            .implement(new ImplementCommand(RUN_ID, planHash, revision))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof PipelineSignal.Completed c
                        && c.status() == RunStatus.CHAIN_MATERIALIZED));
    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status());
    verify(materializationCapability).execute(any());
  }

  @Test
  void flowRecordsImplementOnceBeforeExecutingMaterialization() {
    String planHash = runCreateChainToWaitingForImplement();
    long revision = loadRun().run().runRevision();
    ImplementCommand command =
        new ImplementCommand(RUN_ID, planHash, revision, "implement-1", "payload-sha");

    runtime.recordImplement(command).collect().asList().await().indefinitely();

    assertEquals(RunStatus.RUNNING, loadRun().run().status());
    assertEquals("materialization", loadRun().run().currentStageId());
    verify(materializationCapability, never()).execute(any());
    long revisionAfterGate = loadRun().run().runRevision();

    runtime.recordImplement(command).collect().asList().await().indefinitely();

    assertEquals(revisionAfterGate, loadRun().run().runRevision());
    verify(materializationCapability, never()).execute(any());

    runtime.executeStage(RUN_ID, "materialization").collect().asList().await().indefinitely();

    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status());
    verify(materializationCapability).execute(any());

    runtime.recordImplement(command).collect().asList().await().indefinitely();

    assertEquals(RunStatus.CHAIN_MATERIALIZED, loadRun().run().status());
    verify(materializationCapability).execute(any());
  }

  /**
   * No wording materializes a chain.
   *
   * <p>Writing a chain into the catalog is the one irreversible step, and nothing removes it again.
   * It is reachable only through a command that names the approved plan, never through text.
   */
  @Test
  void noTextAtTheImplementGateMaterializesAChain() {
    String planHash = runCreateChainToWaitingForImplement();
    long revision = loadRun().run().runRevision();
    CreateProductPipelineCoordinator coordinator = liveCoordinator();

    for (String text :
        List.of("", "Agree", "yes", "Implement", "Implement " + planHash, "Implement not-a-hash")) {
      coordinator
          .handle(implementChat(text), CONVERSATION_ID)
          .collect()
          .asList()
          .await()
          .indefinitely();
      assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status(), text);
      assertEquals(revision, loadRun().run().runRevision(), text);
    }

    verify(materializationCapability, never()).execute(any());
  }
  private WaitingForApproval runCreateChainToPlanCandidate() {
    runtime
        .startOrResume(
            new StartOrResumeCommand(
                CONVERSATION_ID, RUN_ID, createChainProfile, sampleManifest(createChainProfile)))
        .collect()
        .asList()
        .await()
        .indefinitely();
    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    Reference draftCandidate =
        afterInput.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow()
            .candidate();
    List<PipelineSignal> afterDraft =
        runtime
            .approve(new ApproveCommand(RUN_ID, draftCandidate, runStore.load(RUN_ID).orElseThrow().run().runRevision()))
            .collect()
            .asList()
            .await()
            .indefinitely();
    Reference planCandidate =
        afterDraft.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow()
            .candidate();
    return new WaitingForApproval(planCandidate);
  }

  private String runCreateChainToWaitingForImplement() {
    WaitingForApproval waiting = runCreateChainToPlanCandidate();
    runtime
        .approve(new ApproveCommand(RUN_ID, waiting.candidate(), loadRun().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_IMPLEMENT, loadRun().run().status());
    ApprovalRecordV2 approval =
        artifactStore.payload(
            artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
                .filter(item -> "2".equals(item.schemaVersion()))
                .reduce((a, b) -> b)
                .orElseThrow(),
            ApprovalRecordV2.class);
    return approval.targetContentHash();
  }

  private ProductPipelineRunDocument loadRun() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private CreateProductPipelineCoordinator liveCoordinator() {
    CreateRunBindingStore bindingStore = new CreateRunBindingStore(blobStore, mapper);
    ProductPipelineProfileCatalog catalogForSelection =
        new ProductPipelineProfileCatalog(List.of(createChainProfile));
    CreateRunSelectionService selection =
        new CreateRunSelectionService(
            "2026.1",
            FakeKnowledgeClient.defaultFixture(),
            bindingStore,
            catalogForSelection,
            stubPinResolver(),
            java.time.Clock.systemUTC());
    selection.selectOrCreate(CONVERSATION_ID);
    CreateChainApplicationFacade facade =
        new CreateChainApplicationFacade(selection, bindingStore, runtime, runStore, catalogForSelection);
    return new CreateProductPipelineCoordinator(facade, runStore);
  }

  private static ChatRequest implementChat(String text) {
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText(text);
    return request;
  }

  private ProductPipelineProfile loadCreateChainProfileOrFallback() {
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/approval-gates.yaml")) {
      return ProductPipelineProfileParser.parse(in);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private RunManifest sampleManifest(ProductPipelineProfile profile) {
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
        List.of(new DependencyClosureEntry("planning", "1", "c1")),
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
        null);
  }

  private static StageCapability discovery() {
    AtomicInteger calls = new AtomicInteger();
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementDiscoveryCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (calls.incrementAndGet() == 1 && context.attributeAsString("userText") == null) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need input")));
        }
        RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "draft ready",
                        null)));
      }
    };
  }

  private static StageCapability importStage() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return SpecificationImportCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        Object approved = context.attributes().get("approvedDraft");
        RequirementDraft draft =
            approved instanceof RequirementDraft requirementDraft
                ? requirementDraft
                : RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "import skipped",
                        null)));
      }
    };
  }

  private static StageCapability analysis() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementAnalysisCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        RequirementBrief brief =
            new RequirementBrief("brief", List.of("fact"), List.of(), List.of(), List.of(), "ok");
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())),
                        "analyzed",
                        null)));
      }
    };
  }

  private static StageCapability planning() {
    return new ScriptedCapability(
        "test-planning",
        new StageOutcome(
            StageOutcomeClass.CANDIDATE,
            List.of(
                new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "ok"), List.of()),
                new ArtifactCandidate(
                    Kind.PLAN_VALIDATION_RESULT, Map.of("verdict", "PASS"), List.of()),
                new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "ok"), List.of()),
                new ArtifactCandidate(
                    Kind.GRAPH_ASSEMBLY_RESULT, Map.of("assembly", "ok"), List.of()),
                new ArtifactCandidate(
                    Kind.COMPILER_VALIDATION_BUNDLE, Map.of("bundle", "ok"), List.of())),
            "plan ready",
            null));
  }

  private static final class ScriptedCapability implements StageCapability {
    private final String capabilityId;
    private final Queue<StageOutcome> outcomes;

    private ScriptedCapability(String capabilityId, StageOutcome... outcomes) {
      this.capabilityId = capabilityId;
      this.outcomes = new ArrayDeque<>(List.of(outcomes));
    }

    @Override
    public String capabilityId() {
      return capabilityId;
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      StageOutcome outcome =
          outcomes.isEmpty()
              ? StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "no scripted outcome")
              : outcomes.remove();
      return Multi.createFrom().item(new CapabilitySignal.Completed(outcome));
    }
  }

  private record WaitingForApproval(Reference candidate) {}

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "pkg",
            "1",
            "digest",
            1,
            "idx-1",
            "idx-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of("planning"),
            Map.of(),
            Map.of("skill", "a".repeat(64)),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    CompilerRunPinResolver resolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.when(
            resolver.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(pin);
    return resolver;
  }
}

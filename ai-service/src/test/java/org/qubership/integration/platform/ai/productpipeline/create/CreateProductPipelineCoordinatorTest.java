package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.llm.agent.GateReplyAgent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

public class CreateProductPipelineCoordinatorTest {

  private Fixture fixture;

  /** Stands in for a model that reads the reply as accepting the candidate it was shown. */
  private static GateReplyAgent approvingAgent() {
    return (memoryId, artifactType, artifactHash, revision, reply) -> {
      new ApproveCandidateTool().approveCandidate(artifactType, artifactHash, revision);
      return "Approved.";
    };
  }

  /** Stands in for a model that answers without approving anything. */
  private static GateReplyAgent silentAgent() {
    return (memoryId, artifactType, artifactHash, revision, reply) ->
        "The reader wants a change.";
  }

  @BeforeEach
  void setUp() throws Exception {
    fixture = Fixture.create();
  }

  /** Shared durable fixture for restart IT coverage. */
  public static final class FixtureHelper {
    private final Fixture delegate;

    private FixtureHelper(Fixture delegate) {
      this.delegate = delegate;
    }

    public static FixtureHelper create() throws Exception {
      return new FixtureHelper(Fixture.create());
    }

    public CreateProductPipelineCoordinator coordinator() {
      return delegate.coordinator();
    }

    public CreateProductPipelineCoordinator coordinatorWith(GateReplyAgent agent) {
      return delegate.coordinatorWith(agent);
    }

    public AtomicInteger materializationCalls() {
      return delegate.materializationCalls();
    }

    public CreateProductPipelineCoordinator restartCoordinator() {
      return delegate.restartCoordinator();
    }

    public CreateRunSelectionService selectionService() {
      return delegate.selectionService();
    }

    public ProductPipelineArtifactStore artifactStore() {
      return delegate.artifactStore();
    }

    public ObjectMapper objectMapper() {
      return delegate.objectMapper();
    }
  }

  @Test
  void adapterSourceHasNoLocalLifecycleMap() throws Exception {
    String source =
        Files.readString(
            java.nio.file.Path.of(
                "src/main/java/org/qubership/integration/platform/ai/productpipeline/create/ProductPipelineChatAdapter.java"),
            StandardCharsets.UTF_8);
    assertFalse(source.contains("ConcurrentHashMap"));
    assertFalse(source.contains("enum RunState"));
  }

  /**
   * The stage advances on an approval the literal check cannot read.
   *
   * <p>Both shapes reach this service in practice: the approval question is authored in the
   * language of the conversation, so the reply arrives in that language, and an agent relaying a
   * person's approval writes a sentence rather than the bare token. Read literally, both are fed
   * back into the stage as fresh input, which re-runs it instead of approving it.
   */
  @Test
  void approvalInAnotherLanguageAdvancesTheStage() {
    CreateProductPipelineCoordinator coordinator = fixture.coordinatorWith(approvingAgent());
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(request, "conv-lang").collect().asList().await().indefinitely();
    assertEquals(
        RunStatus.WAITING_FOR_APPROVAL, coordinator.loadRun("conv-lang").orElseThrow().run().status());

    ChatRequest approval = new ChatRequest();
    approval.setResolvedEffectiveUserText("De acuerdo, puede continuar");
    coordinator.handle(approval, "conv-lang").collect().asList().await().indefinitely();

    assertNotEquals(
        "requirement-discovery",
        coordinator.loadRun("conv-lang").orElseThrow().run().currentStageId());
  }

  /** A reply the model does not act on leaves the stage where it was. */
  @Test
  void requestedChangesDoNotApprove() {
    CreateProductPipelineCoordinator coordinator = fixture.coordinatorWith(silentAgent());
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(request, "conv-changes").collect().asList().await().indefinitely();

    ChatRequest changes = new ChatRequest();
    changes.setResolvedEffectiveUserText("Agregue el encabezado request-id a la respuesta");
    coordinator.handle(changes, "conv-changes").collect().asList().await().indefinitely();

    assertEquals(
        "requirement-discovery",
        coordinator.loadRun("conv-changes").orElseThrow().run().currentStageId());
  }

  /** A model failure must not advance a stage nobody approved. */
  @Test
  void gateAgentFailureIsNotAnApproval() {
    CreateProductPipelineCoordinator coordinator =
        fixture.coordinatorWith(
            (memoryId, artifactType, artifactHash, revision, reply) -> {
              throw new IllegalStateException("model unavailable");
            });
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(request, "conv-fail").collect().asList().await().indefinitely();

    ChatRequest approval = new ChatRequest();
    approval.setResolvedEffectiveUserText("De acuerdo");
    coordinator.handle(approval, "conv-fail").collect().asList().await().indefinitely();

    assertEquals(
        "requirement-discovery",
        coordinator.loadRun("conv-fail").orElseThrow().run().currentStageId());
  }

  /**
   * A model-driven approval never reaches materialization on its own.
   *
   * <p>Materializing writes a chain into the catalog and nothing removes it again, so it is the
   * one step a model cannot reach: no tool offers it. An approval carries the run to the implement
   * gate and stops there.
   */
  @Test
  void modelApprovalStopsAtTheImplementGate() {
    CreateProductPipelineCoordinator coordinator = fixture.coordinatorWith(approvingAgent());
    ChatRequest start = new ChatRequest();
    start.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(start, "conv-gate").collect().asList().await().indefinitely();

    // Drive every approval gate with prose no pattern match could read as an approval.
    for (int turn = 0; turn < 8; turn++) {
      RunStatus status = coordinator.loadRun("conv-gate").orElseThrow().run().status();
      if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
        break;
      }
      ChatRequest reply = new ChatRequest();
      reply.setResolvedEffectiveUserText("Se ve bien, continue");
      coordinator.handle(reply, "conv-gate").collect().asList().await().indefinitely();
    }

    assertEquals(
        RunStatus.WAITING_FOR_IMPLEMENT,
        coordinator.loadRun("conv-gate").orElseThrow().run().status(),
        "the run should have reached the implement gate");
    assertEquals(
        0, fixture.materializationCalls().get(), "a model approval must not materialize");
  }

  @Test
  void implementIntentAtTheImplementationGateStartsMaterialization() {
    CreateProductPipelineCoordinator coordinator = fixture.coordinatorWith(approvingAgent());
    ChatRequest start = new ChatRequest();
    start.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(start, "conv-implement").collect().asList().await().indefinitely();

    for (int turn = 0; turn < 8; turn++) {
      RunStatus status = coordinator.loadRun("conv-implement").orElseThrow().run().status();
      if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
        break;
      }
      ChatRequest reply = new ChatRequest();
      reply.setResolvedEffectiveUserText("Looks good, continue");
      coordinator.handle(reply, "conv-implement").collect().asList().await().indefinitely();
    }

    ChatRequest implement = new ChatRequest();
    implement.setResolvedEffectiveUserText("Implement it");
    implement.setScenarioHint(org.qubership.integration.platform.ai.model.ScenarioType.IMPLEMENT_CHAIN);
    coordinator.handle(implement, "conv-implement").collect().asList().await().indefinitely();

    assertEquals(1, fixture.materializationCalls().get());
  }

  @Test
  void restartsAfterRequirementApprovalWithoutAdapterState() {
    CreateProductPipelineCoordinator coordinator = fixture.coordinator();
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("create greetings API");
    coordinator.handle(request, "conv-1").collect().asList().await().indefinitely();

    ProductPipelineRunDocument afterDiscovery =
        coordinator.loadRun("conv-1").orElseThrow();
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, afterDiscovery.run().status());
    assertEquals("requirement-discovery", afterDiscovery.run().currentStageId());

    coordinator.approveCurrent("conv-1").collect().asList().await().indefinitely();

    CreateProductPipelineCoordinator restarted = fixture.restartCoordinator();
    ProductPipelineRunDocument resumed = restarted.loadRun("conv-1").orElseThrow();
    assertNotEquals("requirement-discovery", resumed.run().currentStageId());
    assertTrue(resumed.transitions().size() >= 2);
  }

  private static final class Fixture {
    private final InMemoryArtifactBlobStore blobs;
    private final ObjectMapper mapper;
    private final FakeKnowledgeClient knowledge;
    private final ProductPipelineProfile profile;
    private final ProductPipelineProfileCatalog catalog;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    private final AtomicInteger materializationCalls = new AtomicInteger();

    AtomicInteger materializationCalls() {
      return materializationCalls;
    }
    private CreateRunSelectionService selectionService;
    private ProductPipelineArtifactStore artifactStore;

    private Fixture(
        InMemoryArtifactBlobStore blobs,
        ObjectMapper mapper,
        FakeKnowledgeClient knowledge,
        ProductPipelineProfile profile,
        ProductPipelineProfileCatalog catalog) {
      this.blobs = blobs;
      this.mapper = mapper;
      this.knowledge = knowledge;
      this.profile = profile;
      this.catalog = catalog;
    }

    static Fixture create() throws Exception {
      ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
      ProductPipelineProfile profileV1;
      ProductPipelineProfile profileV2;
      try (InputStream in =
          Fixture.class.getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
        profileV1 = ProductPipelineProfileParser.parse(in);
      }
      try (InputStream in =
          Fixture.class.getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
        profileV2 = ProductPipelineProfileParser.parse(in);
      }
      return new Fixture(
          new InMemoryArtifactBlobStore(),
          mapper,
          FakeKnowledgeClient.defaultFixture(),
          profileV1,
          new ProductPipelineProfileCatalog(List.of(profileV1, profileV2)));
    }

    CreateProductPipelineCoordinator coordinator() {
      return buildCoordinator(blobs);
    }

    CreateProductPipelineCoordinator coordinatorWith(GateReplyAgent agent) {
      return buildCoordinator(blobs, agent);
    }

    CreateProductPipelineCoordinator restartCoordinator() {
      return buildCoordinator(blobs);
    }

    CreateRunSelectionService selectionService() {
      if (selectionService == null) {
        buildCoordinator(blobs);
      }
      return selectionService;
    }

    ProductPipelineArtifactStore artifactStore() {
      if (artifactStore == null) {
        buildCoordinator(blobs);
      }
      return artifactStore;
    }

    ObjectMapper objectMapper() {
      return mapper;
    }

    private CreateProductPipelineCoordinator buildCoordinator(InMemoryArtifactBlobStore store) {
      return buildCoordinator(store, null);
    }

    private CreateProductPipelineCoordinator buildCoordinator(
        InMemoryArtifactBlobStore store, GateReplyAgent gateReplyAgent) {
      CreateRunBindingStore bindingStore = new CreateRunBindingStore(store, mapper);
      CreateRunSelectionService selection =
          new CreateRunSelectionService(
              "2026.1",
              knowledge,
              bindingStore,
              catalog,
              stubPinResolver(),
              clock,
              "1");
      CompilationArtifacts artifacts = new CompilationArtifacts(store, mapper, clock);
      ProductPipelineArtifactStore storeFacade = new ProductPipelineArtifactStore(artifacts);
      this.selectionService = selection;
      this.artifactStore = storeFacade;
      ProductPipelineRunStore runStore = new ProductPipelineRunStore(store, mapper, clock);
      StageCapabilityRegistry capabilities =
          new StageCapabilityRegistry(
              List.of(discovery(), importStage(), analysis(), planning(),
                  materialization(materializationCalls)));
      ProductPipelineRuntime runtime =
          new ProductPipelineRuntime(
              runStore, storeFacade, capabilities, catalog, stubPinResolver(), clock);
      CreateProductPipelineCoordinator coordinator =
          new CreateProductPipelineCoordinator(
              selection, bindingStore, runtime, runStore, catalog, new ApprovalPrompts(),
              gateReplyAgent);
      // Same collaborators CDI injects in production: an approval is validated against the open
      // gate, and the authored question outlives the wait that carried it.
      coordinator.facade =
          new CreateChainApplicationFacade(selection, bindingStore, runtime, runStore, catalog);
      coordinator.approvalQuestions = new ApprovalQuestionStore(store);
      return coordinator;
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
          // Must be a real RequirementDraft: runtime hydrates approvedDraft via Jackson convert.
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

    private StageCapability planning() {
      org.qubership.integration.platform.ai.plan.model.ChainPlanGraph graph =
          new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
              "1.0",
              new org.qubership.integration.platform.ai.plan.model.ChainSection("g", "G"),
              List.of(
                  new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                      "http-trigger", "http-trigger-2", "HTTP", null, null, List.of())),
              List.of());
      String graphDigest =
          new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(mapper)
              .sha256(graph);
      org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult assembly =
          new org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult(
              1, graph, graphDigest, List.of(), List.of(), List.of());
      org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle bundle =
          new org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle(
              1,
              graphDigest,
              List.of(
                  new org.qubership.integration.platform.ai.productpipeline.artifact
                      .CompilerValidationPass(
                      "validator",
                      new org.qubership.integration.platform.ai.qipknowledge.validation
                          .ValidationResult(true, List.of(), "ok"))));
      org.qubership.integration.platform.ai.plan.ImplementationPlan plan =
          org.qubership.integration.platform.ai.plan.ImplementationPlan.schemaVersion2(
              "Plan",
              "planning",
              "1",
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of());
      org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult validation =
          new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult(
              List.of());
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return PlanningCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(
                              new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, plan, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT, validation, context.inputRefs()),
                              new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, graph, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.GRAPH_ASSEMBLY_RESULT, assembly, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.COMPILER_VALIDATION_BUNDLE, bundle, context.inputRefs())),
                          "plan ready",
                          null)));
        }
      };
    }

    private static StageCapability materialization(AtomicInteger calls) {
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return org.qubership.integration.platform.ai.productpipeline.materialization
              .MaterializationCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          calls.incrementAndGet();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.NEEDS_INPUT,
                          "materialization stub waits for implement gate")));
        }
      };
    }
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "pkg",
            "1",
            "digest",
            1,
            "idx-1",
            "idx-digest",
            new org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag(
                java.util.List.of(), java.util.List.of(), "dag"),
            java.util.List.of("planning"),
            java.util.Map.of(),
            java.util.Map.of("skill", "a".repeat(64)),
            java.util.List.of());
    CompilerRunPinResolver resolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(pin);
    return resolver;
  }

}

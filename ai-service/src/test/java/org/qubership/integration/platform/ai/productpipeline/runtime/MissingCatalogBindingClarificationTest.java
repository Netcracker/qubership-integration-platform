package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.BindingResolutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultExecutorCatalogBindingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.CommandPayloadConflictException;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;

/**
 * Missing catalog-binding clarification is applied at the binding producer, then execution resumes
 * the same run. Fake LLM and catalog; real adapter, answer routing, and ledger.
 */
class MissingCatalogBindingClarificationTest {

  private static final Instant FIXED = Instant.parse("2026-09-10T12:00:00Z");
  private static final String RUN_ID = "run-missing-binding-1";
  private static final String CONV_ID = "conv-missing-binding-1";
  private static final String INTERACTION_ID = "wfms-create-work-order";
  private static final String SERVICE_NAME = "WFMS Create Work Order";
  private static final String WHAT_FAILED = "what failed?";
  private static final String FAILURE_ANSWER =
      "The catalog binding for the work-order call is missing.";
  private static final String BIND_COMMAND = "bind-1";
  private static final String BIND_HASH = "hash-bind-1";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private CatalogSystemReadTool catalog;
  private BindingAwareExecution execution;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    catalog = mock(CatalogSystemReadTool.class);
    stubCatalog();
    execution =
        new BindingAwareExecution(
            artifactStore,
            new DefaultExecutorCatalogBindingAdapter(catalog, mock(OperationSchemaLoader.class)));
    profile = twoStageProfile();
    RequirementDraftStore draftStore = new RequirementDraftStore();
    RequirementDiscoveryCapability discovery =
        new RequirementDiscoveryCapability(
            null,
            draftStore,
            (conversationId, userText) -> {
              RequirementDraft draft = readyDraftWithoutBindings();
              draftStore.beginTurn(conversationId);
              draftStore.put(conversationId, draft);
              draftStore.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(draft);
              return Multi.createFrom().empty();
            },
            new CatalogBindingMatcher(catalog));
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("", "requirement-discovery")
            .clarifying("Name the catalog service this interaction should use.")
            .answeringOnly(WHAT_FAILED, FAILURE_ANSWER);
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(discovery, execution)),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(new FailureNarrative(agent))
            .build();
    runtime = new CreateChainTestOrchestrator(support, runStore);
  }

  @Test
  void aServiceAnswerPersistsTheBindingAndResumesTheSameRun() {
    haltOnMissingBinding();

    List<PipelineSignal> signals =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, SERVICE_NAME, BIND_COMMAND, BIND_HASH))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.PLAN_APPROVED, run().run().status());
    assertTrue(
        signals.stream().anyMatch(PipelineSignal.Completed.class::isInstance),
        signals.toString());
    List<CatalogBindingHint> hints = persistedHints();
    assertEquals(1, hints.size());
    assertEquals(INTERACTION_ID, hints.getFirst().interactionId());
    assertEquals("sys-wfms", hints.getFirst().systemId());
    assertEquals("op-create-wo", hints.getFirst().integrationOperationId());
    assertEquals(2, execution.calls.get());
    assertTrue(execution.compilerInvoked.get());
    assertEquals(INTERACTION_ID, execution.lastBinding.serviceCallId());
    assertEquals("op-create-wo", execution.lastBinding.operationId());
  }

  @Test
  void askingWhatFailedLeavesTheWaitUnchanged() {
    haltOnMissingBinding();
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int callsBefore = execution.calls.get();
    int hintsBefore = persistedHints().size();

    List<PipelineSignal> signals =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, WHAT_FAILED))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(FAILURE_ANSWER, onlyMessage(signals));
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    assertEquals(
        PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertInstanceOf(
        SemanticRecoveryState.CompareResult.Unchanged.class,
        before.compareTo(runtime.captureSemanticRecoveryState(RUN_ID)));
    assertEquals(callsBefore, execution.calls.get());
    assertEquals(hintsBefore, persistedHints().size());
    assertEquals(false, execution.compilerInvoked.get());
  }

  @Test
  void aDuplicateBindingAnswerDoesNotBindAgain() {
    haltOnMissingBinding();
    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, SERVICE_NAME, BIND_COMMAND, BIND_HASH))
        .collect()
        .asList()
        .await()
        .indefinitely();
    int callsAfterBind = execution.calls.get();
    assertEquals(1, persistedHints().size());

    runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, SERVICE_NAME, BIND_COMMAND, BIND_HASH))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, persistedHints().size());
    assertEquals(callsAfterBind, execution.calls.get());
    assertEquals(RunStatus.PLAN_APPROVED, run().run().status());
    assertThrows(
        CommandPayloadConflictException.class,
        () ->
            runtime
                .acceptInput(
                    new AcceptInputCommand(RUN_ID, SERVICE_NAME, BIND_COMMAND, "other-hash"))
                .collect()
                .asList()
                .await()
                .indefinitely());
    assertEquals(1, persistedHints().size());
    assertEquals(callsAfterBind, execution.calls.get());
  }

  @Test
  void severalCatalogMatchesStayOnAnActionableCandidateWait() {
    stubCatalog(
        new CatalogRestClient.OperationDto(
            "op-create-wo", "WFMS Create Work Order", "POST", "/work-orders", "spec-wfms"),
        new CatalogRestClient.OperationDto(
            "op-create-wo-draft",
            "WFMS Create Work Order Draft",
            "POST",
            "/work-orders/draft",
            "spec-wfms"));
    haltOnMissingBinding();
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int callsBefore = execution.calls.get();

    List<PipelineSignal> signals =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, SERVICE_NAME, "bind-amb", "hash-amb"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(prompt).orElse(""));
    String visible = PipelineGates.strip(prompt);
    assertTrue(visible.contains("/work-orders"), visible);
    assertTrue(visible.contains("/work-orders/draft"), visible);
    assertEquals(0, persistedHints().size());
    assertEquals(callsBefore, execution.calls.get());
    assertEquals(false, execution.compilerInvoked.get());
    SemanticRecoveryState after = runtime.captureSemanticRecoveryState(RUN_ID);
    assertEquals(before.remaining(), after.remaining());
    assertEquals(before.gateId(), after.gateId());
    assertTrue(
        signals.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance),
        signals.toString());
    assertTrue(
        signals.stream().noneMatch(PipelineSignal.Completed.class::isInstance),
        signals.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "Not A Catalog Service"})
  void emptyOrUnknownAnswersExplainTheUnresolvedChoice(String answer) {
    haltOnMissingBinding();
    assertUnusableBindingAnswer(answer, "bind-unknown", "hash-unknown");
  }

  @Test
  void anIncompatibleAnswerExplainsTheUnresolvedChoice() {
    CatalogRestClient.SystemDto billing =
        new CatalogRestClient.SystemDto("sys-billing", "Billing HTTP", "EXTERNAL", "http");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec-billing", "Billing v1", "sg-billing", "sys-billing");
    CatalogRestClient.OperationDto invoice =
        new CatalogRestClient.OperationDto(
            "op-get-invoice", "getInvoice", "GET", "/invoices", "spec-billing");
    when(catalog.searchCatalogSystems("Billing HTTP")).thenReturn(List.of(billing));
    when(catalog.getApiSpecifications("sys-billing")).thenReturn(List.of(spec));
    when(catalog.listCatalogOperations(eq(CONV_ID), eq("spec-billing"), eq("sys-billing"), isNull()))
        .thenReturn(List.of(invoice));
    when(catalog.listCatalogOperations("spec-billing", "sys-billing", null))
        .thenReturn(List.of(invoice));
    haltOnMissingBinding();
    assertUnusableBindingAnswer("Billing HTTP", "bind-incompat", "hash-incompat");
  }

  private void assertUnusableBindingAnswer(String answer, String commandId, String hash) {
    SemanticRecoveryState before = runtime.captureSemanticRecoveryState(RUN_ID);
    int callsBefore = execution.calls.get();

    List<PipelineSignal> signals =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, answer, commandId, hash))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    assertEquals(
        PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    SemanticRecoveryState after = runtime.captureSemanticRecoveryState(RUN_ID);
    assertEquals(before.remaining(), after.remaining());
    assertEquals(before.gateId(), after.gateId());
    assertNotEquals(before.promptIdentity(), after.promptIdentity());
    assertEquals(0, persistedHints().size());
    assertEquals(callsBefore, execution.calls.get());
    assertEquals(false, execution.compilerInvoked.get());
    assertTrue(
        signals.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance),
        signals.toString());
    assertTrue(
        signals.stream().noneMatch(PipelineSignal.Completed.class::isInstance),
        signals.toString());
  }

  private void haltOnMissingBinding() {
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "create a WFMS work order chain"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    assertEquals(
        PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
    assertTrue(
        afterInput.stream().anyMatch(PipelineSignal.WaitingForInput.class::isInstance),
        afterInput.toString());
    assertEquals(1, execution.calls.get());
    assertEquals(false, execution.compilerInvoked.get());
    assertEquals(0, persistedHints().size());
  }

  private void stubCatalog(CatalogRestClient.OperationDto... operations) {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys-wfms", SERVICE_NAME, "EXTERNAL", "http");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec-wfms", "WFMS v1", "sg-wfms", "sys-wfms");
    List<CatalogRestClient.OperationDto> ops =
        operations.length == 0
            ? List.of(
                new CatalogRestClient.OperationDto(
                    "op-create-wo", "Create Work Order", "POST", "/work-orders", "spec-wfms"))
            : List.of(operations);
    when(catalog.searchCatalogSystems(SERVICE_NAME)).thenReturn(List.of(system));
    when(catalog.searchCatalogSystems("sys-wfms")).thenReturn(List.of(system));
    when(catalog.getApiSpecifications("sys-wfms")).thenReturn(List.of(spec));
    when(catalog.listCatalogOperations(eq(CONV_ID), eq("spec-wfms"), eq("sys-wfms"), isNull()))
        .thenReturn(ops);
    when(catalog.listCatalogOperations("spec-wfms", "sys-wfms", null)).thenReturn(ops);
  }

  private List<CatalogBindingHint> persistedHints() {
    return artifactStore.history(RUN_ID, Kind.CATALOG_BINDING_HINT).stream()
        .map(revision -> artifactStore.payload(revision, CatalogBindingHint.class))
        .toList();
  }

  private static RequirementDraft readyDraftWithoutBindings() {
    return new RequirementDraft(
        true,
        "Create a work order in WFMS.",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        false,
        List.of(
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                "http-trigger",
                "HTTP POST /start")),
        false,
        null,
        null,
        new RequirementFlow(
            List.of(
                new Interaction("http-in", Direction.INBOUND, "Caller", "POST /start", "")),
            List.of()));
  }

  private static String onlyMessage(List<PipelineSignal> signals) {
    return signals.stream()
        .filter(PipelineSignal.Message.class::isInstance)
        .map(PipelineSignal.Message.class::cast)
        .map(PipelineSignal.Message::text)
        .reduce(
            (first, second) -> {
              throw new AssertionError("expected one answer message, got more");
            })
        .orElseThrow(() -> new AssertionError("expected an answer message"));
  }

  private String latestWaitingPrompt() {
    return run().transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElseThrow();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private static ProductPipelineProfile twoStageProfile() {
    ArtifactTypeRef draft = new ArtifactTypeRef("requirement-draft", 1);
    ArtifactTypeRef hint = new ArtifactTypeRef("catalog-binding-hint", 1);
    return new ProductPipelineProfile(
        1,
        "test-missing-binding",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-discovery",
                "requirement-discovery",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                List.of(draft),
                List.of(hint),
                null,
                null,
                new RetryPolicy(0, 1L),
                null),
            new ProfileStage(
                "design-execution",
                "design-execution",
                List.of(),
                List.of(hint),
                List.of(),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L),
                null)),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("requirement-discovery"));
  }

  private RunManifest manifest() {
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
        List.of(
            new DependencyClosureEntry("requirement-discovery", "1", "c1"),
            new DependencyClosureEntry("design-execution", "1", "c2")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private static ApprovalRecordV2 catalogFirstApproval() {
    return new ApprovalRecordV2(
        new CompilationArtifacts.Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
        "hash-plan",
        List.of(),
        "tester",
        "approved",
        FIXED,
        ApprovalPolicy.CATALOG_FIRST_V1,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static final class BindingAwareExecution implements StageCapability {

    private final ProductPipelineArtifactStore artifactStore;
    private final DefaultExecutorCatalogBindingAdapter adapter;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean compilerInvoked = new AtomicBoolean();
    private ResolvedServiceCallBinding lastBinding;

    private BindingAwareExecution(
        ProductPipelineArtifactStore artifactStore,
        DefaultExecutorCatalogBindingAdapter adapter) {
      this.artifactStore = artifactStore;
      this.adapter = adapter;
    }

    @Override
    public String capabilityId() {
      return "design-execution";
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      calls.incrementAndGet();
      List<CatalogBindingHint> hints = new ArrayList<>();
      for (CompilationArtifacts.Revision revision :
          artifactStore.history(context.runId(), Kind.CATALOG_BINDING_HINT)) {
        hints.add(artifactStore.payload(revision, CatalogBindingHint.class));
      }
      List<BindingResolutionResult> results =
          adapter.resolve(
              context.conversationId(),
              SemanticFixtures.linear(
                  "WFMS",
                  "revision-wfms",
                  "trigger-http",
                  "node-call",
                  INTERACTION_ID,
                  "Create Work Order",
                  SERVICE_NAME,
                  List.of(),
                  List.of()),
              hints,
              catalogFirstApproval());
      for (BindingResolutionResult result : results) {
        if (result instanceof BindingResolutionResult.Failed failed && failed.isMissingHint()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.DOMAIN_FAILURE,
                          failed.reason(),
                          RecoveryCause.missingCatalogBinding(failed.serviceCallId()))));
        }
        if (result instanceof BindingResolutionResult.Failed failed) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          failed.outcomeClass(),
                          failed.reason(),
                          RecoveryCause.catalogResolution(failed.requestedFact()))));
        }
        if (result instanceof BindingResolutionResult.Resolved resolved) {
          lastBinding = resolved.binding();
          compilerInvoked.set(true);
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.SUCCEEDED, "compiled with resolved binding")));
        }
      }
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "no binding result")));
    }
  }
}

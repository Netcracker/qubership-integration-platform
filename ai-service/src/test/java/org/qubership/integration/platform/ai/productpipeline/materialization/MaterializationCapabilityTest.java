package org.qubership.integration.platform.ai.productpipeline.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.reconcile.ChainReconcileService;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.mapping.DirectFieldMappingScript;
import org.qubership.integration.platform.ai.plan.mapping.MappingGenerationPipeline;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionPhase;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ExecutorValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.OrderedGraphPatches;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

@ExtendWith(MockitoExtension.class)
class MaterializationCapabilityTest {

  private static final Instant FIXED = Instant.parse("2026-07-24T00:00:00Z");
  private static final String RUN_ID = "run-materialization-capability-1";
  private static final String CONVERSATION_ID = "conv-1";

  @Mock private ProductChainMaterializer materializer;
  @Mock private ChainCatalogFactsService factsService;
  @Mock private ChainReconcileService reconcileService;

  private ProductPipelineArtifactStore artifactStore;
  private CompilationArtifacts compilationArtifacts;
  private MaterializationCapability capability;
  private CipDesignExecutorJavaAdapter designExecutor;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(), mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    compilationArtifacts = artifacts;
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    designExecutor =
        new CipDesignExecutorJavaAdapter(
            mock(org.qubership.integration.platform.ai.productpipeline.create.design.execution.ApprovedCompilerExecutionRunner.class),
            mock(org.qubership.integration.platform.ai.productpipeline.create.design.execution.ExecutorCatalogBindingAdapter.class),
            artifactStore,
            mock(org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator.class));
    capability =
        new MaterializationCapability(
            artifactStore, materializer, factsService, reconcileService, designExecutor);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
  }

  @Test
  void filledLegacyMappingIsRejectedBeforeCatalogWrites() throws Exception {
    var fixture = mappingFixture(false, false, false);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), fixture.graph());
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
    assertTrue(artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT).isEmpty(),
        () -> "Materialization result: " + artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT));
  }

  @Test
  void verifiedFrozenCopyReachesTheCatalogWriteBoundary(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var fixture = mappingFixture(true, false, false);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    capability.copySchemas = fixture.schemas();
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), fixture.graph());
    parkBeforeWrites(directory);
    var subscription = capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))
        .subscribe().with(signal -> org.junit.jupiter.api.Assertions.fail("Verified input should park before catalog writes: " + signal));
    try {
      assertTrue(java.nio.file.Files.exists(directory.resolve("checkpoint.json")),
          () -> "Expected checkpoint at " + directory.resolve("checkpoint.json"));
      verify(materializer, never()).resume(any(), any());
    } finally {
      subscription.cancel();
    }
  }

  @Test
  void shouldGenerateAndValidateCopyFromTheApprovedCustomRequestSchema(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var fixture = mappingFixture(true, false, false, true);
    capability.copySchemas = fixture.schemas();
    var skill = MappingGenerationPipeline.SCRIPT_GENERATOR;
    var shell = fixture.graph().withNodeProperty("script-1", "script", "");
    var context = new GraphPatchExecutionContext(CONVERSATION_ID, skill, "requirements", "graph", "compiler", "24.4",
        fixture.brief(), List.of(), shell, GraphPatchOwnershipPolicy.denyAll(), "");
    var promptRenderer = mock(CompilerSkillContextBuilder.class);
    when(promptRenderer.renderBehaviorScriptGenerationContext(any(), any(), any(), any())).thenReturn("");
    var generation = new MappingGenerationPipeline(compilationArtifacts, new ObjectMapper(), promptRenderer);

    var generated = generation.prepare(CONVERSATION_ID, skill, fixture.semantic(), List.of(), context);

    assertFalse(generated.blocked(), generated.blockedMessage());
    assertEquals(1, generated.envelopeRefs().size(), generated.envelopeRefs().toString());
    var envelope = compilationArtifacts.payload(
        compilationArtifacts.get(CONVERSATION_ID, generated.envelopeRefs().getFirst()).orElseThrow(), MappingEnvelope.class);
    var mapping = fixture.brief().mappingIntents().getFirst();
    assertEquals("b4e9050f8405c335ff1c5661fd13587d67811bb7fd15c6d8c3434c94bca7193b",
        envelope.verifiedCopyPlan().reads().getFirst().schemaDigest());
    var script = DirectFieldMappingScript.from(mapping, envelope).orElseThrow().script();
    var graph = shell.withNodeProperty("script-1", "script", script);
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), graph);
    parkBeforeWrites(directory);
    var subscription = capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))
        .subscribe().with(signal -> org.junit.jupiter.api.Assertions.fail("Verified COPY should park before writes: " + signal));
    try {
      assertTrue(java.nio.file.Files.exists(directory.resolve("checkpoint.json")),
          () -> "Expected checkpoint at " + directory.resolve("checkpoint.json"));
      verify(materializer, never()).resume(any(), any());
    } finally {
      subscription.cancel();
    }
  }

  @Test
  void exactVerbatimMappingKeepsItsSeparateAuthorityAtTheWriteBoundary(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var fixture = mappingFixture(false, true, false);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), fixture.graph());
    parkBeforeWrites(directory);
    var subscription = capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))
        .subscribe().with(signal -> org.junit.jupiter.api.Assertions.fail("Author-supplied body should park before catalog writes: " + signal));
    try {
      assertTrue(java.nio.file.Files.exists(directory.resolve("checkpoint.json")),
          () -> "Expected checkpoint at " + directory.resolve("checkpoint.json"));
      verify(materializer, never()).resume(any(), any());
    } finally {
      subscription.cancel();
    }
  }

  @Test
  void filledMappingSiteWithoutAnApprovedMappingIsRejected() throws Exception {
    var fixture = mappingFixture(false, false, false);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(fixture.brief().withMappingIntents(List.of()), fixture.semantic(), fixture.graph());
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void verbatimBodyCannotAuthorizeAnotherMappingSite() throws Exception {
    var fixture = mappingFixture(false, true, false);
    var nodes = new java.util.ArrayList<>(fixture.graph().nodes());
    nodes.add(new ChainPlanNode("second-copy", "script", "Unapproved copy", null, null, List.of(
        new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "copy"),
        new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", "exchange.in.body = [processId:'wrong']"))));
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(), nodes, fixture.graph().edges());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), graph);
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void verifiedCopyCannotMovePastAnotherBodyProducer() throws Exception {
    var fixture = mappingFixture(true, false, false);
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(), fixture.graph().nodes(), List.of(
        new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("to-call", "trigger-1", "call-1", null),
        new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("late-copy", "call-1", "script-1", null)));
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    capability.copySchemas = fixture.schemas();
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), graph);
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void suppliedBodyBetweenCopySourceAndSiteCannotWriteOrResume(boolean resumed) throws Exception {
    var fixture = mappingFixture(true, false, false);
    String body = "exchange.in.body = [processInstanceId:'replacement']";
    var nodes = new java.util.ArrayList<>(fixture.graph().nodes());
    nodes.add(new ChainPlanNode("supplied-intervening", "script", "Supplied body replacement", null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body))));
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(), nodes, List.of(
        new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("source-to-supplied", "trigger-1", "supplied-intervening", null),
        new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("supplied-to-copy", "supplied-intervening", "script-1", null),
        new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("copy-to-call", "script-1", "call-1", null)));
    var brief = fixture.brief().withFacts(List.of(
        org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
            .suppliedFact("supplied-intervening", body)));
    assertDoesNotThrow(() -> org.qubership.integration.platform.ai.plan.mapping.ScriptExecutionAuthority
        .requireSupported(brief, graph));
    capability.copySchemas = fixture.schemas();
    var prepared = appendHappyPathV2Inputs(brief, fixture.semantic(), graph);
    if (resumed) appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    assertTrue(outcome.message().contains("COPY execution site crosses another body-producing node"), outcome.message());
    verify(materializer, never()).resume(any(), any());
    assertEquals(java.util.Optional.empty(), artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT));
  }

  @Test
  void filledScriptCannotChangeTheVerifiedCopyBodyOnResume() throws Exception {
    var fixture = mappingFixture(true, false, true);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    capability.copySchemas = fixture.schemas();
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), fixture.graph());
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"unqualified", "generated-candidate", "foreign-node", "changed-body", "invalid-range", "orphan-retention"})
  void zeroMappingScriptsWithoutTheirOwnAuthorityCannotWriteOrResume(String partition) throws Exception {
    var fixture = mappingFixture(false, false, false);
    String body = "exchange.in.body = [processId:'invented']";
    var properties = new java.util.ArrayList<org.qubership.integration.platform.ai.plan.model.PlanProperty>();
    properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body));
    if ("orphan-retention".equals(partition)) properties.add(
        new org.qubership.integration.platform.ai.plan.model.PlanProperty("nativeRetentionSite", "true"));
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(),
        fixture.graph().nodes().stream().map(node -> "script-1".equals(node.nodeId())
            ? new ChainPlanNode(node.nodeId(), node.type(), node.label(), null, null, properties) : node).toList(),
        fixture.graph().edges());
    var brief = fixture.brief().withMappingIntents(List.of());
    if (!List.of("unqualified", "orphan-retention").contains(partition)) {
      var authority = "generated-candidate".equals(partition)
          ? org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.GENERATED_CANDIDATE
          : org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY;
      brief = brief.withFacts(List.of(org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures.bodyFact(
          "foreign-node".equals(partition) ? "other-script" : "script-1",
          "changed-body".equals(partition) ? "return 1" : body, authority,
          "invalid-range".equals(partition) ? -1 : 0, body.length())));
    }
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(brief, fixture.semantic(), graph);
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
    assertEquals(java.util.Optional.empty(), artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-node", "blank-body", "invalid-groovy"})
  void approvedGeneratedScriptMustHaveAnExecutableBodyBeforeCatalogWrite(String defect) throws Exception {
    var fixture = mappingFixture(false, false, false);
    var brief = fixture.brief().withMappingIntents(List.of()).withFacts(List.of(
        org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
            .generatedBehavior("script-1", "Return Hello world")));
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(),
        fixture.graph().nodes().stream()
            .filter(node -> !("missing-node".equals(defect) && "script-1".equals(node.nodeId())))
            .map(node -> "script-1".equals(node.nodeId())
                ? new ChainPlanNode(node.nodeId(), node.type(), node.label(), null, null, List.of(
                    new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                        "script", "blank-body".equals(defect) ? "" : "return [")))
                : node).toList(), fixture.graph().edges());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(
        compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(brief, fixture.semantic(), graph);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    assertTrue(outcome.message().contains("invalid-groovy".equals(defect)
        ? "Groovy mapping:" : "Missing executable body for approved script script-1"), outcome.message());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void exactSuppliedOnlyScriptReachesTheWriteBoundaryWithoutMappings(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var fixture = mappingFixture(false, true, false);
    String body = fixture.graph().nodes().stream().filter(node -> "script-1".equals(node.nodeId()))
        .flatMap(node -> node.properties().stream()).filter(property -> "script".equals(property.key()))
        .findFirst().orElseThrow().value();
    String supplied = "  " + body + "\n\n";
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(),
        fixture.graph().nodes().stream().map(node -> "script-1".equals(node.nodeId())
            ? new ChainPlanNode(node.nodeId(), node.type(), node.label(), null, null, List.of(
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", supplied))) : node).toList(),
        fixture.graph().edges());
    var brief = fixture.brief().withMappingIntents(List.of()).withFacts(List.of(
        org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures.suppliedFact("script-1", supplied)));
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(brief, fixture.semantic(), graph);
    parkBeforeWrites(directory);
    var subscription = capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))
        .subscribe().with(signal -> org.junit.jupiter.api.Assertions.fail("Exact supplied body should reach writes: " + signal));
    try {
      assertTrue(java.nio.file.Files.exists(directory.resolve("checkpoint.json")),
          () -> "Expected checkpoint at " + directory.resolve("checkpoint.json"));
    } finally {
      subscription.cancel();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void supplementarySuppliedFactsReachFreshAndResumedWritesWithExactBytes(boolean resumed) throws Exception {
    var fixture = mappingFixture(false, true, false);
    String supplied = "  exchange.in.body = [Subject: 'author value']\nreturn exchange.in.body\n\n";
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(),
        fixture.graph().nodes().stream().map(node -> "script-1".equals(node.nodeId())
            ? new ChainPlanNode(node.nodeId(), node.type(), "script-1", null, null, List.of(
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", supplied))) : node).toList(),
        fixture.graph().edges());
    var facts = org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
        .suppliedFactsWithSupplementaryMetadata("script-1", supplied);
    var brief = fixture.brief().withMappingIntents(List.of()).withFacts(facts);
    var written = new java.util.concurrent.atomic.AtomicReference<ProductChainMaterializer.Inputs>();
    var writeReached = new RuntimeException("Fake catalog write reached");
    materializer = mock(ProductChainMaterializer.class, invocation -> {
      if ("resume".equals(invocation.getMethod().getName())) {
        written.set(invocation.getArgument(0));
        throw writeReached;
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    capability = new MaterializationCapability(artifactStore, materializer, factsService, reconcileService, designExecutor);
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    var prepared = appendHappyPathV2Inputs(brief, fixture.semantic(), graph);
    if (resumed) appendWaitingCheckpoint(prepared.approvalRef(), prepared);

    var error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))),
        "resumed=" + resumed);

    org.junit.jupiter.api.Assertions.assertSame(writeReached, error, "resumed=" + resumed);
    assertEquals(supplied, written.get().graph().nodes().stream().filter(node -> "script-1".equals(node.nodeId()))
        .flatMap(node -> node.properties().stream()).filter(property -> "script".equals(property.key()))
        .findFirst().orElseThrow().value(), "resumed=" + resumed);
    assertEquals(facts, brief.facts(), "resumed=" + resumed);
    verify(materializer).resume(any(), any());
    assertEquals(java.util.Optional.empty(), artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT), "resumed=" + resumed);
  }

  @Test
  void nativeGraphWithoutScriptsNeedsNoMappingAuthority(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var fixture = mappingFixture(false, false, false);
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(),
        fixture.graph().nodes().stream().filter(node -> !"script".equals(node.type())).toList(), List.of(
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("native-call", "trigger-1", "call-1", null)));
    var prepared = appendHappyPathV2Inputs(fixture.brief().withMappingIntents(List.of()), fixture.semantic(), graph);
    parkBeforeWrites(directory);
    var subscription = capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))
        .subscribe().with(signal -> org.junit.jupiter.api.Assertions.fail("Native graph should reach writes: " + signal));
    try {
      assertTrue(java.nio.file.Files.exists(directory.resolve("checkpoint.json")),
          () -> "Expected checkpoint at " + directory.resolve("checkpoint.json"));
    } finally {
      subscription.cancel();
    }
  }

  @Test
  void validFrozenCopyCannotAuthorizeAnExtraUntaggedScript() throws Exception {
    var fixture = mappingFixture(true, false, false);
    var nodes = new java.util.ArrayList<>(fixture.graph().nodes());
    nodes.add(new ChainPlanNode("extra-script", "script", "Extra script", null, null, List.of(
        new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", "exchange.in.body = [invented:true]"))));
    var graph = new ChainPlanGraph(fixture.graph().schemaVersion(), fixture.graph().chain(), nodes, fixture.graph().edges());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    capability.copySchemas = fixture.schemas();
    var prepared = appendHappyPathV2Inputs(fixture.brief(), fixture.semantic(), graph);
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    var outcome = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.message());
    verify(materializer, never()).resume(any(), any());
    assertEquals(java.util.Optional.empty(), artifactStore.latest(RUN_ID, Kind.MATERIALIZATION_RESULT));
  }

  private void parkBeforeWrites(java.nio.file.Path directory) throws Exception {
    var mapper = new ObjectMapper();
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "conv-1", "boundary", "checkpoint"));
    capability.recoveryControl = new org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl(directory, mapper);
  }

  private MappingFixture mappingFixture(boolean verified, boolean supplied, boolean altered) throws Exception {
    return mappingFixture(verified, supplied, altered, false);
  }

  private MappingFixture mappingFixture(boolean verified, boolean supplied, boolean altered,
      boolean customEntry) throws Exception {
    var mapper = new ObjectMapper();
    var sourceJson = mapper.readTree("""
        {"type":"object","properties":{"processInstanceId":{"type":"string"}}}
        """);
    var targetJson = mapper.readTree("""
        {"type":"object","properties":{"processId":{"type":"string"}}}
        """);
    String sourceHash = customEntry ? "b4e9050f8405c335ff1c5661fd13587d67811bb7fd15c6d8c3434c94bca7193b"
        : "d500736bdb6f43ad64a1bd56f2626820982dc705d8d6265f0262e0dcc14ab157";
    String targetHash = "a33b43fecff4d5a99f5a198a5cb5dd35950ad0c971893e2857015e0a166cbbfa";
    var origin = new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource.Message(
        "approved-entry", org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.OUTPUT, "$.processInstanceId");
    var rule = verified
        ? new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule("copy-rule", List.of("copy-fact"),
            "$.processId", new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue.Copy(origin), null, null)
            .withMissingSourcePolicy("ERROR").withSchemaPins(Map.of("source", sourceHash, "target", targetHash))
        : new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule(null, "$.processId", "Read the saved original processId");
    var mapping = new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent("copy",
        "approved-entry", org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.OUTPUT,
        "target", org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.REQUEST, List.of(rule));
    var sourceSide = new org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide(
        "1", "approved-entry", "op-entry", org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.REQUEST,
        "application/json", null, sourceHash, "catalog-source", sourceJson);
    var targetSide = new org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide(
        "1", "target", "op-target", org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.REQUEST,
        "application/json", null, targetHash, "catalog-target", targetJson);
    String body = "exchange.in.body = [processId: exchange.in.body.processId]";
    if (verified) {
      var plan = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingPlan(List.of(
          new org.qubership.integration.platform.ai.plan.mapping.CopyMappingPlan.Read("copy-rule", "processId", origin,
              sourceHash, targetHash, null, false)), List.of());
      var envelope = new org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory(mapper)
          .fromSides(sourceSide, targetSide).withMappingIntentId("copy").withVerifiedCopyPlan(plan);
      appendMappingEvidence(Kind.MAPPING_SCHEMA_SIDE, targetSide);
      if (!customEntry) {
        appendMappingEvidence(Kind.MAPPING_SCHEMA_SIDE, sourceSide);
        appendMappingEvidence(Kind.MAPPING_ENVELOPE, envelope);
      }
      body = org.qubership.integration.platform.ai.plan.mapping.DirectFieldMappingScript.from(mapping, envelope).orElseThrow().script();
    }
    if (altered) body = "exchange.in.body = [processId: 'invented']";
    List<org.qubership.integration.platform.ai.plan.RequirementFact> facts = List.of();
    if (customEntry) {
      facts = List.of(
          new RequirementFact("route", RequirementFactPolarity.POSITIVE, RequirementFactKind.CAPABILITY,
              "http-trigger", "Accept a request.", "Source", "GET /approved", null, "GET", "/approved",
              null, List.of("approved-entry")),
          new RequirementFact("input-schema", RequirementFactPolarity.POSITIVE, RequirementFactKind.MESSAGE_SCHEMA,
              "", sourceJson.toString(), null, null, null, null, null, null, List.of("approved-entry")));
    }
    if (supplied) {
      var binding = new org.qubership.integration.platform.ai.plan.RequirementBodyBinding(
          org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY,
          "script-1", "script", org.qubership.integration.platform.ai.plan.RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT,
          "generation", "author-turn", 0, body.length(), org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Encoding.RAW);
      var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("script-1", "script",
          org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
          "approved-entry", null, null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body)), List.of(), binding);
      facts = List.of(new org.qubership.integration.platform.ai.plan.RequirementFact("supplied-script",
          org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
          org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "", "Use the supplied body",
          "", "", "", "", "", "", List.of("approved-entry"), null, intent));
    }
    var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
        "HTTP endpoint", List.of(), List.of(), List.of(), List.of(), "HTTP endpoint", null, "", facts,
        List.of(new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint(
            "approved-entry", "source-http", "http-trigger", "", "GET", "/approved", "GET /approved")),
        List.of(), List.of(), List.of(mapping))
        .withFlow(new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow(List.of(
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction("approved-entry",
                org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND, "Source", "GET /approved", ""),
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction("target",
                org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND, "Target", "POST /target", "")),
            List.of(new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition("approved-entry", "target"))))
        .withCatalogBindings(customEntry ? List.of(mappingBinding("target", "op-target"))
            : List.of(mappingBinding("approved-entry", "op-entry"), mappingBinding("target", "op-target")));
    var semantic = new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision(
        "chain-semantic-revision/v1", "semantic", "demo-chain", "1",
        List.of(new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint(
            "approved-entry", "trigger-1", "call-1", 0, null, null)),
        List.of(new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode.Trigger(
                "trigger-1", "approved-entry", "http-trigger", null),
            new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode.ServiceCall(
                "call-1", "target", "POST /target", null)), List.of(),
        List.of(new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge(
            "edge", "trigger-1", "call-1", null, null, "copy")),
        List.of(), List.of(), List.of(), List.of(), List.of());
    var graph = new ChainPlanGraph("1.0", new ChainSection("demo-chain", "Demo"), List.of(
        new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of(
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("contextPath", "/approved"),
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("httpMethodRestrict", "GET"))),
        new ChainPlanNode("script-1", "script", "Copy", null, null, List.of(
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "copy"),
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body))),
        new ChainPlanNode("call-1", "service-call", "Target", null, null, List.of())),
        List.of(new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("to-copy", "trigger-1", "script-1", null),
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("to-call", "script-1", "call-1", null)));
    var operations = Map.of("op-entry", new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps(
        "op-entry", Map.of("application/json", sourceJson), Map.of()),
        "op-target", new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps(
            "op-target", Map.of("application/json", targetJson), Map.of()));
    return new MappingFixture(brief, semantic, graph,
        new org.qubership.integration.platform.ai.plan.BriefCopyMappingValidator(new ReadOnlyMappingSchemas(operations), mapper));
  }

  private void appendMappingEvidence(Kind kind, Object payload) {
    compilationArtifacts.append(new AppendCommand(
        CONVERSATION_ID, kind, "1", "compiler-fixture", "1", payload, List.of(), null));
  }

  private static org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint mappingBinding(String id, String operation) {
    return new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint("3", id,
        id, operation, "system", "group", "spec", operation, "HTTP", "POST", "/target", "1", FIXED, "catalog-evidence");
  }

  private record MappingFixture(org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief,
      org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision semantic,
      ChainPlanGraph graph, org.qubership.integration.platform.ai.plan.BriefCopyMappingValidator schemas) {}

  private record ReadOnlyMappingSchemas(Map<String,
      org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps> values)
      implements org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader {
    @Override public org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps load(String operationId) {
      return values.get(operationId);
    }
    @Override public org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide persistRequest(
        String compilationId, String serviceCallId, String operationId, String contentType) { throw new UnsupportedOperationException(); }
    @Override public org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide persistResponse(
        String compilationId, String serviceCallId, String operationId, String contentType, String responseCode) { throw new UnsupportedOperationException(); }
  }

  @Test
  void returnsContractFailureWhenMandatoryInputsAreMissing() {
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void focusedCheckpointRequiresNativeAdmissionAndPreventsTheFirstCatalogWrite(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var mapper = new ObjectMapper();
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "conv-1", "boundary", "checkpoint"));
    capability.recoveryControl = new org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl(
        directory, mapper);
    PreparedV2Inputs invalidEndpoint = appendHappyPathV2Inputs("/wrong");
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE,
        completed(capability.execute(v2Context(invalidEndpoint, invalidEndpoint.requestRef(), invalidEndpoint.bundleRef()))).outcome().outcomeClass());
    assertFalse(java.nio.file.Files.exists(directory.resolve("checkpoint.json")));

    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    var wrongRef = new Reference(Kind.VALIDATED_EXECUTION_BUNDLE, "absent", "absent");
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE,
        completed(capability.execute(v2Context(prepared, prepared.requestRef(), wrongRef))).outcome().outcomeClass());
    assertFalse(java.nio.file.Files.exists(directory.resolve("checkpoint.json")));

    var stream = capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef()));
    var subscription = stream.subscribe().with(ignored -> org.junit.jupiter.api.Assertions.fail("Parked stage emitted success"));
    assertTrue(java.nio.file.Files.exists(directory.resolve("checkpoint.json")));
    var checkpoint = mapper.readTree(directory.resolve("checkpoint.json").toFile());
    assertEquals(prepared.bundle().graphRef().artifactId(), checkpoint.path("graphRef").path("artifactId").asText());
    verify(materializer, never()).resume(any(), any());
    subscription.cancel();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"\"/approved\"", "/other", "/APPROVED", "/approved/"})
  void rejectsUrlMismatchBeforeAnyCatalogWrite(String generatedPath) {
    var prepared = appendHappyPathV2Inputs(generatedPath);
    append(Kind.REQUIREMENT_BRIEF, "1",
        new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
            "Unapproved later brief", List.of(), List.of(), List.of(), List.of(), "", null, "", List.of(), List.of()),
        List.of());
    org.mockito.Mockito.lenient().doThrow(new AssertionError("Catalog write started before URL validation"))
        .when(materializer).resume(any(), any());
    var result = completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef())));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcome().outcomeClass());
    var finding = result.outcome().recoveryCause().findings().getFirst();
    assertEquals("HTTP_ENDPOINT_MISMATCH", finding.code());
    assertEquals("trigger-1", finding.originRecordId());
    assertEquals("/properties/contextPath", finding.fieldPointer());
    assertTrue(finding.message().contains("/approved"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void returnsContractFailureWhenApprovalDigestDoesNotMatchGraphDigest() {
    PreparedInputs prepared = appendHappyPathInputs();
    ApprovalRecordV2 mismatchedApproval =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            "different-digest",
            prepared.approvedCandidates(),
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference mismatchedApprovalRef = append(Kind.APPROVAL_RECORD, "2", mismatchedApproval, List.of());
    StageExecutionContext context = contextWith(prepared, mismatchedApprovalRef);

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void configuredRecoveryFaultStopsBeforeV2CatalogWrite() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    capability =
        new MaterializationCapability(
            artifactStore,
            materializer,
            factsService,
            reconcileService,
            new CanonicalGraphDigest(new ObjectMapper()),
            designExecutor,
            new E2eRecoveryFaultInjector(
                "demo-chain", "materialization=CONTRACT_SHAPE:1"));

    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, prepared.requestRef(), prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertEquals(
        RecoveryCauseCode.CONTRACT_SHAPE,
        completed.outcome().recoveryCause().causeCode());
    assertTrue(completed.outcome().message().contains("E2E recovery fault"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void readableLegacyInputsRequireNewApprovalBeforeCatalogWrites() {
    PreparedInputs prepared = appendHappyPathInputs();
    var before = artifactStore.get(RUN_ID, prepared.graphRef()).orElseThrow().payload().deepCopy();
    var outcome = completed(capability.execute(contextWith(prepared, prepared.approvalRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertEquals(RecoveryCauseCode.CONTRACT_SHAPE, outcome.recoveryCause().causeCode());
    assertTrue(outcome.message().contains("create-chain@2"), outcome.message());
    verify(materializer, never()).resume(any(), any());
    assertEquals(before, artifactStore.get(RUN_ID, prepared.graphRef()).orElseThrow().payload());
  }

  @Test
  void legacyV1FilledCopyCannotResumeCatalogWrites() {
    PreparedInputs prepared = appendHappyPathInputs("exchange.in.body = [processId: exchange.in.body.processId]");
    var before = artifactStore.get(RUN_ID, prepared.graphRef()).orElseThrow().payload().deepCopy();
    var outcome = completed(capability.execute(contextWith(prepared, prepared.approvalRef()))).outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("Recapture"), outcome.message());
    verify(materializer, never()).resume(any(), any());
    assertEquals(before, artifactStore.get(RUN_ID, prepared.graphRef()).orElseThrow().payload());
  }

  @Test
  void runtimeRegistryContainsCompletedMaterializationCapability() {
    StageCapabilityRegistry registry = new StageCapabilityRegistry(List.of(capability));
    assertInstanceOf(
        MaterializationCapability.class,
        registry.require(MaterializationCapability.CAPABILITY_ID));
  }

  @Test
  void returnsContractFailureWhenGraphBytesDifferFromAssemblyDigest() {
    PreparedInputs prepared = appendHappyPathInputs();
    ChainPlanGraph differentGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("other", "Other"),
            List.of(new ChainPlanNode("only", "script", "Only", null, null, List.of())),
            List.of());
    GraphAssemblyResult mismatched =
        new GraphAssemblyResult(
            1, differentGraph, prepared.graphDigest(), List.of(), List.of(), List.of());
    Reference badAssembly = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", mismatched, List.of(prepared.graphRef()));
    List<Reference> candidates =
        List.of(
            prepared.implementationPlanRef(),
            prepared.validationRef(),
            prepared.graphRef(),
            badAssembly,
            prepared.bundleRef());
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            prepared.implementationPlanRef().contentHash(),
            candidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, candidates);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(
                prepared.implementationPlanRef(),
                prepared.validationRef(),
                prepared.graphRef(),
                badAssembly,
                prepared.bundleRef(),
                approvalRef,
                prepared.runManifestRef()),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void returnsFailureBeforeResumeWhenCompilerBundleNotEligible() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of())),
            List.of());
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle failingBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(
                new CompilerValidationPass(
                    "validator", new ValidationResult(false, List.of(), "blocked"))));
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
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
    PlanValidationResult validationResult = new PlanValidationResult(List.of());
    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference bundleRef = append(Kind.COMPILER_VALIDATION_BUNDLE, "1", failingBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifest(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, validationRef, graphRef, assemblyRef, bundleRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(
                implementationPlanRef,
                validationRef,
                graphRef,
                assemblyRef,
                bundleRef,
                approvalRef,
                runManifestRef),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void returnsFailureBeforeResumeWhenOrderedPatchReferenceMissing() {
    PreparedInputs prepared = appendHappyPathInputs();
    Reference dangling =
        new Reference(Kind.GRAPH_PATCH_ARTIFACT, "missing-art", "missing-hash");
    ChainPlanGraph graph =
        artifactStore.payload(
            artifactStore.get(RUN_ID, prepared.graphRef()).orElseThrow(), ChainPlanGraph.class);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(
            1, graph, prepared.graphDigest(), List.of(dangling), List.of(), List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(prepared.graphRef()));
    List<Reference> candidates =
        List.of(
            prepared.implementationPlanRef(),
            prepared.validationRef(),
            prepared.graphRef(),
            assemblyRef,
            prepared.bundleRef());
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            prepared.implementationPlanRef().contentHash(),
            candidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, candidates);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(
                prepared.implementationPlanRef(),
                prepared.validationRef(),
                prepared.graphRef(),
                assemblyRef,
                prepared.bundleRef(),
                approvalRef,
                prepared.runManifestRef()),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsValidatedBundleContentHashMismatch() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    ValidatedExecutionBundle foreign =
        new ValidatedExecutionBundle(
            "1",
            prepared.bundle().approvalRef(),
            prepared.bundle().designPlanReportRef(),
            prepared.bundle().designPlanReportHash(),
            prepared.bundle().designExecutionPlanRef(),
            prepared.bundle().designExecutionPlanHash(),
            prepared.bundle().runManifestRef(),
            prepared.bundle().graphRef(),
            "foreign-graph-digest",
            prepared.bundle().orderedGraphPatchesRef(),
            prepared.bundle().orderedPatchDigest(),
            prepared.bundle().graphValidationRef(),
            prepared.bundle().planValidationRef(),
            prepared.bundle().compilerValidationRef(),
            prepared.bundle().executorValidationRef());
    Reference foreignBundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", foreign, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, prepared.requestRef(), foreignBundleRef)));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(
        completed.outcome().message().toLowerCase().contains("does not reference the validated bundle"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2AcceptsReproducedValidatedBundleSameContentHash() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    Reference reproducedBundleRef =
        append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", prepared.bundle(), List.of());
    assertNotEquals(prepared.bundleRef().artifactId(), reproducedBundleRef.artifactId());
    assertEquals(prepared.bundleRef().contentHash(), reproducedBundleRef.contentHash());
    assertNotEquals(prepared.request().validatedExecutionBundleRef(), reproducedBundleRef);

    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    StageExecutionContext context =
        v2Context(prepared, prepared.requestRef(), reproducedBundleRef);
    MaterializationResult readBack =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of()),
            prepared.request().graphDigest(),
            MaterializationPhase.READ_BACK);
    MaterializationResult reconciled =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.RECONCILE);
    MaterializationResult complete =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.COMPLETE);
    when(materializer.resume(any(), any())).thenReturn(readBack);
    when(materializer.markReconciled(any(), any())).thenReturn(reconciled);
    when(materializer.markComplete(any(), any())).thenReturn(complete);
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                2,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(reconcileService.compare(any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed.outcome().outcomeClass(),
        () -> String.valueOf(completed.outcome().message()));
    verify(materializer).resume(any(), any());
  }

  @Test
  void v2RejectsStaleDesignPlanReportHash() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest stale =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            new Reference(
                Kind.DESIGN_PLAN_REPORT,
                prepared.request().designPlanReportRef().artifactId(),
                "stale-report-hash"),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            prepared.request().validatedExecutionBundleRef());
    Reference staleRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", stale, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, staleRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsStaleProjectionHash() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest stale =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            new Reference(
                Kind.DESIGN_EXECUTION_PLAN,
                prepared.request().designExecutionPlanRef().artifactId(),
                "stale-plan-hash"),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            prepared.request().validatedExecutionBundleRef());
    Reference staleRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", stale, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, staleRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsGraphDigestMismatch() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest mismatched =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            "other-graph-digest",
            prepared.request().orderedPatchDigest(),
            prepared.request().validatedExecutionBundleRef());
    Reference badRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", mismatched, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, badRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsOrderedPatchDigestMismatch() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest mismatched =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            "other-patch-digest",
            prepared.request().validatedExecutionBundleRef());
    Reference badRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", mismatched, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, badRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsFailedExecutorValidationPass() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    ExecutorValidationBundle failed =
        new ExecutorValidationBundle(
            "1",
            prepared.request().graphDigest(),
            prepared.request().designPlanReportRef(),
            prepared.request().designPlanReportRef().contentHash(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().designExecutionPlanRef().contentHash(),
            false,
            List.of("failed"));
    Reference failedExecutorRef = append(Kind.EXECUTOR_VALIDATION_BUNDLE, "1", failed, List.of());
    ValidatedExecutionBundle badBundle =
        new ValidatedExecutionBundle(
            "1",
            prepared.bundle().approvalRef(),
            prepared.bundle().designPlanReportRef(),
            prepared.bundle().designPlanReportHash(),
            prepared.bundle().designExecutionPlanRef(),
            prepared.bundle().designExecutionPlanHash(),
            prepared.bundle().runManifestRef(),
            prepared.bundle().graphRef(),
            prepared.bundle().graphDigest(),
            prepared.bundle().orderedGraphPatchesRef(),
            prepared.bundle().orderedPatchDigest(),
            prepared.bundle().graphValidationRef(),
            prepared.bundle().planValidationRef(),
            prepared.bundle().compilerValidationRef(),
            failedExecutorRef);
    Reference badBundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", badBundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            badBundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, requestRef, badBundleRef)));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().toLowerCase().contains("executor"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsMissingApprovalCandidate() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    ApprovalRecordV2 incomplete =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            prepared.implementationPlanRef().contentHash(),
            List.of(prepared.implementationPlanRef()),
            "user",
            null,
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference incompleteApprovalRef = append(Kind.APPROVAL_RECORD, "2", incomplete, List.of());
    ValidatedExecutionBundle badBundle =
        new ValidatedExecutionBundle(
            "1",
            incompleteApprovalRef,
            prepared.bundle().designPlanReportRef(),
            prepared.bundle().designPlanReportHash(),
            prepared.bundle().designExecutionPlanRef(),
            prepared.bundle().designExecutionPlanHash(),
            prepared.bundle().runManifestRef(),
            prepared.bundle().graphRef(),
            prepared.bundle().graphDigest(),
            prepared.bundle().orderedGraphPatchesRef(),
            prepared.bundle().orderedPatchDigest(),
            prepared.bundle().graphValidationRef(),
            prepared.bundle().planValidationRef(),
            prepared.bundle().compilerValidationRef(),
            prepared.bundle().executorValidationRef());
    Reference badBundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", badBundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            incompleteApprovalRef,
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            badBundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    capability.copyMappings = new org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator(compilationArtifacts);
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, requestRef, badBundleRef)));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().toLowerCase().contains("candidate"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void createChainV3ProfileUsesDesignExecutionMaterializationPath() throws Exception {
    ProductPipelineProfile profile = createChainV3FromBundledV2();
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    RunManifest manifestV3 = runManifestWithProfileVersion("3");
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            profile,
            manifestV3,
            List.of(prepared.requestRef(), prepared.bundleRef(), prepared.runManifestRef()),
            Map.of());
    MaterializationResult readBack =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of()),
            prepared.request().graphDigest(),
            MaterializationPhase.READ_BACK);
    MaterializationResult reconciled =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.RECONCILE);
    MaterializationResult complete =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.COMPLETE);
    when(materializer.resume(any(), any())).thenReturn(readBack);
    when(materializer.markReconciled(any(), any())).thenReturn(reconciled);
    when(materializer.markComplete(any(), any())).thenReturn(complete);
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                2,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(reconcileService.compare(any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
  }

  @Test
  void v2SuccessfulReadbackMovesExecutorCheckpointToComplete() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    StageExecutionContext context =
        v2Context(prepared, prepared.requestRef(), prepared.bundleRef());
    MaterializationResult readBack =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of()),
            prepared.request().graphDigest(),
            MaterializationPhase.READ_BACK);
    MaterializationResult reconciled =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.RECONCILE);
    MaterializationResult complete =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.COMPLETE);
    when(materializer.resume(any(), any())).thenReturn(readBack);
    when(materializer.markReconciled(any(), any())).thenReturn(reconciled);
    when(materializer.markComplete(any(), any())).thenReturn(complete);
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                2,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(reconcileService.compare(any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed.outcome().outcomeClass(),
        () -> String.valueOf(completed.outcome().message()));
    assertTrue(
        completed.outcome().candidates().stream()
            .anyMatch(candidate -> candidate.kind() == Kind.DESIGN_EXECUTION_RESULT));
    DesignExecutionCheckpoint checkpoint =
        artifactStore
            .latest(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(revision -> artifactStore.payload(revision, DesignExecutionCheckpoint.class))
            .orElseThrow();
    assertEquals(DesignExecutionPhase.COMPLETE, checkpoint.phase());
  }

  private PreparedInputs appendHappyPathInputs() {
    return appendHappyPathInputs(null);
  }

  private PreparedInputs appendHappyPathInputs(String scriptBody) {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, scriptBody == null ? List.of()
                    : List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "legacy-copy"),
                        new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", scriptBody)))),
            List.of());
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle compilerBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(new CompilerValidationPass("validator", new ValidationResult(true, List.of(), "ok"))));
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
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
    PlanValidationResult validationResult = new PlanValidationResult(List.of());

    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference bundleRef = append(Kind.COMPILER_VALIDATION_BUNDLE, "1", compilerBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifest(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, validationRef, graphRef, assemblyRef, bundleRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    return new PreparedInputs(
        graphRef,
        assemblyRef,
        bundleRef,
        validationRef,
        implementationPlanRef,
        runManifestRef,
        approvalRef,
        approvedCandidates,
        graphDigest);
  }

  private StageExecutionContext contextWith(PreparedInputs prepared, Reference approvalRef) {
    return new StageExecutionContext(
        RUN_ID,
        CONVERSATION_ID,
        "materialization",
        RUN_ID,
        "attempt-1",
        null,
        runManifest(),
        List.of(
            prepared.implementationPlanRef(),
            prepared.validationRef(),
            prepared.graphRef(),
            prepared.assemblyRef(),
            prepared.bundleRef(),
            approvalRef,
            prepared.runManifestRef()),
        Map.of());
  }

  private Reference append(
      Kind kind, String schemaVersion, Object payload, List<Reference> inputs) {
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                RUN_ID,
                kind,
                schemaVersion,
                "test-producer",
                "1",
                payload,
                inputs,
                null,
                new ArtifactProvenance(RUN_ID, "materialization", "create-chain", "1", "profile-sha", "test", "1", "closure-sha")));
    return revision.reference();
  }

  private static CapabilitySignal.Completed completed(Multi<CapabilitySignal> stream) {
    return stream.collect().asList().await().indefinitely().stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static String sha256Hex(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static RunManifest runManifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        "1",
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("implementation-plan", 2)),
        null);
  }

  private static ProductPipelineProfile createChainV3FromBundledV2() throws Exception {
    try (var input =
        MaterializationCapabilityTest.class.getResourceAsStream(
            "/product-pipelines/profiles/create-chain-v2.yaml")) {
      ProductPipelineProfile v2 = ProductPipelineProfileParser.parse(input);
      return new ProductPipelineProfile(
          v2.schemaVersion(),
          v2.profileId(),
          "3",
          v2.runInputs(),
          v2.stages(),
          v2.terminal(),
          v2.dependencyRoots(),
          v2.compilerPipeline(),
          v2.implementationGate());
    }
  }

  private static RunManifest runManifestWithProfileVersion(String profileVersion) {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        profileVersion,
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("implementation-plan", 2)),
        null);
  }

  private static RunManifest runManifestV2() {
    return runManifestWithProfileVersion("2");
  }

  private PreparedV2Inputs appendHappyPathV2Inputs() {
    return appendHappyPathV2Inputs("/approved");
  }

  private PreparedV2Inputs appendHappyPathV2Inputs(String generatedPath) {
    var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
        "HTTP endpoint", List.of(), List.of(), List.of(), List.of(), "HTTP endpoint", null, "", List.of(),
        List.of(new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint(
            "approved-entry", "source-http", "http-trigger", "", "GET", "/approved", "GET /approved")),
        List.of(), List.of(), List.of());
    brief = brief.withFacts(List.of(org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
        .suppliedFact("script-1", "return null")));
    var semantic = new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision(
        "chain-semantic-revision/v1", "semantic", "demo-chain", "1",
        List.of(new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint(
            "approved-entry", "trigger-1", "script-1", 0, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null,
                    List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("contextPath", generatedPath),
                        new org.qubership.integration.platform.ai.plan.model.PlanProperty("httpMethodRestrict", "GET"))),
                new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of(
                    new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", "return null")))),
            List.of());
    return appendHappyPathV2Inputs(brief, semantic, graph);
  }

  private PreparedV2Inputs appendHappyPathV2Inputs(
      org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief,
      org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision semantic,
      ChainPlanGraph graph) {
    Reference briefRef = append(Kind.REQUIREMENT_BRIEF, "1", brief, List.of());
    Reference semanticRef = append(Kind.CHAIN_SEMANTIC_REVISION, semantic.schemaVersion(), semantic, List.of(briefRef));
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    String orderedPatchDigest = sha256Hex("[]");
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle compilerBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(new CompilerValidationPass("validator", new ValidationResult(true, List.of(), "ok"))));
    PlanValidationResult validationResult = new PlanValidationResult(List.of());
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
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

    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of(semanticRef));
    Reference reportRef =
        append(Kind.DESIGN_PLAN_REPORT, "1", Map.of("markdown", "# plan"), List.of());
    Reference planRef =
        append(Kind.DESIGN_EXECUTION_PLAN, "1", Map.of("flowId", "flow-1"), List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of(semanticRef));
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference orderedPatchesRef =
        append(Kind.ORDERED_GRAPH_PATCHES, "1", new OrderedGraphPatches("1", List.of()), List.of());
    Reference compilerBundleRef =
        append(Kind.COMPILER_VALIDATION_BUNDLE, "1", compilerBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifestV2(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, reportRef, planRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    ExecutorValidationBundle executorValidation =
        new ExecutorValidationBundle(
            "1",
            graphDigest,
            reportRef,
            reportRef.contentHash(),
            planRef,
            planRef.contentHash(),
            true,
            List.of());
    Reference executorValidationRef =
        append(Kind.EXECUTOR_VALIDATION_BUNDLE, "1", executorValidation, List.of());
    ValidatedExecutionBundle bundle =
        new ValidatedExecutionBundle(
            "1",
            approvalRef,
            reportRef,
            reportRef.contentHash(),
            planRef,
            planRef.contentHash(),
            runManifestRef,
            graphRef,
            graphDigest,
            orderedPatchesRef,
            orderedPatchDigest,
            validationRef,
            validationRef,
            compilerBundleRef,
            executorValidationRef);
    Reference bundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", bundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            approvalRef,
            reportRef,
            planRef,
            graphDigest,
            orderedPatchDigest,
            bundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    return new PreparedV2Inputs(
        request,
        requestRef,
        bundle,
        bundleRef,
        implementationPlanRef,
        approvalRef,
        runManifestRef,
        assemblyRef);
  }

  private void appendWaitingCheckpoint(Reference approvalRef, PreparedV2Inputs prepared) {
    DesignExecutionCheckpoint checkpoint =
        new DesignExecutionCheckpoint(
            "1",
            approvalRef,
            prepared.request().designPlanReportRef().contentHash(),
            prepared.request().designExecutionPlanRef().contentHash(),
            "manifest-hash",
            DesignExecutionPhase.WAITING_FOR_MATERIALIZATION,
            List.of(
                new DesignExecutionCheckpoint.CompletedStep(
                    "phase-5",
                    List.of(
                        prepared.request().designPlanReportRef().contentHash(),
                        prepared.request().designExecutionPlanRef().contentHash()),
                    List.of(prepared.bundleRef()),
                    List.of(prepared.bundleRef().contentHash()),
                    new ArtifactProvenance(
                        RUN_ID,
                        "design-execution",
                        "create-chain",
                        "2",
                        "profile-sha",
                        "design-execution",
                        "1",
                        "closure"),
                    "WAITING_FOR_MATERIALIZATION")));
    append(Kind.DESIGN_EXECUTION_CHECKPOINT, "1", checkpoint, List.of());
  }

  private StageExecutionContext v2Context(
      PreparedV2Inputs prepared, Reference requestRef, Reference bundleRef) {
    return new StageExecutionContext(
        RUN_ID,
        CONVERSATION_ID,
        "materialization",
        RUN_ID,
        "attempt-1",
        null,
        runManifestV2(),
        List.of(requestRef, bundleRef, prepared.runManifestRef()),
        Map.of());
  }

  private record PreparedInputs(
      Reference graphRef,
      Reference assemblyRef,
      Reference bundleRef,
      Reference validationRef,
      Reference implementationPlanRef,
      Reference runManifestRef,
      Reference approvalRef,
      List<Reference> approvedCandidates,
      String graphDigest) {}

  private record PreparedV2Inputs(
      MaterializationRequest request,
      Reference requestRef,
      ValidatedExecutionBundle bundle,
      Reference bundleRef,
      Reference implementationPlanRef,
      Reference approvalRef,
      Reference runManifestRef,
      Reference assemblyRef) {}
}

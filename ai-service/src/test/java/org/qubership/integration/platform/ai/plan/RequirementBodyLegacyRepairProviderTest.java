package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPackageIdentity;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineNode;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.*;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.*;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.stage.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.pack.*;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchArtifactFactory;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.validation.*;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.*;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementBodyLegacyRepairProviderTest {
  private static final String BODY = "  exchange.message.body = 'retained-author 😀'  \n\n";
  private static final String SOURCE = "Receive private POST /legacy-body. Use this exact script:\n```groovy\n"
      + BODY + "```\nProceed without IDS.";
  private static final String PATH = "/facts/script-body/elementIntent/bodyBinding";
  private static final String DISCOVERY = "requirement-discovery";

  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject RequirementCaptureTools tools;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts nativeArtifacts;
  @Inject ArtifactBlobStore nativeBlobs;
  @Inject CompilationSessions sessions;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject DeterministicElementSchemaService schemas;
  @Inject CompilerValidationPipeline validation;
  @Inject CompilerPlanValidator planValidator;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock RequirementDraftTool draftAdapter;

  @Test
  void retainedLegacyBodyRepairsThroughItsSourceOwnerAndActualProviderThenAssembles() throws Exception {
    var h = new Harness();
    var original = h.legacyRevision;
    var originalPayload = original.payload().deepCopy();
    var source = json.valueToTree(conversations.archive().read(h.chat));
    var limits = limits();

    // The diagnostic is read-only and does not create the DISCOVERY authority that admission needs.
    var refusal = h.update(h.correctedFact, "metadata-without-discovery");
    assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
    assertTrue(refusal.path("issues").toString().contains("REPAIR_FACT_LOSS"), refusal.toString());
    var rejected = drafts.rejectedCaptureRevision(h.chat).orElseThrow().reference();
    var before = h.nativeState();
    var proof = RequirementRevisionAdmission.evaluate(h.chat, rejected, drafts, nativeArtifacts, conversations,
        schemas, new RequirementRevisionAdmission.NativeInputs(h.brief(original.reference(), h.completed()), h.boundGraph()));
    assertEquals(RequirementRevisionAdmission.PreservationOutcome.VERIFIED, proof.outcome(),
        () -> proof.findings().stream().filter(value -> value.outcome() != RequirementRevisionAdmission.PreservationOutcome.VERIFIED).toList().toString());
    assertEquals(json.valueToTree(h.binding).properties().stream().map(value -> PATH + "/" + value.getKey()).sorted().toList(), proof.changedPaths());
    assertEquals(original.reference(), proof.baseReference());
    assertEquals(rejected, proof.attemptReference());
    assertEquals(CaptureProposal.digest(h.legacy.authoredDraft()), proof.baseHash());
    assertEquals(CaptureProposal.digest(conversations.archive().read(h.chat)), proof.sourceHash());
    assertEquals(before, h.nativeState(), "The preservation report must not admit or consume anything.");
    assertEquals(original.reference(), drafts.latestRevision(h.chat).orElseThrow().reference());

    var scripted = new ScriptedStreamingChatModel(
        Turn.tool("read-retained-body-source", request -> {
          assertEquals(1, h.engineCalls.get());
          assertNotNull(h.nativeFailure.get());
          var binding = ProductCapabilityCaptureContext.binding(h.chat).orElseThrow();
          assertEquals(Set.of(PATH), binding.requirementRepairPaths());
          assertEquals(h.legacy.authoredDraft(), binding.approvedDraft().authoredDraft());
          String packet = request.messages().toString();
          assertTrue(packet.contains("BODY_BINDING_REQUIRED"), packet);
          assertTrue(packet.contains("script-body"), packet);
          assertTrue(packet.contains("/elementIntent/bodyBinding"), packet);
          assertTrue(packet.contains(json.valueToTree(BODY).toString()), packet);
          assertTrue(packet.contains(h.binding.sourceTurnId()), packet);
          assertTrue(packet.contains(h.binding.sourceGeneration()), packet);
          assertTrue(packet.contains("Current") || packet.contains("current"), packet);
          h.assertEvidence();
          assertEquals(source, json.valueToTree(conversations.archive().read(h.chat)));
          assertEquals(original.reference(), drafts.latestRevision(h.chat).orElseThrow().reference());
          assertEquals(1, h.budget.state(h.run).requests());
          assertEquals(0, h.budget.state(h.run).grants());
        }, "readRequirementSource", json.writeValueAsString(Map.of("source",
            Map.of("turnId", h.binding.sourceTurnId(), "offset", 0, "maxChars", 16000)))),
        Turn.tool("complete-native-body-binding", request -> {
          String result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
              .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text();
          assertTrue(result.startsWith("Source turn=" + h.binding.sourceTurnId()), result);
          try {
            var retained = json.readTree(result.substring(result.indexOf('\n') + 1));
            assertEquals(SOURCE, retained.path("message").path("content").asText());
            assertEquals(original.artifactId(), retained.path("consumedDraftReference").asText());
          } catch (Exception failure) { throw new AssertionError(failure); }
          assertEquals(2, h.budget.state(h.run).requests());
          assertEquals(original.reference(), drafts.latestRevision(h.chat).orElseThrow().reference());
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update(h.correctedFact)))),
        Turn.tool("continue-native-legacy-repair", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          h.assertMetadataOnly();
          assertTrue(drafts.validateForContinuation(h.chat).isEmpty());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("legacy-repair-finished", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals("HANDOFF", lastTool(request).path("nextAction").asText());
        }, "Requirements are ready."));
    h.install(scripted);
    h.complete();
    scripted.assertComplete();

    assertEquals(4, scripted.requests().size());
    assertEquals(List.of(DISCOVERY), h.reopened);
    assertEquals(2, h.engineCalls.get());
    assertEquals(h.run, h.document().run().runId());
    assertEquals(RunStatus.PLAN_APPROVED, h.document().run().status());
    assertEquals(BODY, body(h.engineResult.get().graph()));
    assertEquals(h.boundGraph(), h.engineResult.get().graph());
    assertTrue(CompilerQualityValidator.validateApprovedValues(h.engineResult.get().graph(), h.finalBrief.get()).isEmpty());
    assertTrue(h.engineResult.get().validationBundle().approvalEligible(), h.engineResult.get().validationBundle().toString());
    assertEquals(5, h.engineResult.get().validationBundle().passes().size());
    assertTrue(h.artifacts.latest(h.run, Kind.VALIDATED_EXECUTION_BUNDLE).isPresent());
    h.assertMetadataOnly();
    assertEquals(source, json.valueToTree(conversations.archive().read(h.chat)), "Consumed author source stays exact.");
    assertEquals(originalPayload, nativeArtifacts.get(original.compilationId(), original.reference()).orElseThrow().payload());
    assertEquals(4, h.budget.state(h.run).requests());
    assertEquals(0, h.budget.state(h.run).grants());
    assertTrue(h.budget.state(h.run).active().isEmpty());
    assertEquals(h.budget.state(h.run), new LlmRunBudget(h.blobs, json, config).state(h.run));
    assertEquals(1, h.document().transitions().stream()
        .filter(value -> value.reason() != null && value.reason().startsWith(RecoveryAttemptLedger.AUTOMATIC_REOPEN_REASON_PREFIX)).count());
    var cause = new RecoveryCause(RecoveryCauseCode.BRIEF_CONTRACT, List.of(h.nativeFailure.get().finding()), "");
    var key = h.ledger.key(DISCOVERY, cause, RecoveryAttemptLedger.inputArtifactIdentity(h.document(), DISCOVERY), h.document().transitions());
    assertTrue(new RecoveryAttemptLedger().automaticReopenRecorded(h.document().transitions(), key, ""));
    var reloaded = new ProductPipelineRunStore(h.blobs, json, Clock.systemUTC()).load(h.run).orElseThrow();
    assertEquals(h.document(), reloaded);
    assertEquals(limits, limits());
    verify(h.narrative, never()).diagnoseRecovery(anyString(), any());
  }

  @Test
  void sourceBaseAndBodyTamperingCannotUseLegacyMetadataCompletionOrMutateFailureEvidence() throws Exception {
    var h = new Harness();
    var sourceBefore = json.valueToTree(conversations.archive().read(h.chat));
    var baseBefore = drafts.latestRevision(h.chat).orElseThrow();
    try {
      ProductCapabilityCaptureContext.bindDiscovery(h.run, h.chat, h.legacy, Map.of(), Set.of(PATH), ignored -> {});
      var wrongSource = new RequirementBodyBinding(h.binding.authority(), h.binding.elementRef(), h.binding.propertyKey(),
          h.binding.bodyKind(), "foreign-generation", h.binding.sourceTurnId(), h.binding.startOffset(), h.binding.endOffset(), h.binding.encoding());
      h.assertRejected(fact(BODY, wrongSource), "foreign-source", "BODY_SOURCE_MISMATCH");
      h.assertRejected(fact(BODY.strip(), h.binding), "changed-body", "BODY_SOURCE_MISMATCH");
      var bodyRefusal = drafts.rejectedCaptureRevision(h.chat).orElseThrow().reference();
      var wrongOwner = new RequirementBodyBinding(h.binding.authority(), "foreign-owner", h.binding.propertyKey(),
          h.binding.bodyKind(), h.binding.sourceGeneration(), h.binding.sourceTurnId(), h.binding.startOffset(), h.binding.endOffset(), h.binding.encoding());
      h.assertRejected(fact(BODY, wrongOwner), "foreign-owner", "BODY_SOURCE_MISMATCH");
      ProductCapabilityCaptureContext.unbind();
      var foreignBase = RequirementCaptureProjection.toDraft(input("different original body", null), null, List.of(), "legacy", "legacy");
      ProductCapabilityCaptureContext.bindDiscovery(h.run, h.chat, foreignBase, Map.of(), Set.of(PATH), ignored -> {});
      h.assertRejected(h.correctedFact, "foreign-base", "REPAIR_FACT_LOSS");
      assertEquals(baseBefore.reference(), drafts.latestRevision(h.chat).orElseThrow().reference());
      assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(h.chat)));
      assertNull(drafts.get(h.chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent().bodyBinding());
      var failure = assertThrows(RequestedElementValues.UnprovenBodyBinding.class,
          () -> h.executeEngine(h.brief(baseBefore.reference(), h.legacy)));
      assertEquals("script-body", failure.finding().originRecordId());
      assertEquals("/elementIntent/bodyBinding", failure.finding().fieldPointer());
      var state = h.nativeState();
      var diagnostic = RequirementRevisionAdmission.evaluate(h.chat, bodyRefusal, drafts, nativeArtifacts,
          conversations, schemas, new RequirementRevisionAdmission.NativeInputs(h.brief(baseBefore.reference(), h.completed()), h.boundGraph()));
      assertEquals(RequirementRevisionAdmission.PreservationOutcome.VIOLATED, diagnostic.outcome(), diagnostic.toString());
      assertEquals(state, h.nativeState(), "Failure diagnostics cannot change immutable attempts or the accepted base.");
      assertEquals(baseBefore.payload(), drafts.latestRevision(h.chat).orElseThrow().payload());
      assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(h.chat)));
      assertEquals(0, h.budget.state(h.run).requests());
      assertEquals(0, h.budget.state(h.run).grants());
      verifyNoInteractions(provider);
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  private List<Object> limits() {
    return List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun());
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text());
    } catch (Exception failure) { throw new AssertionError(failure); }
  }

  private static DraftInput input(String body, RequirementBodyBinding binding) {
    return withNativeAnchors(new DraftInput(new FlowInput(List.of(new InteractionInput("trigger", Direction.INBOUND,
        "Caller", "POST /legacy-body", null, null, null)), List.of()), List.of(fact(body, binding)),
        List.of(new CapabilityInput("trigger", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/legacy-body", null, null, null)), List.of(), new DraftSettings(false, null)));
  }

  private static FactInput fact(String body, RequirementBodyBinding binding) {
    return new FactInput("script-body", List.of("trigger"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Run the exact author script.", null, null,
        new RequirementElementIntent("script-1", "script", RequirementElementIntent.Placement.LOCAL_NODE,
            "trigger", null, "trigger", null, null, List.of(new PlanProperty("script", body)), List.of(), binding));
  }

  private static DraftUpdate update(FactInput fact) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(fact),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
  }

  private static String body(ChainPlanGraph graph) {
    return graph.nodes().stream().filter(node -> "script-1".equals(node.nodeId())).findFirst().orElseThrow()
        .properties().stream().filter(value -> "script".equals(value.key())).findFirst().orElseThrow().value();
  }

  private final class Harness {
    final String chat = "legacy-provider-" + UUID.randomUUID(), run = UUID.randomUUID().toString();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ProductPipelineArtifactStore artifacts = new ProductPipelineArtifactStore(nativeArtifacts);
    final ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, json, Clock.systemUTC());
    final RecoveryAttemptLedger ledger = new RecoveryAttemptLedger();
    final FailureNarrative narrative = mock(FailureNarrative.class);
    final LlmRunBudget budget = new LlmRunBudget(blobs, json, config);
    final AtomicInteger engineCalls = new AtomicInteger();
    final AtomicReference<RequestedElementValues.UnprovenBodyBinding> nativeFailure = new AtomicReference<>();
    final AtomicReference<CompilerDagExecutionResult> engineResult = new AtomicReference<>();
    final AtomicReference<RequirementBrief> finalBrief = new AtomicReference<>();
    final List<String> reopened = new ArrayList<>();
    final RequirementDraft legacy;
    final Revision legacyRevision;
    final RequirementBodyBinding binding;
    final FactInput correctedFact;
    final ChainPlanGraph graph;
    final ChainSemanticRevision semantic;
    final ResolvedCompilerDag dag;
    final RunManifest manifest;
    final DefaultCompilerDagExecutionEngine engine;
    final CreateChainTestOrchestrator runtime;

    Harness() {
      when(draftAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
      var turn = conversations.archive().beginInput(chat, SOURCE, null);
      conversations.archive().resolveInput(chat, turn, SOURCE);
      drafts.beginTurn(chat, SOURCE);
      // Restore an already admitted artifact serialized before bodyBinding existed.
      legacy = RequirementCaptureProjection.toDraft(input(BODY, null), null, List.of(), "legacy", "legacy");
      drafts.put(chat, legacy);
      legacyRevision = drafts.latestRevision(chat).orElseThrow();
      conversations.archive().consume(chat, turn.generation(), List.of(turn.turnId()), legacyRevision.artifactId());
      var restoredSources = new ConversationService(config, new ConversationArchive(nativeBlobs, json));
      var restoredArtifacts = new CompilationArtifacts(nativeBlobs, json, Clock.systemUTC());
      var reloaded = new RequirementDraftStore(restoredArtifacts, new CompilationSessions(nativeBlobs, json, Clock.systemUTC()), restoredSources);
      assertEquals(legacyRevision.reference(), reloaded.latestRevision(chat).orElseThrow().reference());
      assertEquals(legacy, reloaded.get(chat).orElseThrow());
      assertEquals(conversations.archive().read(chat), restoredSources.archive().read(chat));
      assertNull(reloaded.get(chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent().bodyBinding());
      var intent = legacy.authoredDraft().facts().getFirst().elementIntent();
      binding = RequirementBodySources.resolve(intent, List.of(intent), AuthorSourceEvidence.packet(conversations.archive().read(chat))).binding();
      assertEquals(RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY, binding.authority());
      correctedFact = fact(BODY, binding);
      graph = new ChainPlanGraph("1.0", new ChainSection("Legacy body", ""), List.of(
          new ChainPlanNode("trigger", "http-trigger", "Entry", null, null, List.of(
              new PlanProperty("httpMethodRestrict", "POST"), new PlanProperty("contextPath", "/legacy-body"),
              new PlanProperty("externalRoute", "false"), new PlanProperty("accessControlType", "NONE"))),
          new ChainPlanNode("script-1", "script", "Prepare", null, null, List.of())),
          List.of(new ChainPlanEdge("e1", "trigger", "script-1", null)));
      var provenance = new SemanticProvenance(List.of());
      semantic = new ChainSemanticRevision(ChainSemanticRevision.CURRENT_SCHEMA_VERSION, "legacy-revision", "Legacy body",
          CompilerContract.V1, List.of(new SemanticEntryPoint("trigger", "trigger", "script-1", 0, provenance, null)),
          List.of(new SemanticNode.Trigger("trigger", "http-trigger", provenance),
              new SemanticNode.Operation("script-1", "script", provenance)), List.of(),
          List.of(new SemanticExecutionEdge("e1", "trigger", "script-1", null, new SemanticRoute.Sequence(), null)),
          List.of(), List.of(), List.of(), List.of(), List.of());
      var nodes = new ArrayList<ResolvedCompilerNode>();
      nodes.add(node("cip-chain-assembler", List.of("CHAIN_STRUCTURE"), List.of("GRAPH_ASSEMBLY_RESULT"), List.of(), "graph-assembly", 0));
      int order = 1;
      for (String id : CompilerValidationPipeline.validatorSkillIds()) {
        nodes.add(node(id, List.of("GRAPH_ASSEMBLY_RESULT"), List.of("PRE_BUILD_VALIDATION"), List.of("cip-chain-assembler"), id, order++));
      }
      dag = new ResolvedCompilerDag(nodes, List.of(), "legacy-dag");
      var pin = new CompilerRunPin("compiler", "1", "package-hash", 1, "1", "catalog-hash", dag,
          nodes.stream().map(ResolvedCompilerNode::skillId).toList(), Map.of(), Map.of(), List.of(),
          null, null, null, null, null, null);
      manifest = new RunManifest(run, null, List.of(), "product", "legacy-body", "1", "profile-hash", "baseline",
          "baseline-hash", List.of(), "closure", new KnowledgePackageRef("knowledge", "1", "1", "checksum", "CERTIFIED", "certificate"),
          "2026.1", List.of(), pin);
      var registry = mock(CompilerNodeExecutionAdapterRegistry.class);
      var noop = mock(CompilerNodeExecutionAdapter.class);
      when(noop.execute(any(), any())).thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));
      when(registry.require(anyString())).thenReturn(noop);
      var pack = mock(CipKnowledgePackRepository.class);
      when(pack.activeVersion()).thenReturn(new CipKnowledgePackVersion("1", "1"));
      var digest = new CanonicalGraphDigest(json);
      engine = new DefaultCompilerDagExecutionEngine(new InMemorySkillWorkspaceStore(new ChainPlanStore()),
          mock(SkillExecutorRegistry.class), registry, pack, new GraphPatchExecutionContextStore(),
          new GraphPatchArtifactFactory(digest), new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier()),
          digest, new GraphAssemblyService(digest), validation, artifacts, new ChainPlanGraphValidator(schemas),
          new ClasspathCompilerContractRepository(), null);

      var firstDiscovery = new AtomicInteger();
      var sourceOwner = capability(DISCOVERY, context -> firstDiscovery.getAndIncrement() == 0
          ? success(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, legacy, context.inputRefs())) : discovery.execute(context));
      var analysis = capability("legacy-analysis", context -> {
        var draft = drafts.get(chat).orElseThrow();
        var draftRef = artifacts.latest(run, Kind.REQUIREMENT_DRAFT).orElseThrow().reference();
        var brief = brief(draftRef, draft);
        finalBrief.set(brief);
        return success(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of(draftRef)));
      });
      var execute = capability("legacy-execution", context -> {
        var brief = (RequirementBrief) context.attributes().get("requirementBrief");
        var inputs = adapterInputs(brief);
        var runner = mock(ApprovedCompilerExecutionRunner.class);
        when(runner.execute(any(), any(), anyList(), any(), anyString(), any())).thenAnswer(call -> executeEngine(brief));
        var binder = mock(ExecutorCatalogBindingAdapter.class);
        when(binder.resolve(anyString(), any(), anyList(), any())).thenReturn(List.of());
        var adapter = new CipDesignExecutorJavaAdapter(runner, binder, artifacts, planValidator);
        var result = adapter.executeAfterApproval(inputs, context.attemptId(), (id, status) -> {});
        if (engineResult.get() != null) {
          assertEquals(StageOutcomeClass.CANDIDATE, result.outcomeClass(), () -> result.message()
              + "\nNative compiler passes: " + engineResult.get().validationBundle());
        }
        if (result.recoveryCause() != null) {
          assertEquals(RecoveryCauseCode.BRIEF_CONTRACT, result.recoveryCause().causeCode());
          assertEquals("BODY_BINDING_REQUIRED", result.recoveryCause().findings().getFirst().code());
        }
        var mapped = result.outcomeClass() == StageOutcomeClass.CANDIDATE ? StageOutcomeClass.SUCCEEDED : result.outcomeClass();
        return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(mapped,
            result.candidates().stream().filter(candidate -> candidate.kind() == Kind.CHAIN_PLAN_GRAPH).toList(),
            result.message(), null, result.recoveryCause())));
      });
      var draftType = new ArtifactTypeRef("requirement-draft", 2);
      var briefType = new ArtifactTypeRef("requirement-brief", 1);
      var profile = new ProductPipelineProfile(1, "legacy-body", "1", List.of(), List.of(
          new ProfileStage(DISCOVERY, DISCOVERY, List.of(), List.of(draftType), null, null, new RetryPolicy(0, 0)),
          new ProfileStage("requirement-analysis", analysis.capabilityId(), List.of(draftType), List.of(briefType), null, null, new RetryPolicy(0, 0)),
          new ProfileStage("design-execution", execute.capabilityId(), List.of(briefType), List.of(new ArtifactTypeRef("chain-plan-graph", 1)), null, null, new RetryPolicy(0, 0))),
          new TerminalPolicy("design-execution", "PLAN_APPROVED"), List.of());
      var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts,
          new StageCapabilityRegistry(List.of(sourceOwner, analysis, execute)), Clock.systemUTC())
          .compilerRunPinResolver(new CompilerRunPinResolver(new CompilerPipelineIndex(2,
              new CipKnowledgePackVersion("1", "1"), null, List.of(),
              new CompilerPackageIdentity("compiler", "1", pin.compilerPackageDigest()), Map.of(),
              nodes.stream().map(value -> new CompilerPipelineNode(value.skillId(), value.compilerPhase(), value.generatorId(),
                  value.consumes(), value.produces(), value.dependsOn(), value.captureTool(), value.applicabilitySignals(),
                  value.readinessSignals(), value.runtimeReady(), value.runtimeReadinessFindings(), null, null,
                  value.topologicalLevel(), value.stableTieBreaker(), value.mandatory(), value.executionMode(), value.adapterId())).toList(), List.of())))
          .failureNarrative(narrative).recoveryLedger(ledger).build().withLlmBudget(budget)
          .withNativeRequirementCapture(drafts, feedback);
      runtime = new CreateChainTestOrchestrator(support, runs);
      ((ProductPipelineStageExecutor) runtime.stageExecutor()).withConversations(conversations);
      support.bootstrap(new StartOrResumeCommand(chat, run, profile, manifest), "legacy-body-flow");
    }

    JsonNode nativeState() {
      return json.valueToTree(Map.of("draft", drafts.latestRevision(chat).orElseThrow(), "source", conversations.archive().read(chat),
          "attempts", nativeArtifacts.history(sessions.active(chat), Kind.REQUIREMENT_CAPTURE_ATTEMPT)));
    }

    JsonNode update(FactInput fact, String id) throws Exception {
      var registered = new RequirementCaptureToolProvider(tools, json).provideTools(null);
      return json.readTree(registered.toolExecutorByName("updateRequirementDraft").execute(ToolExecutionRequest.builder()
          .id(id).name("updateRequirementDraft").arguments(json.writeValueAsString(Map.of("changes", RequirementBodyLegacyRepairProviderTest.update(fact)))).build(), chat));
    }

    void assertRejected(FactInput fact, String id, String code) throws Exception {
      var result = update(fact, id);
      assertFalse(result.path("accepted").asBoolean(), result.toString());
      assertTrue(result.path("issues").toString().contains(code), result.toString());
      assertEquals(legacyRevision.reference(), drafts.latestRevision(chat).orElseThrow().reference());
    }

    ChainPlanGraph boundGraph() { return graph.withNodeProperty("script-1", "script", BODY); }

    RequirementDraft completed() {
      return RequirementCaptureProjection.toDraft(input(BODY, binding), legacy, legacy.catalogBindings(), "diagnostic", "diagnostic");
    }

    RequirementBrief brief(Reference reference, RequirementDraft draft) {
      return RequirementBriefProjector.project(new RequirementBrief("Run the retained script", List.of(), List.of(), List.of(), List.of(), "Legacy body",
          reference.artifactId(), draft.planningText(), draft.facts()).withFlow(draft.flow()));
    }

    CompilerDagExecutionResult executeEngine(RequirementBrief brief) {
      engineCalls.incrementAndGet();
      try {
        var result = engine.execute(new CompilerDagExecutionRequest(run, chat, manifest, brief, semantic, dag,
            List.of(), List.of(), CompilerExecutionSeed.forCreate(chat, brief, semantic, graph, List.of()), null),
            (id, status) -> {}).await().indefinitely();
        engineResult.set(result);
        return result;
      } catch (RequestedElementValues.UnprovenBodyBinding failure) {
        nativeFailure.set(failure);
        throw failure;
      }
    }

    void assertMetadataOnly() {
      var admitted = drafts.get(chat).orElseThrow().authoredDraft();
      assertEquals(input(BODY, binding), admitted);
      assertEquals(legacy.catalogBindings(), drafts.get(chat).orElseThrow().catalogBindings());
      assertEquals(legacy.flow(), drafts.get(chat).orElseThrow().flow());
      assertEquals(BODY, admitted.facts().getFirst().elementIntent().requestedProperties().getFirst().value());
    }

    void assertEvidence() {
      var revision = artifacts.latest(run, Kind.RECOVERY_EVIDENCE).orElseThrow();
      var evidence = artifacts.payload(revision, RecoveryEvidence.class);
      assertEquals("BRIEF_CONTRACT", evidence.observedCauseCode());
      assertEquals("design-execution", evidence.observingStageId());
      assertEquals(DISCOVERY, evidence.producerStageId());
      assertNotNull(evidence.approvedBriefRef());
      var brief = artifacts.get(run, evidence.approvedBriefRef()).orElseThrow();
      assertEquals(evidence.approvedBriefRef().contentHash(), brief.contentHash());
      assertTrue(revision.inputs().contains(brief.reference()));
      var draft = artifacts.history(run, Kind.REQUIREMENT_DRAFT).stream()
          .filter(value -> artifacts.payload(brief, RequirementBrief.class).approvedDraftReference().equals(value.artifactId())).findFirst().orElseThrow();
      assertTrue(revision.inputs().contains(draft.reference()));
      assertEquals(legacy, artifacts.payload(draft, RequirementDraft.class));
      assertTrue(json.valueToTree(evidence).toString().contains("BODY_BINDING_REQUIRED"));
      assertTrue(json.valueToTree(evidence).toString().contains("script-body"));
      var finding = evidence.findings().stream().filter(value -> "BODY_BINDING_REQUIRED".equals(value.code())).findFirst().orElseThrow();
      try {
        assertEquals(json.valueToTree(nativeFailure.get().finding()), json.readTree(finding.rawValidatorJson()));
      } catch (Exception failure) { throw new AssertionError(failure); }
    }

    void install(ScriptedStreamingChatModel script) {
      var boundary = boundary(script, budget);
      doAnswer(call -> {
        boundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
        return null;
      }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    void complete() {
      for (int i = 0; i < 12 && document().run().status() != RunStatus.PLAN_APPROVED; i++) {
        var result = runtime.stageExecutor().execute(run, document().run().currentStageId()).await().indefinitely();
        if (result.decision() instanceof StageDecision.ReopenProducer reopen) reopened.add(reopen.producerStageId());
        assertFalse(result.decision() instanceof StageDecision.WaitForInput, result.decision().toString());
        assertFalse(result.decision() instanceof StageDecision.Fail, result.decision().toString());
        runtime.support().applyStageLifecycle(run, result).collect().asList().await().indefinitely();
      }
      assertEquals(RunStatus.PLAN_APPROVED, document().run().status());
    }

    ProductPipelineRunDocument document() { return runs.load(run).orElseThrow(); }

    CipDesignExecutorJavaAdapter.ExecutionInputs adapterInputs(RequirementBrief brief) {
      var briefRef = artifacts.latest(run, Kind.REQUIREMENT_BRIEF).orElseThrow().reference();
      var revisionRef = append(Kind.CHAIN_SEMANTIC_REVISION, semantic, List.of(briefRef));
      var ids = new IdsDocument("1", IdsDocument.Mode.PROVIDED, briefRef.artifactId(), briefRef.contentHash(), "flow-hash", "test-renderer", "# Retained body\n");
      var idsRef = append(Kind.IDS_DOCUMENT, ids, List.of(briefRef));
      var report = new DesignPlanReport("1", "# Retained body\n");
      var reportRef = append(Kind.DESIGN_PLAN_REPORT, report, List.of(revisionRef));
      var steps = new ArrayList<DesignExecutionPlan.Step>();
      int ordinal = 1;
      for (var node : dag.nodes()) steps.add(new DesignExecutionPlan.Step("step-" + ordinal, ordinal++, node.skillId(),
          DesignExecutionPlan.OwnerKind.SKILL, List.of(node.skillId()), List.of(), List.of(), List.of(), List.of(), List.of(), node.produces()));
      var plan = new DesignExecutionPlan("1", semantic.revisionId(), "test-planner", revisionRef.artifactId(), "input-hash", "2026.1",
          ApprovalPolicy.CATALOG_FIRST_V1, steps, reportRef.artifactId(), reportRef.contentHash(), Map.of(), Map.of(),
          manifest.compilerRunPin().pipelineIndexDigest(), ApprovalPolicy.CATALOG_FIRST_V1_HASH);
      var planRef = append(Kind.DESIGN_EXECUTION_PLAN, plan, List.of(reportRef, revisionRef));
      var implementation = new ImplementationPlan("Run the unchanged author body.");
      var implementationRef = append(Kind.IMPLEMENTATION_PLAN, implementation, List.of(planRef));
      var manifestRef = append(Kind.RUN_MANIFEST, manifest, List.of());
      var approval = new ApprovalRecordV2(implementationRef, implementationRef.contentHash(),
          List.of(idsRef, revisionRef, reportRef, planRef, implementationRef), "test-author", "Approved fixture plan", Instant.now(),
          ApprovalPolicy.CATALOG_FIRST_V1, ApprovalPolicy.CATALOG_FIRST_V1_HASH, null, null, null, null, null, null);
      var approvalRef = append(Kind.APPROVAL_RECORD, approval, approval.approvedCandidates());
      return new CipDesignExecutorJavaAdapter.ExecutionInputs(run, chat, approvalRef, approval, report, reportRef, plan, planRef,
          semantic, revisionRef, ids, idsRef, implementation, implementationRef, manifest, manifestRef, List.of(), null, null, null);
    }

    Reference append(Kind kind, Object payload, List<Reference> inputs) {
      String schema = kind == Kind.CHAIN_SEMANTIC_REVISION ? ChainSemanticRevision.CURRENT_SCHEMA_VERSION
          : kind == Kind.APPROVAL_RECORD ? "2" : "1";
      return artifacts.append(new CompilationArtifacts.AppendCommand(run, kind, schema, "legacy-test", "1", payload, inputs, null,
          new ArtifactProvenance(run, "design-execution", "legacy-body", "1", "profile-hash", "legacy-execution", "1", "closure"))).reference();
    }
  }

  private RateLimitStreamingChatModel boundary(ScriptedStreamingChatModel script, LlmRunBudget budget) {
    try {
      var constructor = RateLimitStreamingChatModel.class.getDeclaredConstructor(dev.langchain4j.model.chat.StreamingChatModel.class,
          AppConfig.class, RateLimitBackoffSleeper.class, RateLimitErrorClassifier.class, RateLimitWaitPolicy.class);
      constructor.setAccessible(true);
      var boundary = constructor.newInstance(script, config, (RateLimitBackoffSleeper) seconds -> {
        throw new AssertionError("Unexpected transport retry");
      }, new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
      var bind = RateLimitStreamingChatModel.class.getDeclaredMethod("withRunBudget", LlmRunBudget.class);
      bind.setAccessible(true);
      return (RateLimitStreamingChatModel) bind.invoke(boundary, budget);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static ResolvedCompilerNode node(String id, List<String> consumes, List<String> produces,
      List<String> dependencies, String adapter, int order) {
    return new ResolvedCompilerNode(id, "Assembly", null, consumes, produces, dependencies, null, List.of(), List.of(),
        true, List.of(), order, 0, true, CompilerNodeExecutionMode.JAVA_ADAPTER, adapter);
  }

  private static StageCapability capability(String id, Function<StageExecutionContext, Multi<CapabilitySignal>> run) {
    return new StageCapability() {
      public String capabilityId() { return id; }
      public Multi<CapabilitySignal> execute(StageExecutionContext context) { return run.apply(context); }
    };
  }

  private static Multi<CapabilitySignal> success(ArtifactCandidate candidate) {
    return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.SUCCEEDED, List.of(candidate), "Saved", null)));
  }
}

package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.AttributeReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MappingAction;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

class MappingGenerationPipelineTest {

  private static final String COMPILATION_ID = "conv-map-pipeline";
  private static final String TRANSFORMATION_SKILL = "cip-transformation-generator";
  private static final String SCRIPT_SKILL = "cip-script-generator";
  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

  private CompilationArtifacts artifacts;
  private MappingGenerationPipeline pipeline;
  private JsonNode orderSchema;

  @BeforeEach
  void setUp() throws Exception {
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            MAPPER,
            Clock.fixed(Instant.parse("2026-07-30T09:00:00Z"), ZoneOffset.UTC));
    pipeline = new MappingGenerationPipeline(artifacts, MAPPER, contextBuilder());
    orderSchema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } },
              "required": ["orderId"]
            }
            """);
  }

  static boolean mapper2Enabled() {
    return MappingMechanismSelector.mapper2Enabled();
  }

  @Test
  @EnabledIf("mapper2Enabled")
  void identityMapper2FakeCapturePassesValidator() {
    persistSide("trigger-http", MappingPort.OUTPUT, orderSchema);
    persistSide("call-1", MappingPort.REQUEST, orderSchema);
    MappingIntent intent = identityOrderId();
    GraphPatchExecutionContext context = sampleContext(List.of(pinnedSourceRef()));

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            TRANSFORMATION_SKILL,
            revisionWith(intent),
            List.of(binding()),
            context);

    assertFalse(prepared.blocked());
    assertFalse(prepared.mappingGenerationContext().isBlank());
    assertTrue(prepared.context().consumedArtifacts().contains(pinnedSourceRef()));
    assertFalse(prepared.envelopeRefs().isEmpty());

    AtomicBoolean skillCalled = new AtomicBoolean();
    MappingEnvelope envelope = loadEnvelope(prepared.envelopeRefs().getFirst());
    MappingDescriptionDocument captured = fakeMapper2Skill(envelope, skillCalled);

    new MappingCaptureValidator().validateMapper2(envelope, intent, captured);
    assertTrue(skillCalled.get());
  }

  @Test
  void prepareContinuesWhenPersistedSideIsAbsent() {
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "", "commandType", "Set to completeTask.", MappingRuleStatus.USER_DEFINED)));

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            SCRIPT_SKILL,
            revisionWith(intent),
            List.of(binding()),
            sampleContext(List.of()));

    assertFalse(prepared.blocked(), prepared.blockedMessage());
    assertTrue(artifacts.history(COMPILATION_ID, Kind.MAPPING_SCHEMA_SIDE).isEmpty());
  }

  @Test
  void unresolvedRequiredDoesNotCallMappingGeneratorSkill() {
    persistSide("trigger-http", MappingPort.OUTPUT, orderSchema);
    persistSide("call-1", MappingPort.REQUEST, orderSchema);
    MappingIntent unresolved =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of());
    AtomicBoolean skillCalled = new AtomicBoolean();

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            SCRIPT_SKILL,
            revisionWith(unresolved),
            List.of(binding()),
            sampleContext(List.of()));

    assertTrue(prepared.blocked());
    assertTrue(
        prepared.blockedMessage().startsWith(BriefMappingValidator.UNRESOLVED_REQUIRED_PREFIX));
    if (!prepared.blocked()) {
      fakeMapper2Skill(loadEnvelope(prepared.envelopeRefs().getFirst()), skillCalled);
    }
    assertFalse(skillCalled.get());
  }

  @Test
  @EnabledIf("mapper2Enabled")
  void chainedMapperOutputReusesPriorEnvelopeBySiteNodeId() throws Exception {
    JsonNode looseOrder =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } }
            }
            """);
    persistSide("trigger-http", MappingPort.OUTPUT, looseOrder);
    persistSide("call-a", MappingPort.REQUEST, looseOrder);
    persistSide("call-b", MappingPort.REQUEST, looseOrder);
    MappingIntent downstream =
        new MappingIntent(
            "map-second",
            "site-map-first",
            MappingPort.OUTPUT,
            "node-call-b",
            MappingPort.REQUEST,
            List.of(),
            "MAPPER_2");
    MappingIntent upstream =
        new MappingIntent(
            "map-first",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call-a",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)),
            "MAPPER_2");
    ChainPlanGraph graph = chainedMapperGraph();
    GraphPatchExecutionContext context =
        new GraphPatchExecutionContext(
            "run-chained",
            TRANSFORMATION_SKILL,
            "req-1",
            "graph-1",
            "compiler-1",
            "24.4",
            new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            graph,
            GraphPatchOwnershipPolicy.denyAll(),
            "");

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            TRANSFORMATION_SKILL,
            chainedMapperRevision(List.of(downstream, upstream)),
            List.of(
                binding("node-call-a", "call-a", "op-a"),
                binding("node-call-b", "call-b", "op-b")),
            context);

    assertFalse(prepared.blocked(), prepared.blockedMessage());
    assertEquals(2, prepared.envelopeRefs().size());
    MappingEnvelope first = loadEnvelope(prepared.envelopeRefs().getFirst());
    MappingEnvelope second = loadEnvelope(prepared.envelopeRefs().get(1));
    assertEquals("map-first", first.mappingIntentId());
    assertEquals("map-second", second.mappingIntentId());
  }

  @Test
  void scriptEchoOfOffHopProcessIdDoesNotBlockScriptGenerator() throws Exception {
    JsonNode salesforceResponse =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "id": { "type": "string" } },
              "required": ["id"]
            }
            """);
    JsonNode omResult =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "processId": { "type": "string" } }
            }
            """);
    persistSide("call-a", MappingPort.RESPONSE, salesforceResponse);
    persistSide("call-b", MappingPort.REQUEST, omResult);
    MappingIntent intent =
        new MappingIntent(
            "map-result",
            "node-call-a",
            MappingPort.RESPONSE,
            "node-call-b",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.processInstanceId",
                    "$.processId",
                    null,
                    MappingRuleStatus.PROPOSED)));

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            SCRIPT_SKILL,
            chainedMapperRevision(List.of(intent)),
            List.of(
                binding("node-call-a", "call-a", "op-a"),
                binding("node-call-b", "call-b", "op-b")),
            sampleContext(List.of()));

    assertFalse(prepared.blocked(), prepared.blockedMessage());
    assertTrue(prepared.mappingGenerationContext().contains("Replace the complete script body"));
    assertTrue(
        prepared
            .mappingGenerationContext()
            .contains("Successful Groovy compilation is not semantic equivalence"));
  }

  @Test
  void unresolvedRequiredBlocksScriptGeneratorForEmptyRules() {
    persistSide("trigger-http", MappingPort.OUTPUT, orderSchema);
    persistSide("call-1", MappingPort.REQUEST, orderSchema);
    MappingIntent unresolved =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of());

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            SCRIPT_SKILL,
            revisionWith(unresolved),
            List.of(binding()),
            sampleContext(List.of()));

    assertTrue(prepared.blocked());
    assertTrue(
        prepared.blockedMessage().startsWith(BriefMappingValidator.UNRESOLVED_REQUIRED_PREFIX));
  }

  @Test
  void emptyIntentsWithCompleteTaskRendersBehaviorContext() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithCompleteTask();
    assertTrue(revision.mappingIntents().isEmpty());
    GraphPatchExecutionContext context =
        completeTaskContext(completeTaskGraph(), completeTaskBrief());

    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(COMPILATION_ID, SCRIPT_SKILL, revision, List.of(), context);

    assertFalse(prepared.blocked(), prepared.blockedMessage());
    String rendered = prepared.mappingGenerationContext();
    assertFalse(rendered.isBlank(), "empty mapping intents must not skip behavior script context");
    assertTrue(rendered.contains(SemanticFixtures.COMPLETE_TASK_NODE_ID), rendered);
    assertTrue(rendered.contains("commandType=completeTask"), rendered);
    assertTrue(rendered.contains("script"), rendered);
    assertFalse(rendered.contains("mappingIntentId:"), rendered);
    assertTrue(
        prepared.context().editTargetNodeIds().contains(SemanticFixtures.COMPLETE_TASK_NODE_ID),
        prepared.context().editTargetNodeIds().toString());
    assertTrue(revision.mappingIntents().isEmpty());
    assertTrue(prepared.envelopeRefs().isEmpty());
  }

  @Test
  void emptyIntentsWithoutBehaviorScriptsKeepEmptyContext() {
    MappingGenerationPipeline.Result prepared =
        pipeline.prepare(
            COMPILATION_ID,
            SCRIPT_SKILL,
            SemanticFixtures.linearOrders(),
            List.of(),
            sampleContext(List.of()));

    assertFalse(prepared.blocked(), prepared.blockedMessage());
    assertTrue(prepared.mappingGenerationContext().isBlank());
    assertTrue(prepared.envelopeRefs().isEmpty());
  }

  @Test
  void productionDoesNotShipMappingCodegenPhases() throws Exception {
    Path root = Path.of("src/main/java");
    if (!Files.isDirectory(root)) {
      root = Path.of("ai-service/src/main/java");
    }
    assertTrue(Files.isDirectory(root), "production sources not found at " + root.toAbsolutePath());
    List<String> forbidden =
        List.of(
            "ScriptConfigurationPhase.java",
            "SimpleScriptExecutor.java",
            "MappingFlowExecutor.java",
            "Mapper2ConfigurationPhase.java",
            "SimpleMapper2Executor.java");
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> leftovers =
          walk.filter(path -> forbidden.contains(path.getFileName().toString())).toList();
      assertTrue(leftovers.isEmpty(), "deleted mapping codegen still present: " + leftovers);
    }
  }

  private MappingEnvelope loadEnvelope(Reference reference) {
    return artifacts.payload(
        artifacts.get(COMPILATION_ID, reference).orElseThrow(), MappingEnvelope.class);
  }

  private static MappingDescriptionDocument fakeMapper2Skill(
      MappingEnvelope envelope, AtomicBoolean skillCalled) {
    skillCalled.set(true);
    return new MappingDescriptionDocument(
        envelope.source(), envelope.target(), List.of(), List.of(identityAction(envelope)));
  }

  private static MappingAction identityAction(MappingEnvelope envelope) {
    AttributeReference source = attributeRef(envelope.idToPath(), "$.orderId");
    AttributeReference target = attributeRef(envelope.idToPath(), "$.orderId");
    return new MappingAction("action-order-id", List.of(source), target, null);
  }

  private static AttributeReference attributeRef(Map<String, String> idToPath, String jsonPath) {
    List<String> pathIds = new ArrayList<>();
    for (Map.Entry<String, String> entry : idToPath.entrySet()) {
      if (jsonPath.equals(entry.getValue())) {
        pathIds.add(entry.getKey());
      }
    }
    if (pathIds.isEmpty()) {
      pathIds.add("missing-" + jsonPath.replace("$.", ""));
    }
    return new AttributeReference("body", pathIds);
  }

  private void persistSide(String serviceCallId, MappingPort direction, JsonNode schema) {
    artifacts.append(
        new AppendCommand(
            COMPILATION_ID,
            Kind.MAPPING_SCHEMA_SIDE,
            "1",
            "test",
            "1",
            new MappingSchemaSide(
                "1",
                serviceCallId,
                "op-1",
                direction,
                "application/json",
                null,
                "sha-test",
                "test-provenance",
                schema),
            List.of(),
            null));
  }

  private static MappingIntent identityOrderId() {
    return new MappingIntent(
        "map-init",
        "trigger-http",
        MappingPort.OUTPUT,
        "node-call",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)));
  }

  private static ChainSemanticRevision revisionWith(MappingIntent intent) {
    return SemanticFixtures.linear(
        "Orders",
        "revision-orders",
        "trigger-http",
        "node-call",
        "call-1",
        "createOrder",
        "Orders API",
        List.of(intent),
        List.of());
  }

  private static ResolvedServiceCallBinding binding() {
    return binding("node-call", "call-1", "op-1");
  }

  private static ResolvedServiceCallBinding binding(
      String targetNodeId, String serviceCallId, String operationId) {
    return new ResolvedServiceCallBinding(
        targetNodeId,
        serviceCallId,
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        operationId,
        "http",
        "POST",
        "/orders",
        "createOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-" + serviceCallId,
        "");
  }

  private static ChainPlanGraph chainedMapperGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("id", "name"),
        List.of(
            new ChainPlanNode("trigger-http", "http-trigger", "Trigger", null, 1, List.of()),
            new ChainPlanNode(
                "site-map-first",
                "mapper-2",
                "Map first",
                null,
                2,
                List.of(new PlanProperty("mappingIntentId", "map-first"))),
            new ChainPlanNode(
                "site-map-second",
                "mapper-2",
                "Map second",
                null,
                3,
                List.of(new PlanProperty("mappingIntentId", "map-second"))),
            new ChainPlanNode("node-call-a", "service-call", "Call A", null, 4, List.of()),
            new ChainPlanNode("node-call-b", "service-call", "Call B", null, 5, List.of())),
        List.of());
  }

  private static ChainSemanticRevision chainedMapperRevision(List<MappingIntent> intents) {
    ChainSemanticRevision base = SemanticFixtures.linearOrders();
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "site-map-first", "mapper-2", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-call-a", "call-a", "createOrder", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-call-b", "call-b", "createTask", new SemanticProvenance(List.of()))),
        base.regions(),
        base.executionEdges(),
        base.containment(),
        intents,
        base.constraints(),
        base.assumptions(),
        base.citations());
  }

  private static Reference pinnedSourceRef() {
    return new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief");
  }

  private static GraphPatchExecutionContext completeTaskContext(
      ChainPlanGraph graph, RequirementBrief brief) {
    return new GraphPatchExecutionContext(
        "run-1",
        SCRIPT_SKILL,
        "req-1",
        "graph-1",
        "compiler-1",
        "24.4",
        brief,
        List.of(),
        graph,
        GraphPatchOwnershipPolicy.denyAll(),
        "");
  }

  private static ChainPlanGraph completeTaskGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("id", "Orders"),
        List.of(
            new ChainPlanNode("trigger-http", "http-trigger", "Trigger", null, 1, List.of()),
            new ChainPlanNode(
                SemanticFixtures.COMPLETE_TASK_NODE_ID,
                "script",
                "completeTask",
                null,
                2,
                List.of()),
            new ChainPlanNode("node-call", "service-call", "Call", null, 3, List.of())),
        List.of());
  }

  private static RequirementBrief completeTaskBrief() {
    return new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary")
        .withFacts(
            List.of(
                new RequirementFact(
                    SemanticFixtures.COMPLETE_TASK_FACT_ID,
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.BEHAVIOR,
                    "",
                    "Respond with commandType=completeTask")));
  }

  private static GraphPatchExecutionContext sampleContext(List<Reference> consumed) {
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection("id", "name"), List.of(), List.of());
    return new GraphPatchExecutionContext(
        "run-1",
        TRANSFORMATION_SKILL,
        "req-1",
        "graph-1",
        "compiler-1",
        "24.4",
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
        consumed,
        graph,
        GraphPatchOwnershipPolicy.denyAll(),
        "");
  }

  private static CompilerSkillContextBuilder contextBuilder() {
    QipKnowledgePackRepository repository = Mockito.mock(QipKnowledgePackRepository.class);
    CompilerSkillAddonRepository addonRepository = Mockito.mock(CompilerSkillAddonRepository.class);
    when(addonRepository.loadForSkill(Mockito.anyString()))
        .thenReturn(CompilerSkillAddonContext.empty());
    when(repository.loadCompilerGeneratorSpecIndex())
        .thenReturn(new CompilerGeneratorSpecIndex(List.of()));
    when(repository.loadCompilerSkillCatalog()).thenReturn(new CompilerSkillCatalog(List.of()));
    return new CompilerSkillContextBuilder(
        MAPPER,
        repository,
        addonRepository,
        Mockito.mock(CompilerSkillRuntimeEligibility.class),
        Mockito.mock(KnowledgeClient.class),
        Mockito.mock(KnowledgeContextProvider.class));
  }
}

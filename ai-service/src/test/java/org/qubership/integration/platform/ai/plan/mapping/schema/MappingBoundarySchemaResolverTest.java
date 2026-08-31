package org.qubership.integration.platform.ai.plan.mapping.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.NullType;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class MappingBoundarySchemaResolverTest {

  private static final String COMPILATION_ID = "comp-1";
  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

  private CompilationArtifacts artifacts;
  private MappingBoundarySchemaResolver resolver;
  private JsonNode orderSchema;

  @BeforeEach
  void setUp() throws Exception {
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            MAPPER,
            Clock.fixed(Instant.parse("2026-07-30T09:00:00Z"), ZoneOffset.UTC));
    resolver = new DefaultMappingBoundarySchemaResolver(artifacts, COMPILATION_ID, MAPPER);
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

  @Test
  void edgeMappedIntentResolvesTriggerOutputAndCallRequest() {
    persistSide(
        "trigger-http",
        null,
        MappingPort.OUTPUT,
        "application/json",
        null,
        orderSchema);
    persistSide("call-1", "op-1", MappingPort.REQUEST, "application/json", null, orderSchema);
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "edge-1",
            MappingPort.OUTPUT,
            "edge-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "customerId", null)));

    MappingBoundarySchemas sides =
        resolver.resolve(
            SemanticFixtures.linearOrdersWithMapping(),
            List.of(binding("node-call", "call-1", "op-1")),
            intent,
            Map.of());

    assertEquals(MappingPort.OUTPUT, sides.source().direction());
    assertEquals(MappingPort.REQUEST, sides.target().direction());
    assertEquals("application/json", sides.target().mediaType());
  }

  @Test
  void mappingOnTriggerToScriptEdgeWalksDownstreamToTheCall() {
    persistSide(
        "trigger-http",
        null,
        MappingPort.OUTPUT,
        "application/json",
        null,
        orderSchema);
    persistSide("call-1", "op-1", MappingPort.REQUEST, "application/json", null, orderSchema);
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "edge-trigger-script",
            MappingPort.OUTPUT,
            "edge-trigger-script",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "customerId", null)));

    MappingBoundarySchemas sides =
        resolver.resolve(
            triggerScriptCall(),
            List.of(binding("node-call", "call-1", "op-1")),
            intent,
            Map.of());

    assertEquals(MappingPort.OUTPUT, sides.source().direction());
    assertEquals(MappingPort.REQUEST, sides.target().direction());
  }

  @Test
  void triggerOutputToCallRequestUsesInboundThenOperationRequest() {
    persistSide(
        "trigger-http",
        null,
        MappingPort.OUTPUT,
        "application/json",
        null,
        orderSchema);
    persistSide("call-1", "op-1", MappingPort.REQUEST, "application/json", null, orderSchema);
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null)));

    MappingBoundarySchemas sides =
        resolver.resolve(
            SemanticFixtures.linearOrders(),
            List.of(binding("node-call", "call-1", "op-1")),
            intent,
            Map.of());

    assertEquals(MappingPort.OUTPUT, sides.source().direction());
    assertEquals(MappingPort.REQUEST, sides.target().direction());
    assertEquals("application/json", sides.target().mediaType());
  }

  @Test
  void callAResponseToCallBRequestUsesNamedStatusAndContentType() {
    persistSide("call-a", "op-a", MappingPort.RESPONSE, "application/json", "201", orderSchema);
    persistSide("call-b", "op-b", MappingPort.REQUEST, "application/json", null, orderSchema);
    MappingIntent intent =
        new MappingIntent(
            "map-ab",
            "call-a",
            MappingPort.RESPONSE,
            "call-b",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null)));

    MappingBoundarySchemas sides =
        resolver.resolve(twoCalls(), twoCallBindings(), intent, Map.of());

    assertEquals("201", sides.source().responseCode());
    assertEquals("application/json", sides.source().mediaType());
    assertEquals(MappingPort.REQUEST, sides.target().direction());
  }

  @Test
  void ambiguousResponseStatusFailsClosed() {
    persistSide("call-a", "op-a", MappingPort.RESPONSE, "application/json", "200", orderSchema);
    persistSide("call-a", "op-a", MappingPort.RESPONSE, "application/json", "201", orderSchema);
    persistSide("call-b", "op-b", MappingPort.REQUEST, "application/json", null, orderSchema);
    MappingIntent intent =
        new MappingIntent(
            "map-ab",
            "call-a",
            MappingPort.RESPONSE,
            "call-b",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null)));
    ChainSemanticRevision revision = twoCalls();
    List<ResolvedServiceCallBinding> bindings = twoCallBindings();

    assertThrows(
        IllegalStateException.class,
        () -> resolver.resolve(revision, bindings, intent, Map.of()));
  }

  @Test
  void mapperOutputReusesPriorEnvelopeTarget() {
    persistSide("call-1", "op-1", MappingPort.REQUEST, "application/json", null, orderSchema);
    MessageSchema emptySchema = new MessageSchema(List.of(), List.of(), new NullType());
    MappingEnvelope prior =
        new MappingEnvelope(emptySchema, emptySchema, Map.of(), "digest-ab12");
    MappingIntent intent =
        new MappingIntent(
            "map-next",
            "mapper-1",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null)));

    MappingBoundarySchemas sides =
        resolver.resolve(
            withMapper(SemanticFixtures.linearOrders()),
            List.of(binding("node-call", "call-1", "op-1")),
            intent,
            Map.of("mapper-1", prior));

    assertTrue(sides.source().provenance().startsWith("envelope:"));
    assertTrue(sides.source().provenance().contains(prior.digest()));
  }

  @Test
  void triggerResponseUsesPersistedAsyncRequestSchema() {
    persistSide(
        "call-om-result",
        "op-result",
        MappingPort.REQUEST,
        "application/json",
        null,
        orderSchema);
    persistSide("call-sf", "op-create", MappingPort.REQUEST, "application/json", null, orderSchema);
    persistSide(
        "call-sf", "op-create", MappingPort.RESPONSE, "application/json", "201", orderSchema);
    MappingIntent intent =
        new MappingIntent(
            "response-createTask-to-onTaskResult",
            "trigger-on-task-result",
            MappingPort.RESPONSE,
            "node-sf",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")));

    MappingBoundarySchemas sides =
        resolver.resolve(omResultTriggerToSalesforce(), omResultBindings(), intent, Map.of());

    assertEquals("application/json", sides.source().mediaType());
    assertEquals("call-om-result", sides.source().serviceCallId());
    assertEquals(MappingPort.REQUEST, sides.target().direction());
  }

  @Test
  void missingSchemaFailsClosed() {
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null)));
    ChainSemanticRevision revision = SemanticFixtures.linearOrders();
    List<ResolvedServiceCallBinding> noBindings = List.of();

    assertThrows(
        IllegalStateException.class,
        () -> resolver.resolve(revision, noBindings, intent, Map.of()));
  }

  private void persistSide(
      String serviceCallId,
      String operationId,
      MappingPort direction,
      String contentType,
      String responseCode,
      JsonNode schema) {
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
                operationId,
                direction,
                contentType,
                responseCode,
                "sha-test",
                "test-provenance",
                schema),
            List.of(),
            null));
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

  private static List<ResolvedServiceCallBinding> twoCallBindings() {
    return List.of(binding("node-a", "call-a", "op-a"), binding("node-b", "call-b", "op-b"));
  }

  private static List<ResolvedServiceCallBinding> omResultBindings() {
    return List.of(
        binding("trigger-on-task-result", "call-om-result", "op-result"),
        binding("node-sf", "call-sf", "op-create"));
  }

  private static ChainSemanticRevision omResultTriggerToSalesforce() {
    ChainSemanticRevision base = SemanticFixtures.linearOrders();
    return new ChainSemanticRevision(
        base.schemaVersion(),
        "revision-om-sf",
        base.chainIdentity(),
        base.compilerContractVersion(),
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                "trigger-on-task-result",
                "node-sf",
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation("OM onTaskResult", null))),
        List.of(
            new SemanticNode.Trigger(
                "trigger-on-task-result",
                "async-api-trigger",
                new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-sf",
                "call-sf",
                "createTask",
                new SemanticProvenance(List.of("fact-sf")))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-result",
                "trigger-on-task-result",
                "node-sf",
                null,
                new SemanticRoute.Sequence(),
                "response-createTask-to-onTaskResult")),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision triggerScriptCall() {
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
            new SemanticNode.Operation("script-req", "script", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-call", "call-1", "createOrder", new SemanticProvenance(List.of("fact-call")))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-trigger-script",
                "trigger-http",
                "script-req",
                null,
                new SemanticRoute.Sequence(),
                "map-init"),
            new SemanticExecutionEdge(
                "edge-script-call",
                "script-req",
                "node-call",
                null,
                new SemanticRoute.Sequence(),
                null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision twoCalls() {
    ChainSemanticRevision base = SemanticFixtures.linearOrders();
    return new ChainSemanticRevision(
        base.schemaVersion(),
        "revision-ab",
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-a", "call-a", "getA", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-b", "call-b", "getB", new SemanticProvenance(List.of()))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision withMapper(ChainSemanticRevision base) {
    List<SemanticNode> nodes = new ArrayList<>(base.nodes());
    nodes.add(
        new SemanticNode.Operation("mapper-1", "mapper-2", new SemanticProvenance(List.of())));
    return new ChainSemanticRevision(
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
  }
}

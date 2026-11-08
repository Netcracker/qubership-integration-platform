package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.converter.stream.InputStreamCache;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.DefaultMappingBoundarySchemaResolver;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingCondition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

class DirectFieldMappingScriptTest {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  private static final String COMPILATION = "copy-provenance";
  private CompilationArtifacts artifacts;
  private DefaultMappingBoundarySchemaResolver resolver;
  private MappingSchemaSide event;
  private MappingSchemaSide request;
  private MappingSchemaSide response;
  private MappingSchemaSide result;
  private MappingIntent requestIntent;
  private MappingIntent resultIntent;

  @BeforeEach
  void setUp() throws Exception {
    artifacts = new CompilationArtifacts(new InMemoryArtifactBlobStore(), JSON, Clock.systemUTC());
    resolver = new DefaultMappingBoundarySchemaResolver(artifacts, COMPILATION);
    event = side("node-in", MappingPort.OUTPUT, "event-hash", """
        {"type":"object","properties":{"title":{"type":"string"},"processInstanceId":{"type":"string"}}}
        """);
    request = side("createTask", MappingPort.REQUEST, "request-hash", """
        {"type":"object","properties":{"Subject":{"type":"string"}},"required":["Subject"]}
        """);
    response = side("createTask", MappingPort.RESPONSE, "response-hash", """
        {"type":"object","properties":{"processInstanceId":{"type":"string"}}}
        """);
    result = side("taskResult", MappingPort.REQUEST, "result-hash", """
        {"type":"object","properties":{"processId":{"type":"string"}},"required":["processId"]}
        """);
    requestIntent = new MappingIntent("request-map", "onTaskStart", MappingPort.OUTPUT,
        "createTask", MappingPort.REQUEST, List.of(rule("title", "Subject", "onTaskStart", MappingPort.OUTPUT,
            event, request)));
    resultIntent = new MappingIntent("result-map", "createTask", MappingPort.RESPONSE,
        "taskResult", MappingPort.REQUEST, List.of(rule("processInstanceId", "processId", "onTaskStart",
            MappingPort.OUTPUT, event, result)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = MappingCondition.When.class, mode =
      org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE, names = "ALWAYS")
  void conditionalCopyCannotPassFieldValidation(MappingCondition.When when) throws Exception {
    MappingIntent conditional = withCondition(requestIntent, new MappingCondition(when, "createTask"));
    assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.validateFields(conditional,
        Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), event), request),
        () -> "Field validation must reject unsupported condition " + when);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = MappingCondition.When.class, mode =
      org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE, names = "ALWAYS")
  void conditionalCopyCannotPrepareANativePlan(MappingCondition.When when) throws Exception {
    MappingIntent conditional = withCondition(requestIntent, new MappingCondition(when, "createTask"));
    assertThrows(IllegalArgumentException.class, () -> prepare(List.of(conditional)),
        () -> "Native planning must reject unsupported condition " + when);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = MappingCondition.When.class, mode =
      org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE, names = "ALWAYS")
  void aFrozenPlanCannotEraseAnApprovedCopyCondition(MappingCondition.When when) throws Exception {
    Map<String, CopyMappingPlan> plans = prepare(List.of(requestIntent));
    MappingIntent conditional = withCondition(requestIntent, new MappingCondition(when, "createTask"));
    MappingEnvelope frozen = envelope(event, request).withMappingIntentId(requestIntent.mappingIntentId())
        .withVerifiedCopyPlan(plans.get(requestIntent.mappingIntentId()));
    assertThrows(IllegalArgumentException.class, () -> DirectFieldMappingScript.from(conditional, frozen),
        () -> "Frozen-plan replay must not lower " + when + " as ALWAYS");
  }

  @Test
  void unconditionalCopyStillValidatesPlansAndExecutesAfterArtifactRoundTrip() throws Exception {
    MappingIntent unconditional = withCondition(requestIntent, MappingCondition.always());
    CopyMappingPlanner.validateFields(unconditional,
        Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), event), request);
    Map<String, CopyMappingPlan> plans = prepare(List.of(unconditional));
    assertEquals(Map.of("Subject", "ORIGINAL"),
        execute(unconditional, event, request, plans, new Exchange(Map.of("title", "ORIGINAL"))));
  }

  private static MappingIntent withCondition(MappingIntent intent, MappingCondition condition) throws Exception {
    MappingIntentRule before = intent.rules().getFirst();
    MappingIntentRule changed = new MappingIntentRule(before.id(), before.requirementIds(), before.targetPath(),
        before.value(), condition, before.status(), before.fieldRefs(), before.constants(), before.behavior(),
        before.missingSourcePolicy(), before.schemaPins());
    MappingIntent restored = JSON.readValue(JSON.writeValueAsBytes(intent.withRules(List.of(changed))), MappingIntent.class);
    assertAll("Persisted COPY condition",
        () -> assertEquals(condition.when(), restored.rules().getFirst().condition().when()),
        () -> assertEquals(condition.interactionId(), restored.rules().getFirst().condition().interactionId()));
    return restored;
  }

  @Test
  void aMissingConditionKindCannotValidatePlanOrReplayAsUnconditional() throws Exception {
    MappingIntent malformed = withCondition(requestIntent, new MappingCondition(null, "createTask"));
    CopyMappingPlan plan = prepare(List.of(requestIntent)).get(requestIntent.mappingIntentId());
    assertAll("Malformed condition admission",
        () -> assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.validateFields(malformed,
            Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), event), request)),
        () -> assertThrows(IllegalArgumentException.class, () -> prepare(List.of(malformed))),
        () -> assertThrows(IllegalArgumentException.class, () -> DirectFieldMappingScript.from(malformed,
            envelope(event, request).withVerifiedCopyPlan(plan))));
  }

  @Test
  void alwaysWithAnUnusedInteractionStillExecutesUnconditionally() throws Exception {
    MappingIntent unconditional = withCondition(requestIntent,
        new MappingCondition(MappingCondition.When.ALWAYS, "createTask"));
    assertEquals(Map.of("Subject", "ORIGINAL"), execute(unconditional, event, request,
        prepare(List.of(unconditional)), new Exchange(Map.of("title", "ORIGINAL"))));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"title.nested,Subject", "title,Subject.nested"})
  void nestedCopyPathsCannotPassFieldValidationOrPlanning(String sourcePath, String targetPath) {
    MappingIntent nested = requestIntent.withRules(List.of(rule(sourcePath, targetPath,
        "onTaskStart", MappingPort.OUTPUT, event, request)));
    assertAll("Unsupported nested COPY path",
        () -> assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.validateFields(nested,
            Map.of(origin("onTaskStart", MappingPort.OUTPUT, sourcePath), event), request)),
        () -> assertThrows(IllegalArgumentException.class, () -> prepare(List.of(nested))));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("unsupportedSourceSchemas")
  void unsupportedSourceSchemasCannotPassFieldValidationOrPlanning(String sourceSchema, String diagnostic) throws Exception {
    MappingSchemaSide source = rawSide("node-in", MappingPort.OUTPUT, "source-hash", sourceSchema);
    MappingIntent unsupported = requestIntent.withRules(List.of(rule("title", "Subject", "onTaskStart",
        MappingPort.OUTPUT, source, request)));
    assertAll("Unsupported COPY field schema",
        () -> assertEquals("Mapping origin is unresolved: " + diagnostic, assertThrows(IllegalArgumentException.class,
            () -> CopyMappingPlanner.validateFields(unsupported,
                Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), source), request)).getMessage()),
        () -> assertEquals("Mapping origin is unresolved: " + diagnostic, assertThrows(IllegalArgumentException.class,
            () -> prepareWithSides(unsupported, source, request)).getMessage()));
  }

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> unsupportedSourceSchemas() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("external reference",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"https://example.invalid/schema\"}}}"), "Mapping field schema is unknown or unsupported"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("dangling local reference",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"#/$defs/missing\"}}}"), "Mapping field schema is unknown or unsupported"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("cyclic local reference",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"#/$defs/loop\"}},\"$defs\":{\"loop\":{\"$ref\":\"#/$defs/loop\"}}}"), "Mapping field schema is unknown or unsupported"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("reference with sibling constraints",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"#/$defs/text\",\"maxLength\":5}},\"$defs\":{\"text\":{\"type\":\"string\"}}}"), "Mapping field schema is unknown or unsupported"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("allOf field",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"allOf\":[{\"type\":\"string\"}]}}}"), "Mapping field schema is unknown or unsupported"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("oneOf field",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}"), "Mapping field schema is unknown or unsupported"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("object field",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"object\",\"properties\":{\"nested\":{\"type\":\"string\"}}}}}"), "COPY requires known scalar field types"),
        org.junit.jupiter.params.provider.Arguments.of(org.junit.jupiter.api.Named.of("array field",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}"), "COPY requires known scalar field types"));
  }


  @Test
  void aLocalReferenceToAScalarValidatesAndExecutes() throws Exception {
    assertScalarCopyExecutes(
        "{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"#/$defs/text\"}},\"$defs\":{\"text\":{\"type\":\"string\"}}}",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\"}}}", "LOCAL_REF");
  }

  @Test
  void matchingSourceAndTargetConstraintsValidateAndExecute() throws Exception {
    assertScalarCopyExecutes(
        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"maxLength\":5}}}",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\",\"maxLength\":5}}}", "short");
  }

  @Test
  void frozenCatalogResponseIdCopiesToTaskSubjectWithDifferentExamples() throws Exception {
    var catalog = JSON.readTree(getClass().getResourceAsStream("/mapping/salesforce-createTask-schemas.json"));
    var sourceJson = catalog.path("responseSchemas").path("201").path("application/json");
    var targetJson = catalog.path("requestSchema").path("application/json");
    var source = rawSide("createTask", MappingPort.RESPONSE, "catalog-response", sourceJson.toString());
    var target = rawSide("taskResult", MappingPort.REQUEST, "catalog-request", targetJson.toString());
    var intent = resultIntent.withRules(List.of(rule("id", "Subject", "createTask", MappingPort.RESPONSE, source, target)));
    CopyMappingPlanner.validateFields(intent, Map.of(origin("createTask", MappingPort.RESPONSE, "id"), source), target);
    assertEquals(Map.of("Subject", "FIRST-ORIGIN"), execute(intent, source, target,
        prepareWithSides(intent, source, target), new Exchange(Map.of("id", "FIRST-ORIGIN"))));
  }

  @Test
  void differentOpenApiExamplesDoNotConstrainScalarCopy() throws Exception {
    assertScalarCopyExecutes(
        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"example\":\"task-123\"}}}",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\",\"example\":\"Follow up\"}}}", "actual-id");
  }

  @Test
  void examplesDoNotHideAStricterTargetConstraint() throws Exception {
    var source = rawSide("node-in", MappingPort.OUTPUT, "source-hash",
        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"example\":\"short\"}}}");
    var target = rawSide("createTask", MappingPort.REQUEST, "target-hash",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\",\"example\":\"short\",\"maxLength\":5}}}");
    assertScalarPairRejected(source, target, "COPY target constraints are not established by its source: maxLength");
  }

  @Test
  void aStricterTargetConstraintCannotPassFieldValidationOrPlanning() throws Exception {
    MappingSchemaSide source = rawSide("node-in", MappingPort.OUTPUT, "source-hash",
        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"maxLength\":5}}}");
    MappingSchemaSide target = rawSide("createTask", MappingPort.REQUEST, "target-hash",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"string\",\"maxLength\":4}}}");
    assertScalarPairRejected(source, target, "COPY target constraints are not established by its source: maxLength");
  }

  @Test
  void integerSourcesCanCopyToNumberTargetsWithoutConversion() throws Exception {
    assertScalarCopyExecutes(
        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"integer\"}}}",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"number\"}}}", 7);
  }

  @Test
  void numberSourcesCannotCopyToIntegerTargets() throws Exception {
    MappingSchemaSide source = rawSide("node-in", MappingPort.OUTPUT, "source-hash",
        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"number\"}}}");
    MappingSchemaSide target = rawSide("createTask", MappingPort.REQUEST, "target-hash",
        "{\"type\":\"object\",\"properties\":{\"Subject\":{\"type\":\"integer\"}}}");
    assertScalarPairRejected(source, target, "COPY source and target types are incompatible");
  }

  private void assertScalarCopyExecutes(String sourceSchema, String targetSchema, Object value) throws Exception {
    MappingSchemaSide source = rawSide("node-in", MappingPort.OUTPUT, "source-hash", sourceSchema);
    MappingSchemaSide target = rawSide("createTask", MappingPort.REQUEST, "target-hash", targetSchema);
    MappingIntent intent = requestIntent.withRules(List.of(rule("title", "Subject", "onTaskStart",
        MappingPort.OUTPUT, source, target)));
    CopyMappingPlanner.validateFields(intent, Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), source), target);
    assertEquals(Map.of("Subject", value), execute(intent, source, target,
        prepareWithSides(intent, source, target), new Exchange(Map.of("title", value))));
  }

  private void assertScalarPairRejected(MappingSchemaSide source, MappingSchemaSide target, String diagnostic) {
    MappingIntent intent = requestIntent.withRules(List.of(rule("title", "Subject", "onTaskStart",
        MappingPort.OUTPUT, source, target)));
    assertAll("Incompatible COPY schema pair",
        () -> assertEquals("Mapping origin is unresolved: " + diagnostic,
            assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.validateFields(intent,
                Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), source), target)).getMessage()),
        () -> assertEquals("Mapping origin is unresolved: " + diagnostic,
            assertThrows(IllegalArgumentException.class, () -> prepareWithSides(intent, source, target)).getMessage()));
  }

  private static Map<String, CopyMappingPlan> prepareWithSides(MappingIntent intent,
      MappingSchemaSide source, MappingSchemaSide target) {
    CompilationArtifacts isolated = new CompilationArtifacts(new InMemoryArtifactBlobStore(), JSON, Clock.systemUTC());
    for (MappingSchemaSide side : List.of(source, target)) {
      isolated.append(new CompilationArtifacts.AppendCommand(COMPILATION, CompilationArtifacts.Kind.MAPPING_SCHEMA_SIDE,
          "1", "test", "1", side, List.of(), null));
    }
    return CopyMappingPlanner.prepare(revision(), List.of(intent),
        new DefaultMappingBoundarySchemaResolver(isolated, COMPILATION), List.of(), Map.of());
  }

  @Test
  void legacyCopyWithoutConfirmedOriginCannotReachGeneration() throws Exception {
    MappingIntent legacy = new MappingIntent("legacy", "onTaskStart", MappingPort.OUTPUT,
        "createTask", MappingPort.REQUEST, List.of(new MappingIntentRule("title", "Subject", null)));
    assertThrows(IllegalArgumentException.class, () -> DirectFieldMappingScript.from(legacy, envelope(event, request)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"string", "number", "integer", "boolean", "nullable"})
  void localOutputRetainsEverySupportedScalarSchema(String type) throws Exception {
    String shape = "nullable".equals(type) ? "[\"string\",\"null\"]" : "\"" + type + "\"";
    var source = rawSide("entry", MappingPort.OUTPUT, "source", """
        {"type":"object","properties":{"value":{"type":%s}}}
        """.formatted(shape));
    var origin = origin("entry", MappingPort.OUTPUT, "value");
    var rule = new MappingIntentRule("value", List.of("fact"), "$.result", new MappingValue.Copy(origin),
        MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR");
    var intent = new MappingIntent("local", "call", MappingPort.RESPONSE, "response", MappingPort.OUTPUT,
        List.of(rule));
    var output = LocalMappingTarget.outputSchema(intent, Map.of(origin, source));
    assertEquals(source.schema().path("properties").path("value"), output.schema().path("properties").path("result"));
    assertEquals(output.sha256(), LocalMappingTarget.outputSchema(intent, Map.of(origin, source)).sha256());
  }

  @Test
  void localOutputResolvesSourceFieldReferences() throws Exception {
    var source = rawSide("entry", MappingPort.OUTPUT, "source", """
        {"type":"object","properties":{"value":{"$ref":"#/$defs/text"}},
         "$defs":{"text":{"type":"string","maxLength":12}}}
        """);
    var origin = origin("entry", MappingPort.OUTPUT, "value");
    var rule = new MappingIntentRule("value", List.of("fact"), "$.result", new MappingValue.Copy(origin),
        MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR");
    var intent = new MappingIntent("local", "call", MappingPort.RESPONSE, "response", MappingPort.OUTPUT,
        List.of(rule));
    var output = LocalMappingTarget.outputSchema(intent, Map.of(origin, source));
    assertEquals(JSON.readTree("{\"type\":\"string\",\"maxLength\":12}"),
        output.schema().path("properties").path("result"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"object", "array"})
  void localOutputRejectsUnsupportedNativeCopySourceTypes(String type) throws Exception {
    var source = rawSide("entry", MappingPort.OUTPUT, "source", """
        {"type":"object","properties":{"value":{"type":"%s"}}}
        """.formatted(type));
    var origin = origin("entry", MappingPort.OUTPUT, "value");
    var rule = new MappingIntentRule("value", List.of("fact"), "$.result", new MappingValue.Copy(origin),
        MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR");
    var intent = new MappingIntent("local", "call", MappingPort.RESPONSE, "response", MappingPort.OUTPUT,
        List.of(rule));
    assertThrows(IllegalArgumentException.class, () -> LocalMappingTarget.outputSchema(intent, Map.of(origin, source)));
  }

  @Test
  void localResponseCombinesThreeOriginalBodiesAfterRetentionInsertion() throws Exception {
    JsonNode eventSchema = event.schema();
    JsonNode orderSchema = JSON.readTree("""
        {"type":"object","properties":{"orderId":{"type":"string"},"orderStatus":{"type":"string"}}}
        """);
    JsonNode paymentSchema = JSON.readTree("""
        {"type":"object","properties":{"paymentId":{"type":"string"},"paymentStatus":{"type":"string"}}}
        """);
    artifacts = new CompilationArtifacts(new InMemoryArtifactBlobStore(), JSON, Clock.systemUTC());
    event = side("onTaskStart", MappingPort.REQUEST, schemaHash(eventSchema), eventSchema.toString());
    var order = side("orders", MappingPort.RESPONSE, schemaHash(orderSchema), orderSchema.toString());
    var payment = side("payments", MappingPort.RESPONSE, schemaHash(paymentSchema), paymentSchema.toString());
    var fields = List.of(
        new MappingIntentRule("client", List.of("fact-client"), "$.client",
            new MappingValue.Copy(origin("onTaskStart", MappingPort.OUTPUT, "title")),
            MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR"),
        new MappingIntentRule("correlation", List.of("fact-correlation"), "$.correlationId",
            new MappingValue.Copy(origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId")),
            MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR"),
        new MappingIntentRule("order", List.of("fact-order"), "$.orderId",
            new MappingValue.Copy(origin("orders", MappingPort.RESPONSE, "orderId")),
            MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR"),
        new MappingIntentRule("order-status", List.of("fact-order-status"), "$.orderStatus",
            new MappingValue.Copy(origin("orders", MappingPort.RESPONSE, "orderStatus")),
            MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR"),
        new MappingIntentRule("payment", List.of("fact-payment"), "$.paymentId",
            new MappingValue.Copy(origin("payments", MappingPort.RESPONSE, "paymentId")),
            MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR"),
        new MappingIntentRule("payment-status", List.of("fact-payment-status"), "$.paymentStatus",
            new MappingValue.Copy(origin("payments", MappingPort.RESPONSE, "paymentStatus")),
            MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR"));
    var local = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("response", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        "onTaskStart", null, "payments", null, List.of(), List.of());
    var declaration = new RequirementFact("return", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.FIELD_MAPPING, "", "Return the combined values.", null, null,
        null, null, null, null, List.of("onTaskStart"), null, local);
    var flow = new RequirementFlow(List.of(
        new RequirementFlow.Interaction("onTaskStart", RequirementFlow.Direction.INBOUND, "Caller", "POST /", ""),
        new RequirementFlow.Interaction("orders", RequirementFlow.Direction.OUTBOUND, "Orders", "POST /", ""),
        new RequirementFlow.Interaction("payments", RequirementFlow.Direction.OUTBOUND, "Payments", "POST /", "")),
        List.of(new RequirementFlow.Transition("onTaskStart", "orders"),
            new RequirementFlow.Transition("orders", "payments")));
    var unpinned = new MappingIntent("local-result", "payments", MappingPort.RESPONSE,
        "response", MappingPort.OUTPUT, fields);
    var origins = Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), event,
        origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId"), event,
        origin("orders", MappingPort.RESPONSE, "orderId"), order,
        origin("orders", MappingPort.RESPONSE, "orderStatus"), order,
        origin("payments", MappingPort.RESPONSE, "paymentId"), payment,
        origin("payments", MappingPort.RESPONSE, "paymentStatus"), payment);
    var target = LocalMappingTarget.outputSchema(unpinned, origins);
    var schemaMaps = Map.of(
        "op-onTaskStart", new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps(
            "op-onTaskStart", Map.of("application/json", eventSchema), Map.of()),
        "op-orders", new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps(
            "op-orders", Map.of(), Map.of("200", Map.of("application/json", orderSchema))),
        "op-payments", new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps(
            "op-payments", Map.of(), Map.of("200", Map.of("application/json", paymentSchema))));
    var loader = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(loader.load(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> schemaMaps.get(invocation.getArgument(0)));
    var unpinnedBrief = new RequirementBrief("Return", List.of(), List.of(), List.of(), List.of(), "Return")
        .withFlow(flow).withFacts(List.of(declaration)).withMappingIntents(List.of(unpinned))
        .withCatalogBindings(List.of(testBinding("onTaskStart"), testBinding("orders"), testBinding("payments")));
    var pinning = new org.qubership.integration.platform.ai.plan.BriefCopyMappingValidator(loader, JSON)
        .validateAndPin(unpinnedBrief);
    assertEquals(List.of(), pinning.issues());
    var brief = pinning.brief();
    var intent = brief.mappingIntents().getFirst();
    assertEquals(target.sha256(), intent.rules().getFirst().schemaPins().get("target"));
    var revision = new ChainSemanticRevision(ChainSemanticRevision.CURRENT_SCHEMA_VERSION, "revision", "chain", "1",
        List.of(), List.of(new SemanticNode.Trigger("node-in", "onTaskStart", "async-api-trigger", null),
            new SemanticNode.ServiceCall("node-orders", "orders", "orders", null),
            new SemanticNode.ServiceCall("node-payments", "payments", "payments", null),
            new SemanticNode.Operation("response", "script", null)), List.of(), List.of(
            new SemanticExecutionEdge("e1", "node-in", "node-orders", null, null, null),
            new SemanticExecutionEdge("e2", "node-orders", "node-payments", null, null, null),
            new SemanticExecutionEdge("e3", "node-payments", "response", null, null, "local-result")),
        List.of(), List.of(), List.of(), List.of(), List.of());
    var graph = new ChainPlanGraph("1.0", null, List.of(
        new ChainPlanNode("node-in", "async-api-trigger", "Entry", null, null, List.of()),
        new ChainPlanNode("node-orders", "service-call", "Orders", null, null, List.of()),
        new ChainPlanNode("node-payments", "service-call", "Payments", null, null, List.of()),
        new ChainPlanNode("response", "script", "Response", null, null,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "local-result")))),
        List.of(new ChainPlanEdge("e1", "node-in", "node-orders", null),
            new ChainPlanEdge("e2", "node-orders", "node-payments", null),
            new ChainPlanEdge("e3", "node-payments", "response", null)));
    graph = CopyRetentionStructurePhase.place(graph, List.of(intent),
        (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision, ref, port));
    assertEquals(2, graph.nodes().stream().filter(CopyMappingPlanner::nativeRetentionSite).count());
    var localResolver = new DefaultMappingBoundarySchemaResolver(artifacts, COMPILATION,
        Map.of("node-in", "onTaskStart"), brief);
    assertEquals(target, localResolver.resolve(revision, List.of(), intent, Map.of()).target());
    var plans = CopyMappingPlanner.prepare(revision, List.of(intent), localResolver, List.of(),
        Map.of("response", target), graph);
    var builder = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder.class,
        invocation -> invocation.getMethod().getReturnType() == String.class ? ""
            : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    var pipeline = new MappingGenerationPipeline(artifacts, JSON, builder);
    var context = new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
        "run", "cip-script-generator", "brief", "graph", "compiler", "1", brief, List.of(), graph,
        org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy.denyAll(), "");
    var triggerBinding = new ResolvedServiceCallBinding("node-in", "onTaskStart", "INTEGRATION",
        "system", "group", "spec", "op-onTaskStart", "http", "POST", "/", "trigger",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG, "1", "evidence", "");
    var prepared = pipeline.prepare(COMPILATION, "cip-script-generator", revision, List.of(triggerBinding), context);
    assertEquals(false, prepared.blocked(), prepared.blockedMessage());
    var envelopes = new LinkedHashMap<String, MappingEnvelope>();
    for (var stored : artifacts.history(COMPILATION, CompilationArtifacts.Kind.MAPPING_ENVELOPE)) {
      var envelope = artifacts.payload(stored, MappingEnvelope.class);
      envelopes.put(envelope.mappingIntentId(), envelope);
    }
    assertEquals(target.sha256(), envelopes.get("local-result").verifiedCopyPlan().reads().getFirst().targetSchemaDigest());
    var materializedNodes = new java.util.ArrayList<ChainPlanNode>();
    for (var node : graph.nodes()) {
      String planId = CopyMappingPlanner.nativeRetentionSite(node) ? node.nodeId()
          : MappingExecutionSite.mappingIntentId(node);
      var plan = planId == null ? null : plans.get(planId);
      if (plan == null) {
        materializedNodes.add(node);
        continue;
      }
      String body = plan.reads().isEmpty() ? DirectFieldMappingScript.saveOnly(plan).script()
          : DirectFieldMappingScript.from(intent, envelopes.get(intent.mappingIntentId())).orElseThrow().script();
      var properties = new java.util.ArrayList<>(node.properties());
      properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body));
      materializedNodes.add(new ChainPlanNode(node.nodeId(), node.type(), node.label(), node.parentNodeId(),
          node.order(), properties));
    }
    var materialized = new ChainPlanGraph(graph.schemaVersion(), graph.chain(), materializedNodes, graph.edges());
    new CopyMappingMaterializationValidator(artifacts).validate(COMPILATION, brief, revision, materialized);
    var exchange = new Exchange(Map.of("title", "original-client", "processInstanceId", "original-correlation"));
    for (var entry : List.of(origin("onTaskStart", MappingPort.OUTPUT, "title"),
        origin("orders", MappingPort.RESPONSE, "orderId"))) {
      if ("orders".equals(entry.interactionId())) exchange.in.body = Map.of("orderId", "original-order", "orderStatus", "created");
      String save = DirectFieldMappingScript.saveOnly(plans.get(CopyMappingPlanner.retentionSiteId(entry))).script();
      SecureGroovyMappingCompiler.compile(save);
      new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(save);
    }
    exchange.in.body = Map.of("paymentId", "original-payment", "paymentStatus", "approved");
    assertEquals(Map.of("client", "original-client", "correlationId", "original-correlation", "orderId", "original-order",
        "orderStatus", "created", "paymentId", "original-payment", "paymentStatus", "approved"),
        execute(intent, payment, target, plans, exchange));
    side("orders", MappingPort.RESPONSE, "changed-orders-hash", """
        {"type":"object","properties":{"orderId":{"type":"string","maxLength":12}}}
        """);
    var changed = assertThrows(IllegalStateException.class,
        () -> new CopyMappingMaterializationValidator(artifacts).validate(COMPILATION, brief, revision, materialized));
    org.junit.jupiter.api.Assertions.assertTrue(changed.getMessage().contains("Conflicting schema digests"), changed.getMessage());
  }

  @Test
  void correctedCapturePinsAndExecutesItsLocalResponse() throws Exception {
    var unpinned = org.qubership.integration.platform.ai.plan.RequirementRevisionAdmissionTest.correctLocalResponseBrief();
    JsonNode sourceSchema = JSON.readTree("""
        {"type":"object","properties":{"status":{"type":"string"}}}
        """);
    var loader = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(loader.load("tasks-operation")).thenReturn(
        new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps("tasks-operation", Map.of(),
            Map.of("200", Map.of("application/json", sourceSchema))));
    var pinned = new org.qubership.integration.platform.ai.plan.BriefCopyMappingValidator(loader, JSON)
        .validateAndPin(unpinned);
    assertEquals(List.of(), pinned.issues());
    var brief = pinned.brief();
    var intent = brief.mappingIntents().getFirst();
    assertEquals("response", intent.targetRef());
    assertEquals(MappingPort.OUTPUT, intent.targetPort());
    var source = side("call", MappingPort.RESPONSE, schemaHash(sourceSchema), sourceSchema.toString());
    var revision = new ChainSemanticRevision(ChainSemanticRevision.CURRENT_SCHEMA_VERSION, "revision", "chain", "1",
        List.of(), List.of(new SemanticNode.Trigger("entry", "entry", "http-trigger", null),
            new SemanticNode.ServiceCall("call", "call", "tasks", null),
            new SemanticNode.Operation("response", "script", null)), List.of(), List.of(
            new SemanticExecutionEdge("entry-call", "entry", "call", null, null, null),
            new SemanticExecutionEdge("call-response", "call", "response", null, null, intent.mappingIntentId())),
        List.of(), List.of(), List.of(), List.of(), List.of());
    var graph = new ChainPlanGraph("1.0", null, List.of(
        new ChainPlanNode("entry", "http-trigger", "Entry", null, null, List.of()),
        new ChainPlanNode("call", "service-call", "Call", null, null, List.of()),
        new ChainPlanNode("response", "script", "Response", null, null,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", intent.mappingIntentId())))),
        List.of(new ChainPlanEdge("entry-call", "entry", "call", null),
            new ChainPlanEdge("call-response", "call", "response", null)));
    graph = CopyRetentionStructurePhase.placeApproved(graph, brief,
        (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision, ref, port));
    var builder = org.mockito.Mockito.mock(org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder.class,
        invocation -> invocation.getMethod().getReturnType() == String.class ? ""
            : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    var context = new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext(
        "run", "cip-script-generator", "brief", "graph", "compiler", "1", brief, List.of(), graph,
        org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy.denyAll(), "");
    var prepared = new MappingGenerationPipeline(artifacts, JSON, builder)
        .prepare(COMPILATION, "cip-script-generator", revision, List.of(), context);
    assertEquals(false, prepared.blocked(), prepared.blockedMessage());
    var envelope = artifacts.payload(artifacts.history(COMPILATION, CompilationArtifacts.Kind.MAPPING_ENVELOPE)
        .getFirst(), MappingEnvelope.class);
    var body = DirectFieldMappingScript.from(intent, envelope).orElseThrow().script();
    var nodes = graph.nodes().stream().map(node -> "response".equals(node.nodeId())
        ? new ChainPlanNode(node.nodeId(), node.type(), node.label(), node.parentNodeId(), node.order(),
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", intent.mappingIntentId()),
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", body))) : node).toList();
    var materialized = new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
    new CopyMappingMaterializationValidator(artifacts).validate(COMPILATION, brief, revision, materialized);
    assertEquals(Map.of("status", "created"), execute(intent, source,
        new DefaultMappingBoundarySchemaResolver(artifacts, COMPILATION, Map.of(), brief)
            .resolve(revision, List.of(), intent, Map.of()).target(),
        Map.of(intent.mappingIntentId(), envelope.verifiedCopyPlan()), new Exchange(Map.of("status", "created"))));
  }

  @Test
  void earlierEventRenameSurvivesBodyReplacementAndSameNamedResponseField() {
    Map<String, CopyMappingPlan> plans = prepare(List.of(requestIntent, resultIntent));
    Exchange exchange = new Exchange(new LinkedHashMap<>(Map.of("title", "Create task", "processInstanceId", "ORIGINAL")));
    assertEquals(Map.of("Subject", "Create task"), execute(requestIntent, event, request, plans, exchange));
    exchange.in.body = Map.of("processInstanceId", "RESPONSE_SENTINEL");
    assertEquals(Map.of("processId", "ORIGINAL"), execute(resultIntent, response, result, plans, exchange));
    assertEquals(java.util.Set.of(CopyMappingPlanner.contextId(origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId")),
        CopyMappingPlanner.contextId(new MappingSource.Message("onTaskStart", MappingPort.OUTPUT, "$"))),
        exchange.properties.keySet());
  }

  @Test
  void retainedRuntimeJsonUsesTheOriginalBodyAndChecksTheCatalogType() {
    var runtime = MappingSchemaSide.runtimeJson("onTaskStart");
    var copy = new MappingIntent(resultIntent.mappingIntentId(), resultIntent.sourceRef(), resultIntent.sourcePort(),
        resultIntent.targetRef(), resultIntent.targetPort(), List.of(
            rule("processInstanceId", "processId", "onTaskStart", MappingPort.OUTPUT, runtime, result)));
    var runtimeResolver = new DefaultMappingBoundarySchemaResolver(artifacts, COMPILATION) {
      @Override
      public MappingSchemaSide resolveOrigin(ChainSemanticRevision revision, List<ResolvedServiceCallBinding> bindings,
          String owner, MappingPort port, Map<String, MappingSchemaSide> outputs) {
        if ("onTaskStart".equals(owner) && port == MappingPort.OUTPUT) return runtime;
        return super.resolveOrigin(revision, bindings, owner, port, outputs);
      }
    };
    ChainPlanGraph graph = CopyRetentionStructurePhase.place(responseGraph(), List.of(copy),
        (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision(), ref, port));
    var plans = CopyMappingPlanner.prepare(revision(), List.of(copy), runtimeResolver, List.of(), Map.of(), graph);
    var origin = origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId");
    String saveScript = DirectFieldMappingScript.saveOnly(plans.get(CopyMappingPlanner.retentionSiteId(origin))).script();
    SecureGroovyMappingCompiler.compile(saveScript);
    for (Object original : List.of("inbound", 42)) {
      var exchange = new Exchange(Map.of("processInstanceId", original));
      new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(saveScript);
      exchange.in.body = Map.of("processInstanceId", "response");
      if (original instanceof String) {
        assertEquals(Map.of("processId", original), execute(copy, response, result, plans, exchange));
      } else {
        assertEquals("Invalid COPY source type: processInstanceId", assertThrows(IllegalArgumentException.class,
            () -> execute(copy, response, result, plans, exchange)).getMessage());
      }
    }
  }

  @Test
  void compilerPlacedSaveOnlySiteWorksWithoutARequestMapping() {
    ChainPlanGraph base = responseGraph();
    ChainPlanGraph graph = CopyRetentionStructurePhase.place(base, List.of(resultIntent),
        (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision(), ref, port));
    Map<String, CopyMappingPlan> plans = CopyMappingPlanner.prepare(revision(), List.of(resultIntent),
        resolver, List.of(), Map.of(), graph);
    CopyMappingPlan save = plans.get(CopyMappingPlanner.retentionSiteId(
        origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId")));
    Exchange exchange = new Exchange("{\"processInstanceId\":\"ORIGINAL_JSON\"}");
    Object original = exchange.in.body;
    String script = DirectFieldMappingScript.saveOnly(save).script();
    SecureGroovyMappingCompiler.compile(script);
    new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(script);
    assertEquals(original, exchange.in.body);
    exchange.in.body = Map.of("processInstanceId", "RESPONSE_SENTINEL");
    assertEquals(Map.of("processId", "ORIGINAL_JSON"), execute(resultIntent, response, result, plans, exchange));
  }

  @Test
  void copyKeepsTheWholeOriginForLaterBusinessBehavior() throws Exception {
    var current = resultIntent.withRules(List.of(rule("processInstanceId", "processId", "createTask",
        MappingPort.RESPONSE, response, result)));
    var original = new LinkedHashMap<String, Object>(Map.of("processInstanceId", "original", "status", "created"));
    var exchange = new Exchange(original);
    var plans = prepare(List.of(current));
    assertEquals(Map.of("processId", "original"), execute(current, response, result, plans, exchange));
    original.put("status", "mutated");
    exchange.in.body = Map.of("paymentId", "payment");
    var root = new MappingSource.Message("createTask", MappingPort.RESPONSE, "$");
    assertEquals(Map.of("processInstanceId", "original", "status", "created"),
        JSON.readValue(assertInstanceOf(String.class, exchange.getProperty(CopyMappingPlanner.contextId(root))), Map.class));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"before", "after", "foreign-call"})
  void copyUsesItsFrozenOriginAcrossEarlierProcessingButProtectsItsOutput(String placement) throws Exception {
    var current = resultIntent.withRules(List.of(rule("processInstanceId", "processId", "createTask",
        MappingPort.RESPONSE, response, result)));
    var base = responseGraph();
    var nodes = new java.util.ArrayList<>(base.nodes());
    nodes.add(new ChainPlanNode("processing", "foreign-call".equals(placement) ? "service-call" : "script",
        "Process business data", null, null, List.of()));
    var edges = new java.util.ArrayList<>(base.edges());
    var boundary = "after".equals(placement) ? edges.removeLast() : edges.remove(1);
    edges.add(new ChainPlanEdge(boundary.edgeId(), boundary.fromNodeId(), "processing", null));
    edges.add(new ChainPlanEdge("processing-out", "processing", boundary.toNodeId(), null));
    var graph = CopyRetentionStructurePhase.place(new ChainPlanGraph(base.schemaVersion(), base.chain(), nodes, edges),
        List.of(current), (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision(), ref, port));
    if (!"before".equals(placement)) {
      assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.prepare(revision(), List.of(current),
          resolver, List.of(), Map.of(), graph));
      return;
    }
    var plans = CopyMappingPlanner.prepare(revision(), List.of(current), resolver, List.of(), Map.of(), graph);
    var origin = origin("createTask", MappingPort.RESPONSE, "processInstanceId");
    var exchange = new Exchange(Map.of("processInstanceId", "original", "status", "created"));
    var save = DirectFieldMappingScript.saveOnly(plans.get(CopyMappingPlanner.retentionSiteId(origin))).script();
    SecureGroovyMappingCompiler.compile(save);
    new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(save);
    exchange.in.body = Map.of("processInstanceId", "intervening");
    assertEquals(Map.of("processId", "original"), execute(current, response, result, plans, exchange));
    var root = new MappingSource.Message("createTask", MappingPort.RESPONSE, "$");
    assertEquals(Map.of("processInstanceId", "original", "status", "created"),
        JSON.readValue(assertInstanceOf(String.class, exchange.getProperty(CopyMappingPlanner.contextId(root))), Map.class));
  }

  @Test
  void movedScriptCannotKeepItsApprovedOriginPlan() {
    ChainPlanGraph base = responseGraph();
    ChainPlanGraph moved = new ChainPlanGraph(base.schemaVersion(), base.chain(), base.nodes(),
        List.of(new ChainPlanEdge("early", "node-in", "response", null),
            new ChainPlanEdge("before-call", "response", "node-call", null),
            new ChainPlanEdge("after-call", "node-call", "node-result", null)));
    ChainPlanGraph graph = CopyRetentionStructurePhase.place(moved, List.of(resultIntent),
        (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision(), ref, port));
    assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.prepare(revision(), List.of(resultIntent),
        resolver, List.of(), Map.of(), graph));
  }

  @Test
  void currentResponseCopyUsesResponseInsteadOfRetainedEvent() {
    MappingIntent current = new MappingIntent("result-map", "createTask", MappingPort.RESPONSE,
        "taskResult", MappingPort.REQUEST, List.of(rule("processInstanceId", "processId", "createTask",
            MappingPort.RESPONSE, response, result)));
    Map<String, CopyMappingPlan> plans = prepare(List.of(current));
    Exchange exchange = new Exchange(Map.of("processInstanceId", "CURRENT"));
    assertEquals(Map.of("processId", "CURRENT"), execute(current, response, result, plans, exchange));
    assertEquals(Map.of(CopyMappingPlanner.contextId(new MappingSource.Message("createTask", MappingPort.RESPONSE, "$")),
        "{\"processInstanceId\":\"CURRENT\"}"), exchange.properties);
  }

  @Test
  void optionalAbsentSourceFailsInsteadOfWritingNull() {
    Map<String, CopyMappingPlan> plans = prepare(List.of(requestIntent, resultIntent));
    Exchange exchange = new Exchange(Map.of("title", "Create task"));
    assertThrows(IllegalArgumentException.class, () -> execute(requestIntent, event, request, plans, exchange));
    assertEquals(Map.of("title", "Create task"), exchange.in.body);
  }

  @Test
  void presentNullRequiresCompatibleNullableTypes() throws Exception {
    MappingSchemaSide nullableSource = rawSide("node-in", MappingPort.OUTPUT, "nullable-source", """
        {"type":"object","properties":{"title":{"type":["string","null"]}}}
        """);
    MappingSchemaSide nullableTarget = rawSide("createTask", MappingPort.REQUEST, "nullable-target", """
        {"type":"object","properties":{"Subject":{"type":["string","null"]}}}
        """);
    MappingIntent nullable = new MappingIntent("nullable", "onTaskStart", MappingPort.OUTPUT,
        "createTask", MappingPort.REQUEST, List.of(rule("title", "Subject", "onTaskStart", MappingPort.OUTPUT,
            nullableSource, nullableTarget)));
    MappingSource.Message origin = origin("onTaskStart", MappingPort.OUTPUT, "title");
    CopyMappingPlanner.validateFields(nullable, Map.of(origin, nullableSource), nullableTarget);
    CopyMappingPlan plan = new CopyMappingPlan(List.of(new CopyMappingPlan.Read("title", "Subject", origin,
        nullableSource.sha256(), nullableTarget.sha256(), null, true)), List.of());
    Map<String, Object> nullValue = new LinkedHashMap<>(); nullValue.put("title", null);
    Map<String, Object> expected = new LinkedHashMap<>(); expected.put("Subject", null);
    assertEquals(expected, execute(nullable, nullableSource, nullableTarget, Map.of("nullable", plan), new Exchange(nullValue)));
    assertThrows(IllegalArgumentException.class,
        () -> CopyMappingPlanner.validateFields(nullable, Map.of(origin, nullableSource), request));
  }

  @Test
  void missingOrCorruptedRetainedValueCannotReadTheReplacementBody() {
    Map<String, CopyMappingPlan> plans = prepare(List.of(requestIntent, resultIntent));
    Exchange exchange = new Exchange(Map.of("processInstanceId", "WRONG_CURRENT"));
    assertThrows(IllegalArgumentException.class, () -> execute(resultIntent, response, result, plans, exchange));
    exchange.properties.put(CopyMappingPlanner.contextId(origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId")), "{}");
    assertThrows(IllegalArgumentException.class, () -> execute(resultIntent, response, result, plans, exchange));
    assertEquals(Map.of("processInstanceId", "WRONG_CURRENT"), exchange.in.body);
  }

  @Test
  void changedSchemaPinCannotBeReused() {
    MappingIntent changed = new MappingIntent("request-map", "onTaskStart", MappingPort.OUTPUT,
        "createTask", MappingPort.REQUEST, List.of(requestIntent.rules().getFirst()
            .withSchemaPins(Map.of("source", "replaced-schema", "target", request.sha256()))));
    assertThrows(IllegalArgumentException.class, () -> prepare(List.of(changed)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
      "{\"type\":\"object\"}|Mapping path is permitted but its type is unknown: $.title",
      "{\"type\":\"object\",\"required\":[\"title\"]}|Mapping path is permitted but its type is unknown: $.title",
      "{\"type\":\"object\",\"additionalProperties\":false}|Mapping path is not permitted by the closed schema: $.title"})
  void unknownAndForbiddenPathsRemainDistinct(String schema, String diagnostic) throws Exception {
    MappingSchemaSide source = rawSide("node-in", MappingPort.OUTPUT, "unknown", schema);
    MappingIntent intent = requestIntent.withRules(List.of(rule("title", "Subject", "onTaskStart",
        MappingPort.OUTPUT, source, request)));
    assertEquals("Mapping origin is unresolved: " + diagnostic, assertThrows(IllegalArgumentException.class,
        () -> CopyMappingPlanner.validateFields(intent,
            Map.of(origin("onTaskStart", MappingPort.OUTPUT, "title"), source), request)).getMessage());
  }

  @Test
  void forwardOriginCannotBeReadBeforeItsProducer() {
    MappingIntent forward = new MappingIntent("request-map", "onTaskStart", MappingPort.OUTPUT,
        "createTask", MappingPort.REQUEST, List.of(rule("processInstanceId", "Subject", "createTask",
            MappingPort.RESPONSE, response, request)));
    assertThrows(IllegalArgumentException.class, () -> prepare(List.of(forward)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void branchOrCycleCannotEstablishOriginAvailability(boolean cycle) {
    ChainSemanticRevision base = revision();
    var edges = new java.util.ArrayList<>(base.executionEdges());
    edges.add(cycle ? new SemanticExecutionEdge("cycle", "node-result", "node-in", null, null, null)
        : new SemanticExecutionEdge("branch", "node-in", "node-result", null, null, null));
    ChainSemanticRevision unsupported = new ChainSemanticRevision(base.schemaVersion(), base.revisionId(),
        base.chainIdentity(), base.compilerContractVersion(), base.entryPoints(), base.nodes(), base.regions(), edges,
        base.containment(), base.mappingIntents(), base.constraints(), base.assumptions(), base.citations());
    assertThrows(IllegalArgumentException.class, () -> CopyMappingPlanner.prepare(unsupported,
        List.of(requestIntent), resolver, List.of(), Map.of()));
  }

  @Test
  void unknownPortCannotReadATriggerAsAServiceResponse() {
    MappingIntent wrong = requestIntent.withRules(List.of(rule("title", "Subject", "onTaskStart",
        MappingPort.RESPONSE, event, request)));
    assertThrows(IllegalArgumentException.class, () -> prepare(List.of(wrong)));
  }

  @Test
  void changedPersistedSchemaCannotReuseAnOlderDigest() throws Exception {
    side("node-in", MappingPort.OUTPUT, "changed-event", """
        {"type":"object","properties":{"title":{"type":"string"}}}
        """);
    assertThrows(IllegalStateException.class, () -> prepare(List.of(requestIntent)));
  }

  @Test
  void mixedUnsupportedRuleCannotProduceAPartialNativePlan() {
    MappingIntent mixed = requestIntent.withRules(List.of(requestIntent.rules().getFirst(),
        new MappingIntentRule("title", "Other", "uppercase")));
    assertThrows(IllegalArgumentException.class, () -> prepare(List.of(mixed, resultIntent)));
  }

  @Test
  void nonNullablePresentNullFailsBeforeReplacingBody() {
    Map<String, CopyMappingPlan> plans = prepare(List.of(requestIntent));
    Map<String, Object> source = new LinkedHashMap<>(); source.put("title", null);
    Exchange exchange = new Exchange(source);
    assertThrows(IllegalArgumentException.class, () -> execute(requestIntent, event, request, plans, exchange));
    assertEquals(source, exchange.in.body);
  }

  @Test
  void shouldCopyJsonFromCamelMessageRepresentations() throws Exception {
    String json = "{\"title\":\"Caf\u00e9\"}";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    var plans = prepare(List.of(requestIntent));
    try (var camel = new DefaultCamelContext()) {
      camel.start();
      for (Object body : List.of(Map.of("title", "Caf\u00e9"), json, new ByteArrayInputStream(bytes),
          new InputStreamCache(bytes))) {
        var exchange = new DefaultExchange(camel);
        exchange.getMessage().setBody(body);

        execute(requestIntent, event, request, plans, exchange);

        try (var outbound = exchange.getMessage().getMandatoryBody(InputStream.class)) {
          assertEquals(Map.of("Subject", "Caf\u00e9"), JSON.readValue(outbound, Map.class),
              body.getClass().getSimpleName());
        }
      }
    }
  }

  @Test
  void shouldKeepAStreamBodyReadableAfterSavingItsField() throws Exception {
    ChainPlanGraph graph = CopyRetentionStructurePhase.place(responseGraph(), List.of(resultIntent),
        (ref, port) -> DefaultMappingBoundarySchemaResolver.semanticNodeId(revision(), ref, port));
    var plans = CopyMappingPlanner.prepare(revision(), List.of(resultIntent), resolver, List.of(), Map.of(), graph);
    var origin = origin("onTaskStart", MappingPort.OUTPUT, "processInstanceId");
    var save = plans.get(CopyMappingPlanner.retentionSiteId(origin));
    String script = DirectFieldMappingScript.saveOnly(save).script();
    SecureGroovyMappingCompiler.compile(script);
    String json = "{\"processInstanceId\":\"before-call\"}";
    try (var camel = new DefaultCamelContext()) {
      camel.start();
      var exchange = new DefaultExchange(camel);
      exchange.getMessage().setBody(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

      new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(script);

      assertEquals(json, exchange.getMessage().getBody(String.class));
      assertEquals("{\"value\":\"before-call\"}", exchange.getProperty(CopyMappingPlanner.contextId(origin)));
    }
  }

  @Test
  void shouldRejectAJsonArrayInAStream() throws Exception {
    var plans = prepare(List.of(requestIntent));
    try (var camel = new DefaultCamelContext()) {
      camel.start();
      var exchange = new DefaultExchange(camel);
      exchange.getMessage().setBody(new InputStreamCache("[1]".getBytes(StandardCharsets.UTF_8)));

      var failure = assertThrows(IllegalArgumentException.class,
          () -> execute(requestIntent, event, request, plans, exchange));

      assertEquals("Expected a JSON object body", failure.getMessage());
    }
  }

  private static ChainPlanGraph responseGraph() {
    return new ChainPlanGraph("1.0", null,
        List.of(new ChainPlanNode("node-in", "async-api-trigger", "input", null, null, List.of()),
            new ChainPlanNode("node-call", "service-call", "create", null, null, List.of()),
            new ChainPlanNode("response", "script", "response", null, null,
                List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("mappingIntentId", "result-map"))),
            new ChainPlanNode("node-result", "service-call", "result", null, null, List.of())),
        List.of(new ChainPlanEdge("in-call", "node-in", "node-call", null),
            new ChainPlanEdge("call-response", "node-call", "response", null),
            new ChainPlanEdge("response-result", "response", "node-result", null)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"string", "number", "integer", "boolean"})
  void runtimeJsonCopyValidatesAgainstTheCatalogFieldBeforeReplacingTheBody(String type) throws Exception {
    var origin = origin("onTaskStart", MappingPort.OUTPUT, "title");
    var runtime = MappingSchemaSide.runtimeJson("onTaskStart");
    var catalogRequest = side("runtimeOrder", MappingPort.REQUEST, "runtime-request-hash",
        """
        {"type":"object","properties":{"Subject":{"type":"%s"}},"required":["Subject"]}
        """.formatted(type));
    var intent = new MappingIntent("request-map", "onTaskStart", MappingPort.OUTPUT, "runtimeOrder",
        MappingPort.REQUEST, List.of(rule("title", "Subject", "onTaskStart", MappingPort.OUTPUT, runtime, catalogRequest)));
    var flow = new RequirementFlow(
        List.of(new RequirementFlow.Interaction("onTaskStart", RequirementFlow.Direction.INBOUND,
                "Caller", "POST /orders", ""),
            new RequirementFlow.Interaction("runtimeOrder", RequirementFlow.Direction.OUTBOUND,
                "Orders", "POST /orders", "")),
        List.of(new RequirementFlow.Transition("onTaskStart", "runtimeOrder")));
    var brief = new RequirementBrief("Copy title", List.of(), List.of(), List.of(), List.of(), "Copy title")
        .withFacts(List.of(new RequirementFact("route", RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY, "http-trigger", "Receive an order.", "Caller", "POST /orders",
            null, "POST", "/orders", null, List.of("onTaskStart"))))
        .withFlow(flow).withMappingIntents(List.of(intent));
    var customRevision = new ChainSemanticRevision(ChainSemanticRevision.CURRENT_SCHEMA_VERSION,
        "revision", "chain", "1", List.of(),
        List.of(new SemanticNode.Trigger("custom-entry", "onTaskStart", "http-trigger", null),
            new SemanticNode.ServiceCall("node-call", "runtimeOrder", "create", null)),
        List.of(), List.of(new SemanticExecutionEdge("in-call", "custom-entry", "node-call", null, null, null)),
        List.of(), List.of(intent), List.of(), List.of(), List.of());
    var originResolver = new DefaultMappingBoundarySchemaResolver(artifacts, COMPILATION, Map.of(), brief);
    var schemas = originResolver.resolve(customRevision, List.of(), intent, Map.of());
    var plans = CopyMappingPlanner.prepare(customRevision, List.of(intent), originResolver, List.of(), Map.of());

    assertEquals(runtime, schemas.source());
    Object value = switch (type) {
      case "string" -> "Create task";
      case "number" -> 42.5;
      case "integer" -> 42.0;
      case "boolean" -> true;
      default -> throw new AssertionError(type);
    };
    Object wrongValue = switch (type) {
      case "string", "boolean" -> 42;
      case "integer" -> 42.5;
      default -> "42";
    };
    assertEquals(List.of(type), plans.get("request-map").reads().getFirst().runtimeTypes());
    assertEquals(Map.of("Subject", value),
        execute(intent, runtime, catalogRequest, plans, new Exchange(Map.of("title", value))));
    var wrongType = new Exchange(Map.of("title", wrongValue));
    assertEquals("Invalid COPY source type: title", assertThrows(IllegalArgumentException.class,
        () -> execute(intent, runtime, catalogRequest, plans, wrongType)).getMessage());
    assertEquals(Map.of("title", wrongValue), wrongType.in.body);
    assertEquals("Missing COPY source: title", assertThrows(IllegalArgumentException.class,
        () -> execute(intent, runtime, catalogRequest, plans, new Exchange(Map.of()))).getMessage());
  }

  @Test
  void legacyCopyReadRetainsItsArtifactShapeWithoutRuntimeChecks() throws Exception {
    var oldRead = JSON.readTree("""
        {"ruleId":"title","targetField":"Subject","origin":{"kind":"MESSAGE","interactionId":"onTaskStart",
        "port":"OUTPUT","path":"$.title"},"schemaDigest":"event-hash","targetSchemaDigest":"request-hash",
        "contextId":null,"nullable":false}
        """);
    var read = JSON.treeToValue(oldRead, CopyMappingPlan.Read.class);

    assertAll(
        () -> assertEquals(List.of(), read.runtimeTypes()),
        () -> assertEquals(oldRead, JSON.valueToTree(read)));
  }

  private Map<String, CopyMappingPlan> prepare(List<MappingIntent> intents) {
    return CopyMappingPlanner.prepare(revision(), intents, resolver, List.of(), Map.of());
  }

  private static Object execute(MappingIntent intent, MappingSchemaSide source, MappingSchemaSide target,
      Map<String, CopyMappingPlan> plans, Object exchange) {
    MappingEnvelope envelope = envelope(source, target).withMappingIntentId(intent.mappingIntentId())
        .withVerifiedCopyPlan(plans.get(intent.mappingIntentId()));
    DirectFieldMappingScript.Generated generated = DirectFieldMappingScript.from(intent, envelope).orElseThrow();
    SecureGroovyMappingCompiler.compile(generated.script());
    Object result = new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(generated.script());
    try {
      return JSON.readValue(assertInstanceOf(String.class, result), Map.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new AssertionError("COPY did not produce a JSON object: " + result, invalid);
    }
  }

  private MappingSchemaSide side(String owner, MappingPort port, String digest, String schema) throws Exception {
    MappingSchemaSide side = rawSide(owner, port, digest, schema);
    artifacts.append(new CompilationArtifacts.AppendCommand(COMPILATION, CompilationArtifacts.Kind.MAPPING_SCHEMA_SIDE,
        "1", "test", "1", side, List.of(), null));
    return side;
  }

  private static MappingSchemaSide rawSide(String owner, MappingPort port, String digest, String schema) throws Exception {
    return new MappingSchemaSide("1", owner, "op", port, "application/json", null, digest, "test", JSON.readTree(schema));
  }

  private static MappingIntentRule rule(String source, String target, String interaction, MappingPort port,
      MappingSchemaSide sourceSchema, MappingSchemaSide targetSchema) {
    return new MappingIntentRule(source, List.of("fact-" + source), "$." + target,
        new MappingValue.Copy(origin(interaction, port, source)), MappingCondition.always(), MappingRuleStatus.PROPOSED)
        .withMissingSourcePolicy("ERROR")
        .withSchemaPins(Map.of("source", sourceSchema.sha256(), "target", targetSchema.sha256()));
  }

  private static String schemaHash(JsonNode schema) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(JSON.writeValueAsBytes(schema)));
  }

  private static org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint testBinding(String id) {
    return new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint(
        "3", id, "fact-" + id, id, "system", "group", "spec", "op-" + id,
        "HTTP", "POST", "/" + id, "1", java.time.Instant.EPOCH, "catalog-evidence");
  }

  private static MappingSource.Message origin(String interaction, MappingPort port, String field) {
    return new MappingSource.Message(interaction, port, "$." + field);
  }

  private static MappingEnvelope envelope(MappingSchemaSide source, MappingSchemaSide target) {
    return new JsonSchemaMessageSchemaFactory(JSON).fromSides(source, target);
  }

  private static ChainSemanticRevision revision() {
    return new ChainSemanticRevision(ChainSemanticRevision.CURRENT_SCHEMA_VERSION, "revision", "chain", "1", List.of(),
        List.of(new SemanticNode.Trigger("node-in", "onTaskStart", "async-api-trigger", null),
            new SemanticNode.ServiceCall("node-call", "createTask", "create", null),
            new SemanticNode.ServiceCall("node-result", "taskResult", "send", null)),
        List.of(), List.of(new SemanticExecutionEdge("in-call", "node-in", "node-call", null, null, null),
            new SemanticExecutionEdge("call-result", "node-call", "node-result", null, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public static final class Message { public Object body; Message(Object body) { this.body = body; } }
  public static final class Exchange {
    public final Message in;
    public final Map<String, Object> properties = new LinkedHashMap<>();
    Exchange(Object body) { in = new Message(body); }
    public Object getProperty(String key) { return properties.get(key); }
    public void setProperty(String key, Object value) { properties.put(key, value); }
  }
}

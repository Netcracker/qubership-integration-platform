package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
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

  @Test
  void earlierEventRenameSurvivesBodyReplacementAndSameNamedResponseField() {
    Map<String, CopyMappingPlan> plans = prepare(List.of(requestIntent, resultIntent));
    Exchange exchange = new Exchange(new LinkedHashMap<>(Map.of("title", "Create task", "processInstanceId", "ORIGINAL")));
    assertEquals(Map.of("Subject", "Create task"), execute(requestIntent, event, request, plans, exchange));
    exchange.in.body = Map.of("processInstanceId", "RESPONSE_SENTINEL");
    assertEquals(Map.of("processId", "ORIGINAL"), execute(resultIntent, response, result, plans, exchange));
    assertEquals(1, exchange.properties.size());
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
    assertEquals(Map.of(), exchange.properties);
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

  private Map<String, CopyMappingPlan> prepare(List<MappingIntent> intents) {
    return CopyMappingPlanner.prepare(revision(), intents, resolver, List.of(), Map.of());
  }

  private static Object execute(MappingIntent intent, MappingSchemaSide source, MappingSchemaSide target,
      Map<String, CopyMappingPlan> plans, Exchange exchange) {
    MappingEnvelope envelope = envelope(source, target).withMappingIntentId(intent.mappingIntentId())
        .withVerifiedCopyPlan(plans.get(intent.mappingIntentId()));
    DirectFieldMappingScript.Generated generated = DirectFieldMappingScript.from(intent, envelope).orElseThrow();
    SecureGroovyMappingCompiler.compile(generated.script());
    return new GroovyShell(new Binding(Map.of("exchange", exchange))).evaluate(generated.script());
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

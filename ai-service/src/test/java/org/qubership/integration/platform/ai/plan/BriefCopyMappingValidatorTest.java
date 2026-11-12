package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingCondition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

class BriefCopyMappingValidatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SOURCE = """
      {"type":"object","properties":{"processInstanceId":{"type":"string"}}}
      """;
  private static final String TARGET = """
      {"type":"object","properties":{"processId":{"type":"string"}}}
      """;

  @Test
  void earlierEventCopyPinsBothSchemasWithoutChangingOrigin() throws Exception {
    var result = validator(SOURCE, TARGET).validateAndPin(brief("entry", MappingPort.OUTPUT, "$.processInstanceId"));
    var rule = result.brief().mappingIntents().getFirst().rules().getFirst();
    assertAll(
        () -> assertEquals(List.of(), result.issues()),
        () -> assertEquals(new MappingValue.Copy(new MappingSource.Message("entry", MappingPort.OUTPUT,
            "$.processInstanceId")), rule.value()),
        () -> assertEquals("ERROR", rule.missingSourcePolicy()),
        () -> assertEquals(Map.of("source", "d500736bdb6f43ad64a1bd56f2626820982dc705d8d6265f0262e0dcc14ab157", "target", "a33b43fecff4d5a99f5a198a5cb5dd35950ad0c971893e2857015e0a166cbbfa"), rule.schemaPins()));
  }

  @Test
  void localResponseCopyPinsVerifiedOriginAndDerivedOutput() throws Exception {
    var result = validator(SOURCE, TARGET).validateAndPin(localBrief("result"));
    assertEquals(List.of(), result.issues());
    var pins = result.brief().mappingIntents().getFirst().rules().getFirst().schemaPins();
    org.junit.jupiter.api.Assertions.assertNotNull(pins.get("target"));
    assertEquals(List.of(), validator(SOURCE, TARGET).validateApproved(result.brief()));
  }

  @Test
  void localOutputRequiresAUniqueDeclarationAtTheFinalInteraction() throws Exception {
    var validator = validator(SOURCE, TARGET);
    var brief = localBrief("result");
    assertAll(
        () -> assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), validator.validateAndPin(brief.withFacts(List.of()))
            .issues().stream().map(ProductRequirementBriefTool.Issue::code).toList()),
        () -> assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), validator.validateAndPin(
                brief.withFacts(List.of(brief.facts().getFirst(), brief.facts().getFirst())))
            .issues().stream().map(ProductRequirementBriefTool.Issue::code).toList()),
        () -> assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), validator.validateAndPin(localBrief("call"))
            .issues().stream().map(ProductRequirementBriefTool.Issue::code).toList()));
  }

  @Test
  void changedLocalOriginSchemaInvalidatesTheApprovedOutput() throws Exception {
    var approved = validator(SOURCE, TARGET).validateAndPin(localBrief("result")).brief();
    var changed = validator("""
        {"type":"object","properties":{"processInstanceId":{"type":"string","maxLength":10}}}
        """, TARGET);
    assertEquals(List.of("MAPPING_SCHEMA_CHANGED"), changed.validateApproved(approved).stream()
        .map(ProductRequirementBriefTool.Issue::code).toList());
  }

  private static RequirementBrief localBrief(String predecessor) {
    var base = brief("entry", MappingPort.OUTPUT, "$.processInstanceId");
    var declaration = new RequirementElementIntent("response", "script",
        RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, predecessor, null,
        List.of(), List.of());
    var fact = new RequirementFact("return-fact", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.FIELD_MAPPING, "", "Return the mapped response.", null, null,
        null, null, null, null, List.of("entry"), null, declaration);
    var original = base.mappingIntents().getFirst();
    var local = new MappingIntent(original.mappingIntentId(), "result", MappingPort.RESPONSE,
        "response", MappingPort.OUTPUT, original.rules());
    return base.withFacts(List.of(fact)).withMappingIntents(List.of(local));
  }

  @Test
  void undeclaredOpenSourceCannotEarnSchemaVerification() throws Exception {
    var result = validator(SOURCE, TARGET).validateAndPin(brief("entry", MappingPort.OUTPUT, "$.processId"));
    assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), result.issues().stream()
        .map(ProductRequirementBriefTool.Issue::code).toList());
  }

  @Test
  void declaredIncompatibleSourceCannotEarnSchemaVerification() throws Exception {
    var result = validator("""
        {"type":"object","properties":{"processInstanceId":{"type":"integer"}}}
        """, TARGET).validateAndPin(brief("entry", MappingPort.OUTPUT, "$.processInstanceId"));
    assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), result.issues().stream()
        .map(ProductRequirementBriefTool.Issue::code).toList());
  }

  @Test
  void futureResponseIsUnavailableAtEarlierPlacement() throws Exception {
    var result = validator(SOURCE, TARGET).validateAndPin(brief("result", MappingPort.RESPONSE, "$.processInstanceId"));
    assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), result.issues().stream()
        .map(ProductRequirementBriefTool.Issue::code).toList());
  }

  @Test
  void branchOriginDoesNotUseListOrderAsAvailability() throws Exception {
    var flow = new RequirementFlow(List.of(interaction("entry", true), interaction("call", false),
        interaction("result", false)), List.of(new RequirementFlow.Transition("entry", "call"),
        new RequirementFlow.Transition("entry", "result")));
    var result = validator(SOURCE, TARGET).validateAndPin(brief("entry", MappingPort.OUTPUT,
        "$.processInstanceId").withFlow(flow));
    assertEquals(List.of("MAPPING_SOURCE_UNRESOLVED"), result.issues().stream()
        .map(ProductRequirementBriefTool.Issue::code).toList());
  }

  @Test
  void changedSchemaCannotBeRepinnedUnderAnExistingApproval() throws Exception {
    var approved = validator(SOURCE, TARGET).validateAndPin(brief("entry", MappingPort.OUTPUT,
        "$.processInstanceId")).brief();
    var changed = validator("""
        {"type":"object","properties":{"processInstanceId":{"type":"string"},"newField":{"type":"string"}}}
        """, TARGET);
    assertEquals(List.of("MAPPING_SCHEMA_CHANGED"), changed.validateApproved(approved).stream()
        .map(ProductRequirementBriefTool.Issue::code).toList());
  }

  @Test
  void unchangedPinnedSchemaRemainsEligibleForApprovedValidation() throws Exception {
    var validator = validator(SOURCE, TARGET);
    var approved = validator.validateAndPin(brief("entry", MappingPort.OUTPUT, "$.processInstanceId")).brief();
    assertEquals(List.of(), validator.validateApproved(approved));
  }

  @Test
  void shouldPinCustomRequestSchemaWithoutACatalogBindingForTheTrigger() throws Exception {
    var validator = validator(SOURCE, TARGET);
    var custom = customEntryBrief(SOURCE);
    var result = validator.validateAndPin(custom);
    var rule = result.brief().mappingIntents().getFirst().rules().getFirst();
    assertAll(
        () -> assertEquals(List.of(), result.issues()),
        () -> assertEquals("a33b43fecff4d5a99f5a198a5cb5dd35950ad0c971893e2857015e0a166cbbfa",
            rule.schemaPins().get("target")),
        () -> assertEquals(List.of(), validator.validateApproved(result.brief())));
  }

  @Test
  void directCatalogCopyCanUseRuntimeJsonWithoutAnAuthoredInputSchema() throws Exception {
    var validator = validator(SOURCE, TARGET);
    var custom = directCatalogBrief(SOURCE);
    var noSchema = custom.withFacts(List.of(custom.facts().getFirst()));

    var result = validator.validateAndPin(noSchema);

    assertAll(
        () -> assertEquals(List.of(), result.issues()),
        () -> assertEquals("runtime-json:entry",
            result.brief().mappingIntents().getFirst().rules().getFirst().schemaPins().get("source")),
        () -> assertEquals(List.of(), validator.validateApproved(result.brief())));
  }

  @Test
  void catalogConstraintsThatNeedSourceEvidenceOfferAnInputSchemaQuestion() throws Exception {
    String source = """
        {"type":"object","properties":{"processInstanceId":{"type":"string","minLength":3}}}
        """;
    String target = """
        {"type":"object","properties":{"processId":{"type":"string","minLength":3}}}
        """;
    var validator = validator(source, target);
    var custom = directCatalogBrief(source);
    var result = validator.validateAndPin(custom.withFacts(List.of(custom.facts().getFirst())));

    assertAll(
        () -> assertEquals(List.of("COPY_INPUT_SCHEMA_REQUIRED"),
            result.issues().stream().map(ProductRequirementBriefTool.Issue::code).toList()),
        () -> assertEquals(List.of(new RequirementCaptureProjection.PendingWork("COPY_INPUT_SCHEMA_REQUIRED",
            "entry", null, "Provide the JSON Schema for the request body of POST /events."
                + " Use propertyKey=requestSchema for this question.")), result.pendingWork()),
        () -> assertEquals(List.of(), validator.validateAndPin(custom).issues()));
  }

  private static RequirementBrief directCatalogBrief(String schema) {
    var custom = customEntryBrief(schema);
    var intent = new MappingIntent("entry-copy", "entry", MappingPort.OUTPUT, "result",
        MappingPort.REQUEST, custom.mappingIntents().getFirst().rules());
    return custom.withMappingIntents(List.of(intent))
        .withFlow(new RequirementFlow(List.of(interaction("entry", true), interaction("result", false)),
            List.of(new RequirementFlow.Transition("entry", "result"))));
  }

  @Test
  void retainedCopyUsesTheCatalogFieldTypeAndRejectsAnIncompatibleSuppliedSchema() throws Exception {
    var validator = validator(SOURCE, TARGET);
    var custom = customEntryBrief(SOURCE);
    var noSchema = custom.withFacts(List.of(custom.facts().getFirst()));
    var mismatch = customEntryBrief("""
        {"type":"object","properties":{"processInstanceId":{"type":"integer"}}}
        """);
    assertAll(
        () -> assertEquals(List.of(), validator.validateAndPin(noSchema).issues()),
        () -> assertEquals(List.of("Mapping origin is unresolved: COPY source and target types are incompatible"),
            validator.validateAndPin(mismatch).issues().stream().map(ProductRequirementBriefTool.Issue::message).toList()));
  }

  private static RequirementBrief customEntryBrief(String schema) {
    var endpoint = new RequirementFact("route", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY, "http-trigger", "Accept an event.",
        "Caller", "POST /events", null, "POST", "/events", null, List.of("entry"));
    var contract = new RequirementFact("request-schema", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.MESSAGE_SCHEMA, "", schema,
        null, null, null, null, null, null, List.of("entry"));
    return brief("entry", MappingPort.OUTPUT, "$.processInstanceId")
        .withFacts(List.of(endpoint, contract)).withCatalogBindings(List.of(binding("result")));
  }

  private static BriefCopyMappingValidator validator(String source, String target) throws Exception {
    var maps = Map.of("op-entry", new OperationSchemaMaps("op-entry",
            Map.of("application/json", JSON.readTree(source)), Map.of()),
        "op-result", new OperationSchemaMaps("op-result", Map.of("application/json", JSON.readTree(target)), Map.of()));
    return new BriefCopyMappingValidator(new ReadOnlySchemas(maps), JSON);
  }

  private static RequirementBrief brief(String origin, MappingPort port, String path) {
    var rule = new MappingIntentRule("copy", List.of("mapping-fact"), "$.processId",
        new MappingValue.Copy(new MappingSource.Message(origin, port, path)),
        MappingCondition.always(), MappingRuleStatus.PROPOSED).withMissingSourcePolicy("ERROR");
    return new RequirementBrief("Copy original ID", List.of(), List.of(), List.of(), List.of(), "Copy original ID")
        .withFlow(new RequirementFlow(List.of(interaction("entry", true), interaction("call", false),
            interaction("result", false)), List.of(new RequirementFlow.Transition("entry", "call"),
            new RequirementFlow.Transition("call", "result"))))
        .withCatalogBindings(List.of(binding("entry"), binding("result")))
        .withMappingIntents(List.of(new MappingIntent("response-copy", "call", MappingPort.RESPONSE,
            "result", MappingPort.REQUEST, List.of(rule))));
  }

  private static RequirementFlow.Interaction interaction(String id, boolean inbound) {
    return new RequirementFlow.Interaction(id, inbound ? RequirementFlow.Direction.INBOUND
        : RequirementFlow.Direction.OUTBOUND, "service", id, "");
  }

  private static CatalogBindingHint binding(String id) {
    return new CatalogBindingHint("3", id, "fact-" + id, id, "system", "group", "spec", "op-" + id,
        "HTTP", "POST", "/" + id, "1", Instant.EPOCH, "catalog-evidence");
  }

  private record ReadOnlySchemas(Map<String, OperationSchemaMaps> maps) implements OperationSchemaLoader {
    @Override public OperationSchemaMaps load(String operationId) { return maps.get(operationId); }
    @Override public MappingSchemaSide persistRequest(String compilationId, String serviceCallId,
        String operationId, String contentType) { throw new UnsupportedOperationException(); }
    @Override public MappingSchemaSide persistResponse(String compilationId, String serviceCallId,
        String operationId, String contentType, String responseCode) { throw new UnsupportedOperationException(); }
  }
}

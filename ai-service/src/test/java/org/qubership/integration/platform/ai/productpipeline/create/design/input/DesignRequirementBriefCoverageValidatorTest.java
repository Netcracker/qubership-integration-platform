package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class DesignRequirementBriefCoverageValidatorTest {

  private final DesignRequirementBriefCoverageValidator designValidator =
      new DesignRequirementBriefCoverageValidator();

  @Test
  void readableMissingEdgesUseKindAndTextWithoutSourceFactIds() {
    RequirementBrief brief = twoCallBrief(List.of());

    List<String> technical = designValidator.listMissingEdges(brief);
    List<String> readable = designValidator.listReadableMissingEdges(brief);

    assertFalse(technical.isEmpty());
    assertEquals(technical.size(), readable.size());
    assertTrue(technical.getFirst().contains("trigger-1"), technical.getFirst());
    assertTrue(technical.getFirst().contains("mapping required:"), technical.getFirst());
    assertTrue(readable.getFirst().startsWith("INITIALIZATION: ENDPOINT"), readable.getFirst());
    assertTrue(readable.getFirst().contains("GET /orders"), readable.getFirst());
    assertTrue(readable.getFirst().contains("SERVICE_CALL"), readable.getFirst());
    assertFalse(readable.getFirst().contains("mapping required:"), readable.getFirst());
    assertFalse(readable.getFirst().contains("trigger-1 →"), readable.getFirst());
  }

  @Test
  void unknownSchemasAllowApprovalWithoutMappingRows() {
    RequirementBrief briefWithoutInitialization = twoCallBrief(List.of(conversion(), response()));

    assertDoesNotThrow(() -> designValidator.validate(briefWithoutInitialization));
  }

  @Test
  void identityOnlyAutoDoesNotCreateMappingIntentAndDoesNotBlockTopology() {
    RequirementDataMapping identityInit =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.id", null)),
            List.of("fact-map-init"));
    RequirementBrief brief = twoCallBrief(List.of(identityInit, conversion(), response()));

    assertDoesNotThrow(() -> designValidator.validate(brief));
    RequirementBrief projected = RequirementBriefProjector.project(brief);
    assertEquals(1, projected.mappingIntents().size());
    assertEquals("map-conv", projected.mappingIntents().getFirst().mappingIntentId());
  }

  @Test
  void unresolvedRequiredTargetDoesNotFailTopologyValidate() {
    RequirementBrief brief =
        twoCallBrief(List.of(initialization(), conversion(), response()))
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-init",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "call-1",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule(
                                "", "$.personId", null, MappingRuleStatus.UNRESOLVED)))));

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(BriefMappingValidator.blocksApproval(brief));
  }

  @Test
  void missingMappingMessageListsEveryEdgeAndExactRepairPayload() {
    List<String> missing = designValidator.listMissingEdges(twoCallBrief(List.of()));

    assertTrue(missing.stream().anyMatch(line -> line.contains("INITIALIZATION mapping required")));
    assertTrue(missing.stream().anyMatch(line -> line.contains("CONVERSION mapping required")));
    assertTrue(missing.stream().anyMatch(line -> line.contains("RESPONSE mapping required")));
    assertDoesNotThrow(() -> designValidator.validate(twoCallBrief(List.of())));
  }

  @Test
  void passThroughFillsMissingEdgesThenValidatePasses() {
    RequirementBrief briefWithoutMappings = twoCallBrief(List.of());

    assertDoesNotThrow(() -> designValidator.validate(briefWithoutMappings));

    RequirementBrief filled =
        designValidator.withPassThroughForMissingEdges(briefWithoutMappings);
    assertDoesNotThrow(() -> designValidator.validate(filled));
    assertEquals(3, filled.dataMappings().size());
  }

  @Test
  void passThroughDropsUnboundLeftoverRowsThenFillsRequiredEdges() {
    RequirementBrief briefWithLeftovers =
        twoCallBrief(
            List.of(
                leftoverHashMapping(
                    RequirementDataMapping.Stage.INITIALIZATION,
                    "820d45e25846bb71f78bd5c219f72f87399d7c263d789990f551d38b675bc9e3",
                    "b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f"),
                leftoverHashMapping(
                    RequirementDataMapping.Stage.RESPONSE,
                    "b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f",
                    "b8598ee044e21b5e58941a3e896a1c10ed1f3e05c4f031bb743ff8efdcc3d791")));

    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(briefWithLeftovers);

    assertDoesNotThrow(() -> designValidator.validate(filled));
    assertEquals(3, filled.dataMappings().size());
    assertTrue(
        filled.dataMappings().stream()
            .noneMatch(
                mapping ->
                    mapping.fromIntentRef().contains("820d45e2")
                        || mapping.toIntentRef().contains("b96b0eea")),
        filled.dataMappings().toString());
  }

  @Test
  void explicitRulesFillNumberedMissingEdges() {
    RequirementBrief briefWithoutMappings = twoCallBrief(List.of());

    RequirementBrief filled =
        designValidator.withExplicitMappingsForMissingEdges(
            briefWithoutMappings,
            """
            1: $.request.id -> $.headers.X-Request-Id
            2: $.customer -> $.body.customer | normalizeCustomer(value)
            3: $.inventory -> $.body
            """);

    assertDoesNotThrow(() -> designValidator.validate(filled));
    assertEquals(3, filled.dataMappings().size());
    RequirementDataMapping conversion = filled.dataMappings().get(1);
    assertEquals(RequirementDataMapping.Mode.EXPLICIT, conversion.mode());
    assertEquals("$.customer", conversion.rules().getFirst().sourcePath());
    assertEquals("$.body.customer", conversion.rules().getFirst().targetPath());
    assertEquals("normalizeCustomer(value)", conversion.rules().getFirst().expression());
  }

  @Test
  void explicitRulesRequireEdgeNumbersWhenSeveralEdgesAreMissing() {
    RequirementBrief briefWithoutMappings = twoCallBrief(List.of());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                designValidator.withExplicitMappingsForMissingEdges(
                    briefWithoutMappings, "$.request.id -> $.headers.X-Request-Id"));

    assertTrue(thrown.getMessage().contains("edge number"), thrown.getMessage());
  }

  @Test
  void missingConversionEdgeIsNonblockingPassThrough() {
    RequirementBrief briefWithoutConversion = twoCallBrief(List.of(initialization(), response()));

    assertDoesNotThrow(() -> designValidator.validate(briefWithoutConversion));
  }

  @Test
  void missingResponseEdgeIsNonblockingPassThrough() {
    RequirementBrief briefWithoutResponse =
        twoCallBrief(List.of(initialization(), conversion()));

    assertDoesNotThrow(() -> designValidator.validate(briefWithoutResponse));
  }

  @Test
  void rejectsWrongInitializationTopologyRefs() {
    RequirementDataMapping wrongInit =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "call-1",
            "call-2",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.customerId", null)),
            List.of());
    RequirementBrief brief = twoCallBrief(List.of(wrongInit, conversion(), response()));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(thrown.getMessage().contains("INITIALIZATION"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("trigger-1"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("call-1"), thrown.getMessage());
  }

  @Test
  void rejectsWrongConversionTopologyRefs() {
    RequirementDataMapping wrongConversion =
        new RequirementDataMapping(
            "map-conv",
            RequirementDataMapping.Stage.CONVERSION,
            "trigger-1",
            "call-2",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.customer", "$.body.customer", null)),
            List.of());
    RequirementBrief brief = twoCallBrief(List.of(initialization(), wrongConversion, response()));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(thrown.getMessage().contains("CONVERSION"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("call-1"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("call-2"), thrown.getMessage());
  }

  @Test
  void rejectsWrongResponseTopologyRefs() {
    RequirementDataMapping wrongResponse =
        new RequirementDataMapping(
            "map-resp",
            RequirementDataMapping.Stage.RESPONSE,
            "call-1",
            "call-2",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(),
            List.of("fact-map-resp"));
    RequirementBrief brief = twoCallBrief(List.of(initialization(), conversion(), wrongResponse));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(thrown.getMessage().contains("RESPONSE"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("call-2"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("trigger-1"), thrown.getMessage());
  }

  @Test
  void rejectsUnexpectedTopologyMappingEvenWhenRequiredEdgesExist() {
    RequirementDataMapping reversedInitialization =
        new RequirementDataMapping(
            "map-extra",
            RequirementDataMapping.Stage.INITIALIZATION,
            "call-1",
            "trigger-1",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(),
            List.of("fact-map-extra"));
    RequirementBrief brief =
        twoCallBrief(
            List.of(initialization(), conversion(), response(), reversedInitialization));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(thrown.getMessage().contains("unexpected INITIALIZATION"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("call-1"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("trigger-1"), thrown.getMessage());
  }

  @Test
  void rejectsExplicitMappingWithNoRules() {
    RequirementDataMapping explicitMappingWithNoRules =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(),
            List.of("fact-map-init"));
    RequirementBrief brief =
        twoCallBrief(List.of(explicitMappingWithNoRules, conversion(), response()));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(
        thrown.getMessage().contains("EXPLICIT mapping requires at least one rule"),
        thrown.getMessage());
  }

  @Test
  void acceptsExplicitMappingWithEmptySourceFactIdsWhenRulesPresent() {
    RequirementDataMapping explicitWithoutSourceFacts =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.customerId", null)),
            List.of());
    RequirementBrief brief =
        twoCallBrief(List.of(explicitWithoutSourceFacts, conversion(), response()));

    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void incompletePassThroughDoesNotCoverRequiredEdges() {
    RequirementDataMapping passThroughWithoutSources =
        new RequirementDataMapping(
            "map-resp",
            RequirementDataMapping.Stage.RESPONSE,
            "call-2",
            "trigger-1",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(),
            List.of());
    RequirementBrief brief =
        twoCallBrief(List.of(initialization(), conversion(), passThroughWithoutSources));

    List<String> missing = designValidator.listMissingEdges(brief);
    assertTrue(
        missing.stream().anyMatch(line -> line.contains("RESPONSE")),
        String.join("\n", missing));

    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void scriptOnlyIncompletePassThroughIsAbsenceOfMapping() {
    RequirementBrief brief = scriptOnlyBrief(List.of(shapelessPassThrough()));

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void shapelessLeftoverRowsDoNotCoverServiceCallEdges() {
    RequirementBrief brief = twoCallBrief(List.of(shapelessPassThrough()));

    List<String> missing = designValidator.listMissingEdges(brief);
    assertFalse(missing.isEmpty());
    assertTrue(
        missing.stream().anyMatch(line -> line.contains("INITIALIZATION")),
        String.join("\n", missing));
  }

  @Test
  void acceptsCompleteTwoCallBrief() {
    RequirementBrief brief = twoCallBrief(List.of(initialization(), conversion(), response()));

    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void rejectsPassThroughWithRules() {
    RequirementDataMapping passThroughWithRules =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(new RequirementDataMapping.Rule("a", "b", null)),
            List.of("fact-map-init"));
    RequirementBrief brief = twoCallBrief(List.of(passThroughWithRules, conversion(), response()));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(thrown.getMessage().contains("PASS_THROUGH"), thrown.getMessage());
  }

  @Test
  void kafkaCapabilityFactIsAnEntryWithoutAnEndpointKind() {
    RequirementBrief brief =
        briefWithFacts(
            List.of(
                kafkaCapability("trigger-1", "kafka-trigger-2"),
                call("call-1")),
            List.of());

    List<String> missing = designValidator.listMissingEdges(brief);
    assertEquals(1, missing.size(), missing.toString());
    assertTrue(missing.getFirst().contains("INITIALIZATION"), missing.getFirst());
    assertTrue(missing.getFirst().contains("trigger-1"), missing.getFirst());
    assertFalse(missing.getFirst().contains("no ENDPOINT fact"), missing.getFirst());

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(brief.dataMappings().isEmpty());
  }

  @Test
  void rabbitmqCapabilityFactIsAnEntryWithoutAKafkaSpecialCase() {
    RequirementBrief brief =
        briefWithFacts(
            List.of(
                kafkaCapability("trigger-1", "rabbitmq-trigger-2"),
                call("call-1")),
            List.of());

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(brief.dataMappings().isEmpty());
  }

  @Test
  void missingTriggerStopsBeforeMappingValidation() {
    RequirementBrief brief = briefWithFacts(List.of(call("call-1")), List.of());

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));
    assertTrue(thrown.getMessage().contains("configured trigger entry"), thrown.getMessage());
    assertFalse(thrown.getMessage().contains("INITIALIZATION"), thrown.getMessage());
    assertFalse(thrown.getMessage().contains("no ENDPOINT fact"), thrown.getMessage());
  }

  @Test
  void passThroughCannotInventAMissingTriggerSource() {
    RequirementBrief brief = briefWithFacts(List.of(call("call-1")), List.of());

    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(brief);
    assertTrue(filled.dataMappings().isEmpty());
    RequirementBrief described =
        designValidator.withExplicitMappingsForMissingEdges(brief, "$.a -> $.b");
    assertTrue(described.dataMappings().isEmpty());
  }

  @Test
  void doesNotRequireResponseForFireAndForgetTrigger() {
    RequirementFact asyncTrigger =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "async-api-trigger",
            "Consume OrderCreated events");
    RequirementBrief brief =
        briefWithFacts(
            List.of(asyncTrigger, call("call-1"), call("call-2")),
            List.of(initialization(), conversion()));

    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  private static RequirementBrief scriptOnlyBrief(List<RequirementDataMapping> mappings) {
    RequirementFact trigger =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "GET /greetings");
    RequirementFact script =
        new RequirementFact(
            "script-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "script",
            "Return greeting text from a script");
    return briefWithFacts(List.of(trigger, script), mappings);
  }

  private static RequirementDataMapping shapelessPassThrough() {
    return new RequirementDataMapping(
        "map-junk",
        null,
        "",
        "",
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of());
  }

  private static RequirementBrief twoCallBrief(List<RequirementDataMapping> mappings) {
    RequirementFact trigger =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "GET /orders");
    return briefWithFacts(List.of(trigger, call("call-1"), call("call-2")), mappings);
  }

  private static RequirementBrief briefWithFacts(
      List<RequirementFact> facts, List<RequirementDataMapping> mappings) {
    return new RequirementBrief(
        "Order flow",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Two outbound calls",
        "ref",
        "draft",
        facts,
        mappings);
  }

  private static RequirementFact kafkaCapability(String id, String capabilityKey) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        capabilityKey,
        "Consume events from " + capabilityKey,
        "",
        "consumeEvent",
        "events",
        "",
        "");
  }

  private static RequirementFact call(String id) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "service-call",
        "Outbound call " + id);
  }

  private static RequirementDataMapping initialization() {
    return new RequirementDataMapping(
        "map-init",
        RequirementDataMapping.Stage.INITIALIZATION,
        "trigger-1",
        "call-1",
        RequirementDataMapping.Mode.EXPLICIT,
        List.of(new RequirementDataMapping.Rule("$.id", "$.customerId", null)),
        List.of("fact-map-init"));
  }

  private static RequirementDataMapping conversion() {
    return new RequirementDataMapping(
        "map-conv",
        RequirementDataMapping.Stage.CONVERSION,
        "call-1",
        "call-2",
        RequirementDataMapping.Mode.EXPLICIT,
        List.of(new RequirementDataMapping.Rule("$.customer", "$.body.customer", null)),
        List.of("fact-map-conv"));
  }

  private static RequirementDataMapping leftoverHashMapping(
      RequirementDataMapping.Stage stage, String from, String to) {
    return new RequirementDataMapping(
        "",
        stage,
        from,
        to,
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of("leftover-fact"));
  }

  private static RequirementDataMapping response() {
    return new RequirementDataMapping(
        "map-resp",
        RequirementDataMapping.Stage.RESPONSE,
        "call-2",
        "trigger-1",
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of("fact-map-resp"));
  }
}

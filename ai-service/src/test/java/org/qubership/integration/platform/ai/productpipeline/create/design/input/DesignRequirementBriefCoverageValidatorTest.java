package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class DesignRequirementBriefCoverageValidatorTest {

  private final DesignRequirementBriefCoverageValidator designValidator =
      new DesignRequirementBriefCoverageValidator();

  @Test
  void rejectsBriefWithCallsButWithoutInitializationMapping() {
    RequirementBrief briefWithoutInitialization = twoCallBrief(List.of(conversion(), response()));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> designValidator.validate(briefWithoutInitialization));

    assertTrue(thrown.getMessage().contains("INITIALIZATION mapping"), thrown.getMessage());
  }

  @Test
  void passThroughFillsMissingEdgesThenValidatePasses() {
    RequirementBrief briefWithoutMappings = twoCallBrief(List.of());

    IllegalArgumentException before =
        assertThrows(
            IllegalArgumentException.class, () -> designValidator.validate(briefWithoutMappings));
    assertTrue(before.getMessage().contains("INITIALIZATION"), before.getMessage());

    RequirementBrief filled =
        designValidator.withPassThroughForMissingEdges(briefWithoutMappings);
    assertDoesNotThrow(() -> designValidator.validate(filled));
    assertEquals(3, filled.dataMappings().size());
  }

  @Test
  void rejectsBriefWithTwoCallsButWithoutConversionMapping() {
    RequirementBrief briefWithoutConversion = twoCallBrief(List.of(initialization(), response()));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> designValidator.validate(briefWithoutConversion));

    assertTrue(thrown.getMessage().contains("CONVERSION mapping"), thrown.getMessage());
  }

  @Test
  void rejectsBriefWithRequestResponseTriggerButWithoutResponseMapping() {
    RequirementBrief briefWithoutResponse =
        twoCallBrief(List.of(initialization(), conversion()));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> designValidator.validate(briefWithoutResponse));

    assertTrue(thrown.getMessage().contains("RESPONSE mapping"), thrown.getMessage());
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
  void rejectsPassThroughWithEmptySourceFactIds() {
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

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(
        thrown.getMessage().contains("PASS_THROUGH mapping requires at least one sourceFactId"),
        thrown.getMessage());
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

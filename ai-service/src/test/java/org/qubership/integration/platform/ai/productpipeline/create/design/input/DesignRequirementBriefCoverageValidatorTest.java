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
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class DesignRequirementBriefCoverageValidatorTest {

  private final DesignRequirementBriefCoverageValidator designValidator =
      new DesignRequirementBriefCoverageValidator();

  @Test
  void rejectsServiceCallWithoutUniqueStep() {
    RequirementFact sharedFirst =
        new RequirementFact(
            "fact-om",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "http-service-call",
            "Call Order Management onTaskResult",
            "Order Management",
            "onTaskResult",
            "",
            "",
            "",
            "call-om-result");
    RequirementFact sharedSecond =
        new RequirementFact(
            "fact-om-again",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "http-service-call",
            "Call Order Management onTaskResult again",
            "Order Management",
            "onTaskResult",
            "",
            "",
            "",
            "call-om-result");
    RequirementBrief brief =
        briefWithFacts(
            List.of(httpTrigger(), sharedFirst, sharedSecond),
            List.of(),
            List.of(
                new RequirementServiceCall(
                    "call-om-result", "fact-om", "Order Management", "onTaskResult"),
                new RequirementServiceCall(
                    "call-om-result", "fact-om-again", "Order Management", "onTaskResult")));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> designValidator.validate(brief));

    assertTrue(thrown.getMessage().contains("call-om-result"), thrown.getMessage());
    assertTrue(thrown.getMessage().toLowerCase().contains("unique"), thrown.getMessage());
  }

  @Test
  void kafkaAsyncTriggerWithNoIntentsDoesNotRequireInitializationEdge() {
    RequirementBrief brief =
        briefWithFacts(
            List.of(kafkaCapability("trigger-1", "kafka-trigger-2"), call("call-1")), List.of());

    assertTrue(designValidator.listMissingEdges(brief).isEmpty(), brief.toString());
    assertTrue(designValidator.listReadableMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(brief);
    assertTrue(filled.dataMappings().isEmpty(), filled.dataMappings().toString());
    assertTrue(filled.mappingIntents().isEmpty(), filled.mappingIntents().toString());
  }

  @Test
  void missingStageEdgesAreNotInventedAsPassThroughRows() {
    RequirementBrief brief = twoCallBrief(List.of());

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertTrue(designValidator.listReadableMissingEdges(brief).isEmpty());
    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(brief);
    assertTrue(filled.dataMappings().isEmpty(), filled.dataMappings().toString());
    assertDoesNotThrow(() -> designValidator.validate(brief));
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
  void passThroughDropsUnboundLeftoverRowsWithoutFillingStageEdges() {
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
    assertTrue(filled.dataMappings().isEmpty(), filled.dataMappings().toString());
  }

  @Test
  void leftoverExplicitMappingIsNotRejectedForStageTopology() {
    RequirementDataMapping leftoverExplicit =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "call-1",
            "call-2",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.customerId", null)),
            List.of());
    RequirementBrief brief = twoCallBrief(List.of(leftoverExplicit));

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
  }

  @Test
  void missingConversionEdgeIsNonblockingPassThrough() {
    RequirementBrief briefWithoutConversion = twoCallBrief(List.of(initialization(), response()));

    assertDoesNotThrow(() -> designValidator.validate(briefWithoutConversion));
  }

  @Test
  void missingResponseEdgeIsNonblockingPassThrough() {
    RequirementBrief briefWithoutResponse = twoCallBrief(List.of(initialization(), conversion()));

    assertDoesNotThrow(() -> designValidator.validate(briefWithoutResponse));
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
  void incompletePassThroughIsAbsenceOfMappingNotAGap() {
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

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void scriptOnlyIncompletePassThroughIsAbsenceOfMapping() {
    RequirementBrief brief = scriptOnlyBrief(List.of(shapelessPassThrough()));

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void shapelessLeftoverRowsDoNotInventServiceCallEdges() {
    RequirementBrief brief = twoCallBrief(List.of(shapelessPassThrough()));

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
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
            List.of(kafkaCapability("trigger-1", "kafka-trigger-2"), call("call-1")), List.of());

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(brief.dataMappings().isEmpty());
  }

  @Test
  void rabbitmqCapabilityFactIsAnEntryWithoutAKafkaSpecialCase() {
    RequirementBrief brief =
        briefWithFacts(
            List.of(kafkaCapability("trigger-1", "rabbitmq-trigger-2"), call("call-1")), List.of());

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(brief.dataMappings().isEmpty());
  }

  @Test
  void missingTriggerStopsBeforeMappingValidation() {
    RequirementBrief brief =
        briefWithFacts(List.of(call("call-1")), List.of())
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "call-1", Direction.OUTBOUND, "External service", "call", "")),
                    List.of()));

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
    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
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

  private static RequirementFact httpTrigger() {
    return new RequirementFact(
        "trigger-1",
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT,
        "http-trigger",
        "GET /orders");
  }

  private static RequirementBrief briefWithFacts(
      List<RequirementFact> facts, List<RequirementDataMapping> mappings) {
    return briefWithFacts(facts, mappings, List.of());
  }

  private static RequirementBrief briefWithFacts(
      List<RequirementFact> facts,
      List<RequirementDataMapping> mappings,
      List<RequirementServiceCall> serviceCalls) {
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
        mappings,
        List.of(),
        serviceCalls,
        List.of(),
        List.of());
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
        "Outbound call " + id,
        "External service",
        "call",
        "",
        "",
        "",
        id);
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

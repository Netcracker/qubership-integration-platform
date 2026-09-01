package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
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
        briefWithFacts(List.of(kafkaCapability("trigger-1", "kafka-trigger-2"), call("call-1")));

    assertTrue(designValidator.listMissingEdges(brief).isEmpty(), brief.toString());
    assertTrue(designValidator.listReadableMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(brief);
    assertTrue(filled.mappingIntents().isEmpty(), filled.mappingIntents().toString());
  }

  @Test
  void missingStageEdgesAreNotInventedAsPassThroughRows() {
    RequirementBrief brief = twoCallBrief();

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertTrue(designValidator.listReadableMissingEdges(brief).isEmpty());
    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(brief);
    assertTrue(filled.mappingIntents().isEmpty(), filled.mappingIntents().toString());
    assertDoesNotThrow(() -> designValidator.validate(brief));
  }

  @Test
  void unresolvedRequiredTargetDoesNotFailTopologyValidate() {
    RequirementBrief brief =
        twoCallBrief()
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
  void acceptsCompleteTwoCallBrief() {
    assertDoesNotThrow(() -> designValidator.validate(twoCallBrief()));
  }

  @Test
  void kafkaCapabilityFactIsAnEntryWithoutAnEndpointKind() {
    RequirementBrief brief =
        briefWithFacts(List.of(kafkaCapability("trigger-1", "kafka-trigger-2"), call("call-1")));

    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void rabbitmqCapabilityFactIsAnEntryWithoutAKafkaSpecialCase() {
    RequirementBrief brief =
        briefWithFacts(List.of(kafkaCapability("trigger-1", "rabbitmq-trigger-2"), call("call-1")));

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void missingTriggerStopsBeforeMappingValidation() {
    RequirementBrief brief =
        briefWithFacts(List.of(call("call-1")))
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
    RequirementBrief brief = briefWithFacts(List.of(call("call-1")));

    RequirementBrief filled = designValidator.withPassThroughForMissingEdges(brief);
    assertTrue(filled.mappingIntents().isEmpty());
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
        briefWithFacts(List.of(asyncTrigger, call("call-1"), call("call-2")));

    assertDoesNotThrow(() -> designValidator.validate(brief));
    assertTrue(designValidator.listMissingEdges(brief).isEmpty());
  }

  private static RequirementBrief twoCallBrief() {
    RequirementFact trigger =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "GET /orders");
    return briefWithFacts(List.of(trigger, call("call-1"), call("call-2")));
  }

  private static RequirementFact httpTrigger() {
    return new RequirementFact(
        "trigger-1",
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT,
        "http-trigger",
        "GET /orders");
  }

  private static RequirementBrief briefWithFacts(List<RequirementFact> facts) {
    return briefWithFacts(facts, List.of());
  }

  private static RequirementBrief briefWithFacts(
      List<RequirementFact> facts, List<RequirementServiceCall> serviceCalls) {
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
}

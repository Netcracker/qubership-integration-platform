package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequirementTriggerRoleTest {

  @Test
  void catalogTriggerCapabilityIsAnEntryRegardlessOfFactKind() {
    RequirementFact kafka =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-trigger-2",
            "Consume user events",
            "",
            "consumeUserEvent",
            "user/events",
            "",
            "");
    RequirementFact rabbit =
        new RequirementFact(
            "trigger-2",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "rabbitmq-trigger-2",
            "Consume order events");

    assertTrue(RequirementTriggerRole.isConfiguredTrigger(kafka));
    assertTrue(RequirementTriggerRole.isConfiguredTrigger(rabbit));
    assertEquals(List.of(kafka, rabbit), RequirementTriggerRole.positiveTriggers(List.of(kafka, rabbit)));
  }

  @Test
  void nonTriggerCapabilityIsNotAnEntryEvenWhenKindIsEndpoint() {
    RequirementFact callShapedAsEndpoint =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-service-call",
            "Not a trigger");

    assertFalse(RequirementTriggerRole.isConfiguredTrigger(callShapedAsEndpoint));
  }

  @Test
  void canonicalizesUnambiguousCapabilityKindToEndpoint() {
    RequirementFact kafka =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-trigger-2",
            "Consume user events",
            "",
            "consumeUserEvent",
            "user/events",
            "",
            "");
    RequirementFact call =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "http-service-call",
            "Look up a pet",
            "Petstore Ext",
            "getPetById",
            "",
            "",
            "",
            "call-1");

    List<RequirementFact> canonical = RequirementTriggerRole.canonicalize(List.of(kafka, call));

    assertEquals(RequirementFactKind.ENDPOINT, canonical.getFirst().kind());
    assertEquals("trigger-1", canonical.getFirst().sourceFactId());
    assertEquals("kafka-trigger-2", canonical.getFirst().capabilityKey());
    assertEquals("user/events", canonical.getFirst().topic());
    assertEquals(RequirementFactKind.SERVICE_CALL, canonical.get(1).kind());
  }

  @Test
  void rejectsTriggerCapabilityCapturedAsServiceCall() {
    RequirementFact mixed =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "kafka-trigger-2",
            "Consume user events",
            "Petstore Ext",
            "getPetById",
            "user/events",
            "",
            "",
            "trigger-1");

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequirementTriggerRole.canonicalize(List.of(mixed)));

    assertTrue(thrown.getMessage().contains("kafka-trigger-2"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("SERVICE_CALL"), thrown.getMessage());
    assertTrue(thrown.getMessage().toLowerCase().contains("endpoint"), thrown.getMessage());
  }

  @Test
  void rejectsCatalogTriggerMixedWithANonTriggerEndpoint() {
    RequirementFact kafka =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-trigger-2",
            "Consume user events");
    RequirementFact otherEndpoint =
        new RequirementFact(
            "trigger-2",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "",
            "GET /orders");

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequirementTriggerRole.canonicalize(List.of(kafka, otherEndpoint)));

    assertTrue(thrown.getMessage().toLowerCase().contains("recapture"), thrown.getMessage());
  }
}

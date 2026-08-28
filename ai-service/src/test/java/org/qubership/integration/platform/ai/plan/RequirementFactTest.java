package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RequirementFactTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void bindsNamedIdentityFieldsWithoutReadingText() throws Exception {
    RequirementFact fact =
        mapper.readValue(
            """
            {
              "polarity":"POSITIVE",
              "kind":"ENDPOINT",
              "capabilityKey":"kafka-trigger-2",
              "text":"Consume user events from Kafka",
              "operation":"consumeUserEvent",
              "topic":"user/events"
            }
            """,
            RequirementFact.class);

    assertEquals("kafka-trigger-2", fact.capabilityKey());
    assertEquals("consumeUserEvent", fact.operation());
    assertEquals("user/events", fact.topic());
    assertEquals("", fact.httpMethod());
    assertEquals("", fact.path());
  }

  @Test
  void omittedIdentityFieldsDefaultToEmpty() throws Exception {
    RequirementFact positive =
        mapper.readValue(
            """
            {"polarity":"POSITIVE","text":"GET /greetings"}
            """,
            RequirementFact.class);
    RequirementFact negative =
        mapper.readValue(
            """
            {"polarity":"NEGATIVE","text":"No MCP"}
            """,
            RequirementFact.class);

    assertEquals(RequirementFactKind.BEHAVIOR, positive.kind());
    assertEquals(RequirementFactKind.CONSTRAINT, negative.kind());
    assertFalse(positive.sourceFactId().isBlank());
    assertEquals("", positive.capabilityKey());
    assertEquals("", positive.participant());
    assertEquals("", positive.operation());
    assertEquals("", positive.topic());
    assertEquals("", positive.httpMethod());
    assertEquals("", positive.path());
  }

  @Test
  void rejectsServiceCallWithoutServiceCallIdFromLlmToolCall() {
    assertThrows(
        JsonMappingException.class,
        () ->
            mapper.readValue(
                """
                {"polarity":"POSITIVE","kind":"SERVICE_CALL","text":"Petstore Ext getInventory"}
                """,
                RequirementFact.class));
  }

  @Test
  void unknownKindFallsBackViaPolarityDefault() throws Exception {
    RequirementFact fact =
        mapper.readValue(
            """
            {"polarity":"POSITIVE","kind":"NOT_A_REAL_KIND","text":"Something useful"}
            """,
            RequirementFact.class);

    assertEquals(RequirementFactKind.BEHAVIOR, fact.kind());
  }

  @Test
  void draftCaptureAcceptsServiceCallFactInsideCaptureWrapper() throws Exception {
    RequirementDraftCapture capture =
        mapper.readValue(
            """
            {
              "complete": true,
              "decision": "READY_FOR_PLAN",
              "assembledText": "HTTP trigger then Petstore Ext getInventory",
              "openQuestions": [],
              "facts": [
                {"polarity":"POSITIVE","kind":"ENDPOINT","text":"GET /health-proxy"},
                {
                  "polarity":"POSITIVE",
                  "kind":"SERVICE_CALL",
                  "text":"Petstore Ext getInventory",
                  "serviceCallId":"call-inventory"
                }
              ]
            }
            """,
            RequirementDraftCapture.class);

    assertEquals(DraftDecision.READY_FOR_PLAN, capture.decision());
    assertEquals(RequirementFactKind.SERVICE_CALL, capture.facts().get(1).kind());
  }

  @Test
  void rejectsServiceCallWithoutServiceCallIdEvenWhenSourceFactIdExists() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequirementFact(
                "fact-inventory",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.SERVICE_CALL,
                "",
                "Petstore Ext getInventory"));
  }

  @Test
  void explicitServiceCallIdAllowsDuplicateCallText() throws Exception {
    RequirementFact first =
        mapper.readValue(
            """
            {
              "polarity":"POSITIVE",
              "kind":"SERVICE_CALL",
              "text":"Call OM onTaskResult",
              "participant":"Order Management",
              "operation":"onTaskResult",
              "serviceCallId":"call-om-result"
            }
            """,
            RequirementFact.class);
    RequirementFact second =
        mapper.readValue(
            """
            {
              "polarity":"POSITIVE",
              "kind":"SERVICE_CALL",
              "text":"Call OM onTaskResult",
              "participant":"Order Management",
              "operation":"onTaskResult",
              "serviceCallId":"call-om-again"
            }
            """,
            RequirementFact.class);

    assertEquals("call-om-result", first.serviceCallId());
    assertEquals("call-om-result", first.sourceFactId());
    assertEquals("call-om-again", second.serviceCallId());
    assertEquals("call-om-again", second.sourceFactId());
    assertNotEquals(first.sourceFactId(), second.sourceFactId());
  }

  @Test
  void nonServiceFactDoesNotAcquireServiceCallId() throws Exception {
    RequirementFact fact =
        mapper.readValue(
            """
            {"polarity":"POSITIVE","kind":"ENDPOINT","text":"GET /greetings"}
            """,
            RequirementFact.class);

    assertEquals("", fact.serviceCallId());
    assertFalse(fact.sourceFactId().isBlank());
  }
}

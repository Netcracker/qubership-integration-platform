package org.qubership.integration.platform.ai.chat.chainplan;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ActiveChainPlanServicePublishTest {

  private static final String LEGACY_ROOT_PLAN_JSON =
      """
      {
        "name": "Petstore Smart Order Gateway",
        "description": "Chain to handle smart order placement.",
        "elements": [
          { "clientId": "http-trigger-1", "type": "http-trigger" }
        ]
      }
      """;

  private static final String CANONICAL_PLAN_JSON =
      """
      {
        "chain": { "name": "Canonical Chain", "description": "" },
        "elements": [
          { "clientId": "http-trigger-1", "type": "http-trigger" }
        ],
        "connections": []
      }
      """;

  @Inject ActiveChainPlanService activeChainPlanService;

  @Test
  void publishFromJsonAcceptsLegacyRootNameDescription() {
    String c = UUID.randomUUID().toString();
    PlanPublicationOutcome outcome =
        activeChainPlanService.publishFromJson(c, LEGACY_ROOT_PLAN_JSON);
    assertTrue(outcome.captured(), outcome.failureMessage());
    assertEquals("Petstore Smart Order Gateway", outcome.chainName());
    assertEquals(1, outcome.elementCount());
    assertTrue(activeChainPlanService.getActive(c).isPresent());
    assertEquals(
        "Petstore Smart Order Gateway",
        activeChainPlanService.getActive(c).orElseThrow().chainName());
  }

  @Test
  void publishFromJsonAcceptsCanonicalShape() {
    String c = UUID.randomUUID().toString();
    PlanPublicationOutcome outcome =
        activeChainPlanService.publishFromJson(c, CANONICAL_PLAN_JSON);
    assertTrue(outcome.captured());
    assertEquals("Canonical Chain", outcome.chainName());
  }

  @Test
  void publishFromJsonAcceptsLegacyConnectionAliases() {
    String c = UUID.randomUUID().toString();
    String planJson =
        """
        {
          "chain": { "name": "Alias Chain", "description": "" },
          "elements": [
            { "clientId": "trigger-1", "type": "http-trigger" },
            { "clientId": "script-1", "type": "script" }
          ],
          "connections": [
            { "from": "trigger-1", "to": "script-1" }
          ]
        }
        """;

    PlanPublicationOutcome outcome = activeChainPlanService.publishFromJson(c, planJson);

    assertTrue(outcome.captured(), outcome.failureMessage());
    var connection =
        activeChainPlanService.getActive(c).orElseThrow().plan().getConnections().getFirst();
    assertEquals("trigger-1", connection.getFromClientId());
    assertEquals("script-1", connection.getToClientId());
  }

  @Test
  void markdownCaptureAcceptsLegacyFencedJson() {
    String c = UUID.randomUUID().toString();
    String assistant =
        """
        Plan ready.

        ```json
        """
            + LEGACY_ROOT_PLAN_JSON
            + """
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, assistant);
    assertTrue(activeChainPlanService.getActive(c).isPresent());
    assertEquals(
        "Petstore Smart Order Gateway",
        activeChainPlanService.getActive(c).orElseThrow().chainName());
  }

  @Test
  void hitlAgreeAfterPublishSetsApproved() {
    String c = UUID.randomUUID().toString();
    assertTrue(activeChainPlanService.publishFromJson(c, CANONICAL_PLAN_JSON).captured());
    assertFalse(activeChainPlanService.isApproved(c));
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertTrue(activeChainPlanService.isApproved(c));
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("implementGatePending=true"));
  }

  @Test
  void publishWithPlaceholderOperationIdProducesOpenItem() {
    String c = UUID.randomUUID().toString();
    String planJson =
        """
        {
          "chain": { "name": "Binding debt chain", "description": "" },
          "elements": [
            {
              "clientId": "service-call-1",
              "type": "service-call",
              "expectedProperties": {
                "integrationSpecificationId": "model-x",
                "integrationOperationId": "operation-id-placeholder"
              }
            }
          ],
          "connections": []
        }
        """;
    PlanPublicationOutcome outcome = activeChainPlanService.publishFromJson(c, planJson);
    assertTrue(outcome.captured());
    assertTrue(outcome.openItemCount() > 0);
    assertFalse(activeChainPlanService.isApproved(c));
  }

  @Test
  void publishRejectsEmptyElements() {
    String c = UUID.randomUUID().toString();
    PlanPublicationOutcome outcome =
        activeChainPlanService.publishFromJson(
            c,
            """
            { "chain": { "name": "Empty", "description": "" }, "elements": [] }
            """);
    assertFalse(outcome.captured());
    assertEquals("PLAN_VALIDATION_ERROR", outcome.failureCode());
  }
}

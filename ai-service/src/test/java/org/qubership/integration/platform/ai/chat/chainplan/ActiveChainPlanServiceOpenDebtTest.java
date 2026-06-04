package org.qubership.integration.platform.ai.chat.chainplan;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ActiveChainPlanServiceOpenDebtTest {

  @Inject
  ActiveChainPlanService activeChainPlanService;

  @Test
  void captureStripsUnknownPropertyKeysAndAppendixListsOpenItems() {
    String c = "conv-open-debt-1";
    String text = """
        ```json
        {
          "chain": { "name": "DebtTest", "description": "" },
          "elements": [
            {
              "clientId": "http-trigger-1",
              "type": "http-trigger",
              "expectedProperties": { "integrationOperationId": "x", "totallyUnknownPropertyKey": 1 }
            }
          ],
          "connections": []
        }
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, text);
    var snap = activeChainPlanService.getActive(c);
    assertTrue(snap.isPresent());
    assertFalse(snap.get().openItems().isEmpty());
    assertTrue(
        snap.get().openItems().stream()
            .anyMatch(i -> i.kind() == PlanOpenItemKind.UNKNOWN_PROPERTY_KEY));

    String appendix = activeChainPlanService.formatPromptAppendix(c);
    assertTrue(appendix.contains("## Open plan items"));
    assertTrue(appendix.contains("openDebtFingerprint="));
  }

  @Test
  void captureAddsServiceBindingUnresolvedWhenServiceCallMissingOperationId() {
    String c = "conv-svc-bind-1";
    String text = """
        ```json
        {
          "chain": { "name": "BindTest", "description": "" },
          "elements": [
            {
              "clientId": "svc-1",
              "type": "service-call",
              "expectedProperties": { "integrationSystemId": "sys-1" }
            }
          ],
          "connections": []
        }
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, text);
    var snap = activeChainPlanService.getActive(c);
    assertTrue(snap.isPresent());
    assertTrue(
        snap.get().openItems().stream()
            .anyMatch(i -> i.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED));
    String appendix = activeChainPlanService.formatPromptAppendix(c);
    assertTrue(appendix.contains("SERVICE_BINDING_UNRESOLVED"), appendix);
  }

  @Test
  void captureAddsServiceBindingUnresolvedWhenHttpTriggerImplementedWithoutOperationId() {
    String c = "conv-http-bind-1";
    String text = """
        ```json
        {
          "chain": { "name": "HttpBind", "description": "" },
          "elements": [
            {
              "clientId": "ht-1",
              "type": "http-trigger",
              "expectedProperties": { "integrationSystemId": "sys-1" }
            }
          ],
          "connections": []
        }
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, text);
    var snap = activeChainPlanService.getActive(c);
    assertTrue(snap.isPresent());
    assertTrue(
        snap.get().openItems().stream()
            .anyMatch(i -> i.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED));
  }

  @Test
  void captureAddsServiceBindingUnresolvedWhenServiceCallHasPlaceholderOperationId() {
    String c = "conv-svc-placeholder";
    String text = """
        ```json
        {
          "chain": { "name": "PlaceholderBind", "description": "" },
          "elements": [
            {
              "clientId": "svc-ph",
              "type": "service-call",
              "expectedProperties": {
                "integrationSystemId": "364ea2f4-8918-4e47-9fc3-17652f1706d3",
                "integrationSpecificationId": "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7",
                "integrationOperationId": "operation-id-placeholder"
              }
            }
          ],
          "connections": []
        }
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, text);
    var snap = activeChainPlanService.getActive(c);
    assertTrue(snap.isPresent());
    assertTrue(
        snap.get().openItems().stream()
            .anyMatch(i -> i.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED));
    String appendix = activeChainPlanService.formatPromptAppendix(c);
    assertTrue(appendix.contains("SERVICE_BINDING_UNRESOLVED"), appendix);
  }

  @Test
  void hitlAgreeBlockedWhenServiceCallHasPlaceholderOperationId() {
    String c = "conv-svc-placeholder-hitl";
    String text = """
        ```json
        {
          "chain": { "name": "PlaceholderBind", "description": "" },
          "elements": [
            {
              "clientId": "svc-ph",
              "type": "service-call",
              "expectedProperties": {
                "integrationSystemId": "364ea2f4-8918-4e47-9fc3-17652f1706d3",
                "integrationSpecificationId": "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7",
                "integrationOperationId": "operation-id-placeholder"
              }
            }
          ],
          "connections": []
        }
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, text);
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertFalse(activeChainPlanService.isApproved(c));
  }

  @Test
  void approvePlanForBuildBlockedWhenServiceCallHasPlaceholderOperationId() {
    String c = "conv-svc-placeholder-build";
    String text = """
        ```json
        {
          "chain": { "name": "PlaceholderBind", "description": "" },
          "elements": [
            {
              "clientId": "svc-ph",
              "type": "service-call",
              "expectedProperties": {
                "integrationSystemId": "364ea2f4-8918-4e47-9fc3-17652f1706d3",
                "integrationSpecificationId": "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7",
                "integrationOperationId": "operation-id-placeholder"
              }
            }
          ],
          "connections": []
        }
        ```
        """;
    activeChainPlanService.captureFromAssistantText(c, text);
    assertFalse(activeChainPlanService.approvePlanForBuild(c));
    assertFalse(activeChainPlanService.isApproved(c));
  }

  @Test
  void captureSkipsServiceBindingDebtWhenUserAcceptedUnbound() {
    String c = "conv-svc-bind-2";
    activeChainPlanService.captureFromAssistantText(
        c,
        """
            ```json
            {
              "chain": { "name": "BindTest2", "description": "" },
              "elements": [
                {
                  "clientId": "svc-2",
                  "type": "service-call",
                  "bindingStatus": "user_accepted_unbound",
                  "expectedProperties": {}
                }
              ],
              "connections": []
            }
            ```
            """);
    var snap = activeChainPlanService.getActive(c);
    assertTrue(snap.isPresent());
    assertTrue(
        snap.get().openItems().stream()
            .noneMatch(i -> i.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED));
  }

  @Test
  void markAllOpenPlanItemsDismissedByUserClearsBlockingFingerprint() {
    String c = "conv-open-debt-2";
    activeChainPlanService.captureFromAssistantText(
        c,
        """
            ```json
            {
              "chain": { "name": "DebtTest2", "description": "" },
              "elements": [
                {
                  "clientId": "http-trigger-2",
                  "type": "http-trigger",
                  "expectedProperties": { "bogusUnknown": true }
                }
              ],
              "connections": []
            }
            ```
            """);
    assertFalse(activeChainPlanService.openDebtFingerprintForTest(c).isBlank());
    activeChainPlanService.markAllOpenPlanItemsDismissedByUser(c);
    assertEquals("", activeChainPlanService.openDebtFingerprintForTest(c));
    String appendix = activeChainPlanService.formatPromptAppendix(c);
    assertTrue(appendix.contains("User-dismissed plan debt"));
  }
}

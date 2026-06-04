package org.qubership.integration.platform.ai.chat.chainplan;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ActiveChainPlanServiceImportGateTest {

  @Inject
  ActiveChainPlanService activeChainPlanService;

  @Inject
  ConversationPlanningDiaryService planningDiaryService;

  @Test
  void rejectsCaptureWhenImportPendingAndServiceCallUnbound() {
    String c = "conv-import-gate-reject";
    planningDiaryService.recordImportCandidate(
        c,
        "Service Catalog Management",
        "INTERNAL",
        "S.ActProv.SvcCat",
        "2026.1@1",
        "api",
        "Service Catalog",
        "test");

    String planJson =
        """
        {
          "chain": { "name": "ImportGateTest", "description": "" },
          "elements": [
            {
              "clientId": "svc-1",
              "type": "service-call",
              "expectedProperties": { "integrationSystemId": "placeholder-sys" }
            }
          ],
          "connections": []
        }
        """;

    var outcome = activeChainPlanService.publishFromJson(c, planJson);
    assertFalse(outcome.captured());
    assertTrue(
        outcome.failureMessage().contains("IMPORT_SPECIFICATION"),
        outcome.failureMessage());
    assertTrue(activeChainPlanService.getActive(c).isEmpty());
  }

  @Test
  void allowsCaptureWhenImportPendingButUserAcceptedUnbound() {
    String c = "conv-import-gate-unbound";
    planningDiaryService.recordImportCandidate(
        c,
        "Service Catalog Management",
        "INTERNAL",
        "S.ActProv.SvcCat",
        "2026.1@1",
        "api",
        "Service Catalog",
        "test");

    String planJson =
        """
        {
          "chain": { "name": "UnboundOk", "description": "" },
          "elements": [
            {
              "clientId": "svc-1",
              "type": "service-call",
              "bindingStatus": "user_accepted_unbound",
              "expectedProperties": {}
            }
          ],
          "connections": []
        }
        """;

    var outcome = activeChainPlanService.publishFromJson(c, planJson);
    assertTrue(outcome.captured(), outcome.failureMessage());
  }
}

package org.qubership.integration.platform.ai.chat.chainplan;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ActiveChainPlanServiceCaptureTest {

  private static final String VALID_NESTED_PLAN =
      """
      ```json
      {
        "chain": { "name": "My Integration Chain", "description": "" },
        "elements": [
          { "clientId": "http-trigger-1", "type": "http-trigger" },
          {
            "clientId": "condition-1",
            "type": "condition",
            "children": [
              { "clientId": "if-1", "type": "if",
                "expectedProperties": { "priority": 0, "condition": "true" } },
              { "clientId": "else-1", "type": "else" }
            ]
          }
        ],
        "connections": [
          { "fromClientId": "http-trigger-1", "toClientId": "condition-1" }
        ]
      }
      ```
      """;

  /**
   * LLM sometimes appends Java-style line comments after property values; strict JSON would fail
   * without ALLOW_COMMENTS.
   */
  private static final String VALID_WITH_LINE_COMMENT =
      """
      ```json
      {
        "chain": { "name": "Comment Chain", "description": "" },
        "elements": [
          { "clientId": "http-trigger-1", "type": "http-trigger" },
          {
            "clientId": "condition-1",
            "type": "condition",
            "children": [
              {
                "clientId": "if-1",
                "type": "if",
                "expectedProperties": {
                  "priority": 0,
                  "condition": "true"  // Placeholder condition, please specify if needed
                }
              },
              { "clientId": "else-1", "type": "else" }
            ]
          }
        ],
        "connections": [
          { "fromClientId": "http-trigger-1", "toClientId": "condition-1" }
        ]
      }
      ```
      """;

  private static final String PLAN_WITH_EXPLICIT_SOURCE =
      """
      ```json
      {
        "sourceIdsDocumentId": "QIP.INT.IDS.FromJson",
        "sourceIdsAttachmentObjectKey": "json/key.md",
        "chain": { "name": "Src", "description": "" },
        "elements": [
          { "clientId": "http-trigger-1", "type": "http-trigger" }
        ],
        "connections": []
      }
      ```
      """;

  @Inject ActiveChainPlanService activeChainPlanService;

  @Inject ConversationPlanningDiaryService planningDiaryService;

  @Test
  void unparseablePlanJsonSkipsCaptureActiveUnchanged() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(c, VALID_NESTED_PLAN);
    Optional<ActiveChainPlanSnapshot> before = activeChainPlanService.getActive(c);
    assertTrue(before.isPresent());
    activeChainPlanService.captureFromAssistantText(c, "```json\n{ not json \n```");
    Optional<ActiveChainPlanSnapshot> active = activeChainPlanService.getActive(c);
    assertTrue(active.isPresent());
    assertEquals(before.get().planId(), active.get().planId());
  }

  @Test
  void validPlanBeforeApproveBecomesActive() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(c, VALID_NESTED_PLAN);
    Optional<ActiveChainPlanSnapshot> active = activeChainPlanService.getActive(c);
    assertTrue(active.isPresent());
    assertTrue(active.get().rejectionErrors().isEmpty());
    assertEquals("My Integration Chain", active.get().chainName());
  }

  @Test
  void validPlanWithLineCommentInFencedJsonCapturesSuccessfully() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(c, VALID_WITH_LINE_COMMENT);
    Optional<ActiveChainPlanSnapshot> active = activeChainPlanService.getActive(c);
    assertTrue(active.isPresent());
    assertTrue(active.get().rejectionErrors().isEmpty());
    assertEquals("Comment Chain", active.get().chainName());
  }

  @Test
  void publishFromJsonIdenticalPayloadDedupesWithoutNewPlanId() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(c, VALID_NESTED_PLAN);
    String planId = activeChainPlanService.getActive(c).orElseThrow().planId();
    var outcome = activeChainPlanService.publishFromJson(c, VALID_NESTED_PLAN);
    assertTrue(outcome.captured(), outcome.failureMessage());
    assertEquals(planId, outcome.planId());
    assertEquals(planId, activeChainPlanService.getActive(c).orElseThrow().planId());
    assertTrue(activeChainPlanService.peekLatestArchiveForTest(c).isEmpty());
  }

  @Test
  void secondCaptureAfterApprovedReplacesActiveNewPlanId() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(c, VALID_NESTED_PLAN);
    String firstId = activeChainPlanService.getActive(c).orElseThrow().planId();
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertTrue(activeChainPlanService.isApproved(c));
    activeChainPlanService.captureFromAssistantText(c, VALID_NESTED_PLAN);
    assertFalse(activeChainPlanService.isApproved(c));
    String secondId = activeChainPlanService.getActive(c).orElseThrow().planId();
    assertNotEquals(firstId, secondId);
    Optional<ActiveChainPlanSnapshot> archived = activeChainPlanService.peekLatestArchiveForTest(c);
    assertTrue(archived.isPresent());
    assertTrue(archived.get().rejectionErrors().isEmpty());
    assertEquals("My Integration Chain", archived.get().chainName());
  }

  @Test
  void captureFillsSourceIdsFromPlanningDiaryWhenJsonOmitsThem() {
    String c = UUID.randomUUID().toString();
    planningDiaryService.recordDesignHintsFromUserTurn(
        c, "See QIP.INT.IDS.FromDiary in attachment", List.of("tenant/attach-key.md"));
    activeChainPlanService.captureFromAssistantText(c, VALID_NESTED_PLAN);
    var plan = activeChainPlanService.getActive(c).orElseThrow().plan();
    assertEquals("QIP.INT.IDS.FromDiary", plan.getSourceIdsDocumentId());
    assertEquals("tenant/attach-key.md", plan.getSourceIdsAttachmentObjectKey());
  }

  @Test
  void capturePreservesExplicitSourceIdsFromJsonOverDiary() {
    String c = UUID.randomUUID().toString();
    planningDiaryService.recordDesignHintsFromUserTurn(
        c, "QIP.INT.IDS.Other", List.of("other/key.md"));
    activeChainPlanService.captureFromAssistantText(c, PLAN_WITH_EXPLICIT_SOURCE);
    var plan = activeChainPlanService.getActive(c).orElseThrow().plan();
    assertEquals("QIP.INT.IDS.FromJson", plan.getSourceIdsDocumentId());
    assertEquals("json/key.md", plan.getSourceIdsAttachmentObjectKey());
  }
}

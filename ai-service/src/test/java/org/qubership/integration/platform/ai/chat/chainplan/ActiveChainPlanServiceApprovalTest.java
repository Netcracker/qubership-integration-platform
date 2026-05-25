package org.qubership.integration.platform.ai.chat.chainplan;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ActiveChainPlanServiceApprovalTest {

  private static final String MINIMAL_VALID_PLAN =
      """
      ```json
      {
        "chain": { "name": "planA", "description": "" },
        "elements": [
          { "clientId": "t", "type": "http-trigger" }
        ],
        "connections": []
      }
      ```
      """;

  @Inject ActiveChainPlanService activeChainPlanService;

  @BeforeEach
  void resetPlanApprovalStub() {
    TestPlanApprovalGate.reset();
  }

  @AfterEach
  void cleanupPlanApprovalStub() {
    TestPlanApprovalGate.reset();
  }

  @Test
  void bareAgreeInChatDoesNotApprove() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planBare"));
    TestPlanApprovalGate.setApproveNext(false);
    activeChainPlanService.onUserMessage(c, "Agree");
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=false"));
  }

  @Test
  void llmApprovalApproves() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planPhrase"));
    TestPlanApprovalGate.setApproveNext(true);
    activeChainPlanService.onUserMessage(c, "approve the plan");
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=true"));
    assertTrue(
        activeChainPlanService.describeActiveForLog(c).contains("implementGatePending=true"));
    assertTrue(activeChainPlanService.needsImplementGateHitl(c));
  }

  @Test
  void llmApprovalWithUiAppendixApprovesWhenStubYes() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planAppendix"));
    TestPlanApprovalGate.setApproveNext(true);
    activeChainPlanService.onUserMessage(
        c, "I agree with the plan\n---\n## Current Chain: Foo (ID: abc)");
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=true"));
  }

  @Test
  void approvePlanForBuildSkipsImplementGateHitl() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planBuild"));
    assertTrue(activeChainPlanService.approvePlanForBuild(c));
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=true"));
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("implementGateAck=true"));
    assertFalse(activeChainPlanService.needsImplementGateHitl(c));
  }

  @Test
  void hitlAgreeOptionWithCapturedPlanSetsApproved() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planHitl"));
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=false"));
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=true"));
  }

  @Test
  void hitlAgreeBeforeCaptureAppliesApprovalAfterCapture() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("(none)"));
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planDeferred"));
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=true"));
    assertTrue(
        activeChainPlanService.describeActiveForLog(c).contains("implementGatePending=true"));
  }

  @Test
  void secondCaptureAfterApprovedReplacesActiveAndRevokesApproval() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planRevoke"));
    String firstId = activeChainPlanService.getActive(c).orElseThrow().planId();
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertTrue(activeChainPlanService.isApproved(c));
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planRevoke"));
    assertFalse(activeChainPlanService.isApproved(c));
    String secondId = activeChainPlanService.getActive(c).orElseThrow().planId();
    assertNotEquals(firstId, secondId);
    assertTrue(activeChainPlanService.peekLatestArchiveForTest(c).isPresent());
  }

  @Test
  void longIntentDoesNotApproveWhenStubNo() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planLong"));
    TestPlanApprovalGate.setApproveNext(false);
    activeChainPlanService.onUserMessage(
        c,
        "approve the plan and also explain everything about X and keep typing past one hundred"
            + " twenty chars boundary");
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=false"));
  }

  @Test
  void okWithInlinedIdsImplementChainPhraseDoesNotArchiveActivePlan() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "Petstore Smart Order Gateway"));
    assertTrue(activeChainPlanService.getActive(c).isPresent());
    String effectiveWithIds =
        """
ok

---

> Implement chain `f3a81425-9e8d-5f2c-b033-3c7d9e0f2b81` from QIP.INT.IDS.PetstoreOrderConditional.
""";
    activeChainPlanService.onUserMessage(c, effectiveWithIds);
    assertTrue(activeChainPlanService.getActive(c).isPresent());
    assertFalse(activeChainPlanService.describeActiveForLog(c).contains("(none)"));
  }

  @Test
  void createDifferentChainResetsApproved() {
    String c = UUID.randomUUID().toString();
    activeChainPlanService.captureFromAssistantText(
        c, MINIMAL_VALID_PLAN.replace("planA", "planAchain"));
    activeChainPlanService.applyHitlAgreeOptionChosen(c);
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("approved=true"));
    activeChainPlanService.onUserMessage(c, "create chain planBbranch");
    assertTrue(activeChainPlanService.describeActiveForLog(c).contains("(none)"));
  }
}

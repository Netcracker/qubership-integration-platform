package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;

class ProvidedIdsFlowTest {

  @Test
  void descriptorLoopsContinueAndListensAtProvidedIdsGates() {
    ProductPipelineProfile profile = createChainV2();
    ProvidedIdsFlow flow = new ProvidedIdsFlow(profile, mock(ProvidedIdsFlowTasks.class));

    List<String> taskNames =
        flow.descriptor().getDo().stream().map(item -> item.getName()).toList();

    assertEquals(
        List.of(
            "executeStage",
            "routeDecision",
            "waitForInput",
            "restoreAfterInput",
            "afterInput",
            "waitForRequirementApproval",
            "restoreAfterRequirementApproval",
            "afterRequirementApproval",
            "waitForIdsApproval",
            "restoreAfterIdsApproval",
            "afterIdsApproval",
            "waitForPlanApproval",
            "restoreAfterPlanApproval",
            "afterPlanApproval",
            "waitForImplementation",
            "restoreAfterImplementation",
            "afterImplementation",
            "waitForRetry",
            "restoreAfterRetry",
            "afterRetry"),
        taskNames);
    assertTrue(flow.ownsStage("ids-entry"));
    assertTrue(flow.ownsStage("requirement-discovery"));
    assertTrue(flow.ownsStage("requirement-analysis"));
    assertTrue(flow.ownsStage("design-input"));
    assertTrue(flow.ownsStage("design-planning"));
    assertTrue(flow.ownsStage("design-execution"));
    assertTrue(flow.ownsStage("materialization"));
    assertFalse(flow.ownsStage("unknown"));
  }

  @Test
  void runContextRetainsPinnedProfileAndManifestIdentity() {
    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext("run-1", "create-chain", "2", "manifest-sha", null);

    assertEquals("run-1", context.runId());
    assertEquals("create-chain", context.profileId());
    assertEquals("2", context.profileVersion());
    assertEquals("manifest-sha", context.runManifestDigest());
  }

  @Test
  void runContextRoutesEachProvidedIdsGate() {
    ProvidedIdsFlow.RunContext input =
        new ProvidedIdsFlow.RunContext("run-1", "create-chain", "2", "digest", "WAIT_FOR_INPUT");
    ProvidedIdsFlow.RunContext requirement =
        input.withDecision("WAIT_FOR_REQUIREMENT_APPROVAL");
    ProvidedIdsFlow.RunContext ids =
        input.withDecision("WAIT_FOR_IDS_APPROVAL");
    ProvidedIdsFlow.RunContext plan =
        input.withDecision("WAIT_FOR_PLAN_APPROVAL");
    ProvidedIdsFlow.RunContext implement =
        input.withDecision("WAIT_FOR_IMPLEMENTATION");
    ProvidedIdsFlow.RunContext cont = input.withDecision("CONTINUE");
    ProvidedIdsFlow.RunContext retry = input.withRetry(Duration.ofMillis(50L), 1);
    ProvidedIdsFlow.RunContext reopen = input.withDecision("WAIT_FOR_REQUIREMENT_APPROVAL");
    ProvidedIdsFlow.RunContext leftoverReopen = input.withDecision("REOPEN");
    ProvidedIdsFlow.RunContext done = input.withDecision("STOP");

    assertTrue(input.waitForInput());
    assertTrue(requirement.waitForRequirementApproval());
    assertTrue(ids.waitForIdsApproval());
    assertTrue(plan.waitForPlanApproval());
    assertTrue(implement.waitForImplementation());
    assertTrue(cont.reenterStage());
    assertTrue(reopen.waitForRequirementApproval());
    assertFalse(reopen.reenterStage());
    assertFalse(leftoverReopen.reenterStage());
    assertTrue(retry.waitForRetry());
    assertFalse(retry.reenterStage());
    assertEquals(1, retry.technicalRetriesUsed());
    assertEquals("PT0.05S", retry.retryDelay());
    assertFalse(done.reenterStage());
    assertFalse(done.waitForRetry());
    assertFalse(done.waitForInput());
    assertFalse(done.waitForRequirementApproval());
    assertFalse(done.waitForIdsApproval());
    assertFalse(done.waitForPlanApproval());
    assertFalse(done.waitForImplementation());
  }

  private static ProductPipelineProfile createChainV2() {
    try (InputStream input =
        ProvidedIdsFlowTest.class.getResourceAsStream(
            "/product-pipelines/profiles/create-chain-v2.yaml")) {
      if (input == null) {
        throw new IllegalStateException("create-chain-v2 profile is missing");
      }
      return ProductPipelineProfileParser.parse(input);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}

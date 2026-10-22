package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

class RequirementFailureConsistencyTest {

  @Test
  void acceptedHttpResponseContinuationMustNotBeModeledAsPropagation() {
    var input = input(ServiceCallFailureMode.PROPAGATE);
    var pending = RequirementCaptureProjection.pending(input, List.of(binding("remote"), binding("delivery")));
    assertTrue(pending.stream().anyMatch(item -> "FAILURE_MODE_CONFLICT".equals(item.code())
        && "remote".equals(item.interactionId())), pending.toString());
    assertTrue(!RequirementCaptureProjection.toDraft(input, null,
        List.of(binding("remote"), binding("delivery")), "1", "hash").readyForPlan());
  }

  static DraftInput input(ServiceCallFailureMode mode) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work", null, null, null),
        new InteractionInput("remote", Direction.OUTBOUND, "Remote", "create", null, mode, null),
        new InteractionInput("delivery", Direction.OUTBOUND, "Receiver", "deliver", null, null, null)),
        List.of(new TransitionInput("entry", "remote"), new TransitionInput("remote", "delivery"))), List.of(
            new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Process the request."),
            new FactInput("call-options", List.of("remote"), FactKind.CONSTRAINT, Polarity.POSITIVE,
                "Return all HTTP responses to the ordinary response step.", null, null,
                new RequirementElementIntent("remote", "service-call", RequirementElementIntent.Placement.ANCHOR,
                    "remote", "response", null, "Remote request",
                    List.of(new PlanProperty("errorThrowing", "false")), List.of())),
            new FactInput("render-result", List.of("remote"), FactKind.BEHAVIOR, Polarity.POSITIVE,
                "Render each response and deliver its success or failure fields.", null, null,
                new RequirementElementIntent("response", "script", RequirementElementIntent.Placement.LOCAL_NODE,
                    "remote", "delivery", "remote", "Render result", List.of(), List.of()))),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/work", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  static CatalogBindingHint binding(String owner) {
    return new CatalogBindingHint("3", owner, owner, "POST /" + owner, "system", "group", "spec",
        "operation-" + owner, "HTTP", "POST", "/" + owner, "2026.1", Instant.EPOCH, "evidence-" + owner);
  }
}

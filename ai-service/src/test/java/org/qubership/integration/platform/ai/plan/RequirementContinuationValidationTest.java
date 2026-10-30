package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementContinuationValidationTest {
  @ParameterizedTest
  @ValueSource(strings = {"Intake", ""})
  void continuationRequiresNativeAnchorMetadataWithoutInventingAName(String name) throws Exception {
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    String chat = "anchor-continuation-" + UUID.randomUUID();
    store.beginTurn(chat, "Receive POST on /work using an HTTP trigger."
        + (name.isEmpty() ? "" : " Name the trigger " + name + "."));
    var goal = new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Accept incoming work.");
    var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /work", null, null, null)), List.of()), List.of(goal),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/work", null, null, null)), List.of(), new DraftSettings(null, null));

    try (var ignored = ToolSession.open(chat)) {
      var captured = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      assertTrue(store.get(chat).orElseThrow().openQuestions().isEmpty());

      var refused = mapper.readValue(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertEquals(RequirementCaptureResult.NextAction.REPAIR_CAPTURE, refused.nextAction());
      assertTrue(refused.issues().stream().anyMatch(issue -> "MISSING_ANCHOR_INTENT".equals(issue.code())
          && "entry".equals(issue.entityId())), refused.toString());
      assertEquals(List.of(goal), store.get(chat).orElseThrow().authoredDraft().facts());

      var removal = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of("entry"), List.of(), List.of(), List.of(), null);
      var discarded = mapper.readValue(tools.updateRequirementDraft(removal), RequirementCaptureResult.class);
      assertFalse(discarded.accepted(), discarded.toString());
      assertEquals(input.capabilities(), store.get(chat).orElseThrow().authoredDraft().capabilities());

      var intent = new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
          "entry", null, null, name.isEmpty() ? null : name, List.of(), List.of());
      var naming = new FactInput("entry-metadata", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          name.isEmpty() ? "Use the selected HTTP trigger." : "HTTP trigger named " + name + ".", null, null, intent, new RequirementCaptureInput.ClaimEvidenceInput(
              "current", name.isEmpty() ? "using an HTTP trigger." : "Name the trigger " + name + "."));
      var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(naming), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      var corrected = mapper.readValue(tools.updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertTrue(corrected.accepted(), corrected.toString());
      var continued = mapper.readValue(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertTrue(continued.accepted(), continued.toString());
      assertEquals(List.of(goal, naming), store.get(chat).orElseThrow().authoredDraft().facts());
    }
  }
}

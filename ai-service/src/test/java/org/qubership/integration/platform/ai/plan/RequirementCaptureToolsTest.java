package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;

class RequirementCaptureToolsTest {

  @Test
  void savesPartialDraftAndAppliesOneFocusedUpdate() throws Exception {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindingAdapter, mapper);
    String conversationId = "capture-test-" + UUID.randomUUID();
    DraftInput first = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE,
            "Create a task.")), List.of(), List.of(), new DraftSettings(false, null));

    try (ToolSession.Handle ignored = ToolSession.open(conversationId)) {
      RequirementCaptureResult captured = mapper.readValue(
          tools.captureRequirementDraft(first), RequirementCaptureResult.class);
      assertTrue(captured.accepted());
      assertTrue(captured.changed());
      assertEquals(RequirementCaptureResult.Readiness.PARTIAL, captured.readiness());
      assertNotNull(captured.revision());
      assertEquals(first, store.get(conversationId).orElseThrow().authoredDraft());

      RequirementCaptureResult duplicate = mapper.readValue(
          tools.captureRequirementDraft(first), RequirementCaptureResult.class);
      assertTrue(duplicate.accepted());
      assertFalse(duplicate.changed());
      assertEquals(captured.revision(), duplicate.revision());

      FactInput changedFact = new FactInput("goal", List.of(), FactKind.GOAL,
          Polarity.POSITIVE, "Create one task for each input.");
      DraftUpdate change = new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(changedFact), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      RequirementCaptureResult updated = mapper.readValue(
          tools.updateRequirementDraft(change), RequirementCaptureResult.class);
      assertTrue(updated.accepted());
      assertTrue(updated.changed());
      assertEquals(changedFact, updated.draft().facts().getFirst());
      assertEquals(changedFact, store.get(conversationId).orElseThrow().authoredDraft()
          .facts().getFirst());
      assertEquals(updated.revision(), mapper.readValue(
          tools.readRequirementDraft(), RequirementCaptureResult.class).revision());
    }
  }
}

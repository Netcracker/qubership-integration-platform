package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;

class TargetFolderChoiceResolverTest {

  @Test
  void oneFolderPathWritesTargetFolderPathAndOtherAnswersStayUnresolved() throws Exception {
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var folders = mock(CatalogFolders.class);
    when(folders.tree()).thenReturn(List.of(
        new CatalogFolders.Folder("f1", "A", null, "A"),
        new CatalogFolders.Folder("f2", "Salesforce", "f1", "A/Salesforce"),
        new CatalogFolders.Folder("f3", "Salesforce", null, "Salesforce")));
    var mapper = new ObjectMapper().findAndRegisterModules();
    var feedback = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper));
    var tools = new RequirementCaptureTools(store, adapter, mapper,
        new CaptureToolOutcomeGateway(feedback.fingerprintStore(), feedback), feedback, null, folders);
    var resolver = new TargetFolderChoiceResolver(folders, tools);
    String chat = "folder-choice-" + UUID.randomUUID();
    store.beginTurn(chat, "Create a task in A/Salesforce.");
    var draft = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Create a task.")),
        List.of(), List.of(), new DraftSettings(null, null));
    try (var ignored = ToolSession.open(chat)) {
      var captured = mapper.readValue(tools.captureRequirementDraft(draft), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      assertTrue(resolver.selection("Salesforce").isEmpty(), "selection(Salesforce)");
      assertTrue(resolver.selection("Nope").isEmpty(), "selection(Nope)");
      assertNull(resolver.apply("Nope"), "apply(Nope)");
      assertEquals("A/Salesforce", resolver.selection("A/Salesforce").orElseThrow().settings().targetFolderPath(),
          "selection(A/Salesforce)");
      var applied = mapper.readValue(resolver.apply("A/Salesforce"), RequirementCaptureResult.class);
      assertTrue(applied.accepted(), applied.toString());
      assertEquals("A/Salesforce", store.get(chat).orElseThrow().authoredDraft().settings().targetFolderPath(),
          "apply(A/Salesforce)");
    }
  }
}

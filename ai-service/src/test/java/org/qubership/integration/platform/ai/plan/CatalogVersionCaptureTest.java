package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.lookup.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class CatalogVersionCaptureTest {
  @Test
  void draftCaptureOffersTheNewestVersionAndRestoresTheConfirmedBindingAfterReload() {
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("task", Direction.INBOUND, "Salesforce WFM", "createTask", "Create a task", null, null)),
        List.of()), List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Create a task")),
        List.of(new CapabilityInput("task", "http-trigger", HttpMode.CATALOG, HttpMethod.POST, null, null, null, null)),
        List.of(), new DraftSettings(false, SystemType.EXTERNAL));
    var draft = RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
    var drafts = new RequirementDraftStore();
    drafts.put("chat", draft);
    var blobs = new InMemoryArtifactBlobStore();
    var json = new ObjectMapper().findAndRegisterModules();
    var resolutions = new ConversationApiResolutions();
    var selections = new CatalogVersionSelection(blobs, json, drafts, resolutions);
    var lookup = mock(CatalogOperationLookup.class);
    var older = match("old", "1.0.0");
    var newest = match("new", "2.0.0");
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.Ambiguous(
        List.of("op-old", "op-new"), List.of(older, newest)));
    var adapter = new RequirementDraftTool(drafts, null, null, null, resolutions, null, lookup);
    adapter.versionSelections = selections;

    assertTrue(adapter.canonicalBindings(input, draft, "chat").isEmpty());
    var pending = selections.pending("chat").orElseThrow();
    assertEquals("new", pending.proposal().recommendation().specificationId());
    selections.confirm("chat", pending);

    var reloadedResolutions = new ConversationApiResolutions();
    var reloaded = new RequirementDraftTool(drafts, null, null, null, reloadedResolutions, null, lookup);
    reloaded.versionSelections = new CatalogVersionSelection(blobs, json, drafts, reloadedResolutions);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.Exact(newest));
    var bindings = reloaded.canonicalBindings(input, draft, "chat");

    assertEquals(1, bindings.size());
    assertEquals("new", bindings.getFirst().specificationId());
    assertEquals("op-new", bindings.getFirst().integrationOperationId());
    var queries = ArgumentCaptor.forClass(CatalogQuery.class);
    verify(lookup, times(2)).resolve(queries.capture());
    assertEquals("2.0.0", queries.getAllValues().getLast().release());
    assertEquals(List.of("op-new"), queries.getAllValues().getLast().namedInRequest());
  }

  private static CatalogMatch match(String id, String version) {
    return new CatalogMatch("sf", "group", id, "op-" + id, "Salesforce WFM", "http", "POST", "/tasks",
        "createTask", "catalog-read:" + id, version);
  }
}

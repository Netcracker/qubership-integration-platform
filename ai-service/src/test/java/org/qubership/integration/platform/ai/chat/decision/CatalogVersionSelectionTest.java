package org.qubership.integration.platform.ai.chat.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.plan.ConversationApiResolutions;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

class CatalogVersionSelectionTest {
  private static final RequirementFlow.Interaction CALL = new RequirementFlow.Interaction(
      "task", RequirementFlow.Direction.OUTBOUND, "Salesforce WFM", "createTask", "Create a task");

  @ParameterizedTest
  @CsvSource({"1.9,1.10", "1.0.0-rc.1,1.0.0", "1.0.0-pr1095-20261016-r2,1.0.0", "2.0,10.0"})
  void recommendsTheNewestVersionByNumericPrecedence(String older, String newer) {
    assertEquals("new", CatalogVersionSelection.newest(List.of(match("old", older), match("new", newer)))
        .orElseThrow().specificationId());
  }

  @Test
  void leavesAnAmbiguousServiceOrVersionLabelForClarification() {
    var other = new CatalogMatch("other-system", "group", "other", "other-op", "Other API", "http",
        "POST", "/tasks", "createTask", "catalog-read:other", "2.0.0");
    assertTrue(CatalogVersionSelection.newest(List.of(match("old", "1.0.0"), other)).isEmpty());
    assertTrue(CatalogVersionSelection.newest(List.of(match("old", "1.0.0"), match("new", "1.0.0"))).isEmpty());
    assertTrue(CatalogVersionSelection.newest(List.of(match("old", "stable"), match("new", "latest"))).isEmpty());
  }

  @Test
  void reloadsTheCardAndKeepsTheConfirmedCatalogIdentity() {
    var blobs = new InMemoryArtifactBlobStore();
    var json = new ObjectMapper().findAndRegisterModules();
    var drafts = drafts();
    var resolutions = new ConversationApiResolutions();
    var selections = new CatalogVersionSelection(blobs, json, drafts, resolutions);
    selections.propose("chat", CALL, List.of(match("old", "1.0.0"), match("new", "2.0.0")));
    var pending = selections.pending("chat").orElseThrow();
    var reloaded = new CatalogVersionSelection(blobs, json, drafts, resolutions);
    assertEquals(pending, reloaded.pending("chat").orElseThrow());
    var card = reloaded.decision(pending, 7, "Use version 2.0.0?");
    assertEquals(List.of("approve", "request-changes"), card.actions());
    assertEquals(pending.hash(), card.artifactHash());

    var selected = reloaded.confirm("chat", pending);

    assertEquals("new", selected.specificationId());
    assertEquals("op-new", selected.integrationOperationId());
    assertTrue(reloaded.pending("chat").isEmpty());
    assertEquals(selected, new CatalogVersionSelection(blobs, json, drafts, new ConversationApiResolutions())
        .confirmed("chat", CALL).orElseThrow());
    assertEquals(selected, resolutions.forInteraction("chat", "task").orElseThrow().binding());
    reloaded.confirm("chat", CALL, match("old", "1.0.0"));
    assertEquals("old", new CatalogVersionSelection(blobs, json, drafts, new ConversationApiResolutions())
        .confirmed("chat", CALL).orElseThrow().specificationId());
  }

  @Test
  void changesTheCardBindingWhenRequirementsChangeAndSupportsDeclining() {
    var drafts = drafts();
    var selections = new CatalogVersionSelection(new InMemoryArtifactBlobStore(),
        new ObjectMapper().findAndRegisterModules(), drafts, new ConversationApiResolutions());
    selections.propose("chat", CALL, List.of(match("old", "1.0.0"), match("new", "2.0.0")));
    var previous = selections.pending("chat").orElseThrow();
    drafts.put("chat", new RequirementDraft(false, "Updated requirements")
        .withFlow(new RequirementFlow(List.of(CALL), List.of())));
    var current = selections.pending("chat").orElseThrow();
    assertNotEquals(previous.hash(), current.hash());
    selections.dismiss("chat", current);
    assertTrue(selections.pending("chat").isEmpty());
  }

  private static RequirementDraftStore drafts() {
    var drafts = new RequirementDraftStore();
    drafts.put("chat", new RequirementDraft(false, "Create tasks")
        .withFlow(new RequirementFlow(List.of(CALL), List.of())));
    return drafts;
  }

  private static CatalogMatch match(String id, String version) {
    return new CatalogMatch("sf", "group", id, "op-" + id, "Salesforce WFM", "http",
        "POST", "/tasks", "createTask", "catalog-read:" + id, version);
  }
}

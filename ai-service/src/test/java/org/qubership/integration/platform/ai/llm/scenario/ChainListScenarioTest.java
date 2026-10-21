package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder;
import org.qubership.integration.platform.ai.chain.folder.ChainFilter;
import org.qubership.integration.platform.ai.chain.folder.ChainListAction;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;
import org.qubership.integration.platform.ai.chain.folder.ChainTable;
import org.qubership.integration.platform.ai.chain.folder.ChainTable.Row;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainUpdateRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderWriteRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;
import org.qubership.integration.platform.ai.llm.agent.ChainListIntentAgent;
import org.qubership.integration.platform.ai.llm.agent.ChainListReplyAgent;

class ChainListScenarioTest {

  private static final String CONVERSATION = "chain-list-test";
  private static final Folder CRM = new Folder("f1", "CRM", null, "CRM", "Customer chains");
  private static final Folder LEGACY = new Folder("f2", "Legacy", null, "Legacy");
  private static final Folder LEGACY_CRM = new Folder("f3", "CRM", "f2", "Legacy/CRM");
  private static final Row ORDERS = row("c1", "Orders sync", "");
  private static final Row SF_ACCOUNTS = row("c2", "SF accounts", "Legacy");
  private static final Row SF_CONTACTS = row("c3", "SF contacts", "");

  private ChainListIntentAgent agent;
  /** Answers null unless a test says otherwise, so replies carry Java's facts as they are. */
  private ChainListReplyAgent replies;
  private CatalogFolders folders;
  private ChainTable table;
  private CatalogRestClient catalog;
  private ChainListScenario scenario;

  @BeforeEach
  void setUp() {
    agent = mock(ChainListIntentAgent.class);
    replies = mock(ChainListReplyAgent.class);
    folders = mock(CatalogFolders.class);
    table = mock(ChainTable.class);
    catalog = mock(CatalogRestClient.class);
    ChainContextExtractor chainContext = mock(ChainContextExtractor.class);
    when(chainContext.resolveChainId(any(), anyString())).thenReturn(Optional.empty());
    scenario =
        new ChainListScenario(
            agent, replies, folders, table, catalog, mock(ConversationService.class),
            chainContext);
    when(folders.tree()).thenReturn(List.of(CRM, LEGACY));
  }

  @Test
  void resetDuringCardWordingDoesNotRestoreThePendingChange() {
    answer(command(ChainListAction.DELETE_CHAINS, null, null, List.of("orders sync"), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS));
    when(replies.reply(any(), any(), any())).thenAnswer(invocation -> {
      scenario.clearConversation(CONVERSATION);
      return "Confirm deletion.";
    });

    ChatEvent.Decision emitted =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));

    assertTrue(scenario.openDecision(CONVERSATION).isEmpty());
    scenario.dispatch(card(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, emitted), CONVERSATION);
    verify(catalog, never()).deleteChain(anyString());
  }

  @Test
  void workerRetainsCatalogMutationTracker() {
    answer(command(ChainListAction.MOVE_CHAINS, null, "CRM", List.of("orders sync"), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS));
    when(folders.resolveOrCreateId("/CRM")).thenReturn("f1");
    org.mockito.Mockito.doAnswer(invocation -> {
      ToolInvocationSink.catalogChanges().orElseThrow().chainChanged("c1");
      return null;
    }).when(catalog).moveChain("c1", "f1");
    java.util.List<ChatEvent> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    ToolSession.bind(CONVERSATION);
    ToolInvocationSink.bind(sent::add, null, CONVERSATION);
    try {
      var subscriber = scenario.handle(message(), CONVERSATION, ScenarioType.MANAGE_CHAIN_LIST)
          .subscribe().withSubscriber(io.smallrye.mutiny.helpers.test.AssertSubscriber.create(Long.MAX_VALUE));
      subscriber.awaitCompletion().assertCompleted();
      ToolInvocationSink.flushCatalogChanges();
      assertEquals(List.of(new ChatEvent.CatalogChanged(List.of("c1"), true)), sent);
    } finally {
      ToolInvocationSink.unbind();
      ToolSession.clear();
    }
  }

  @Test
  void movesOneNamedChainAtOnce() {
    answer(command(ChainListAction.MOVE_CHAINS, null, "CRM", List.of("orders sync"), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS, SF_CONTACTS));
    when(folders.resolveOrCreateId("/CRM")).thenReturn("f1");

    assertEquals("Moved 1 chain to CRM.", text(scenario.dispatch(message(), CONVERSATION)));
    verify(catalog).moveChain("c1", "f1");
  }

  @Test
  void movesChainsPickedByAConditionOnlyAfterTheCard() {
    List<ChainFilter> startsWithSf =
        List.of(new ChainFilter(ChainFilter.Field.NAME, ChainFilter.Condition.STARTS_WITH, "SF"));
    answer(command(ChainListAction.MOVE_CHAINS, null, "Salesforce", List.of(), startsWithSf));
    when(table.find(startsWithSf)).thenReturn(List.of(SF_ACCOUNTS, SF_CONTACTS));

    ChatEvent.Decision card =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));

    assertTrue(
        card.question().startsWith("Confirm: move 2 chains to Salesforce."), card.question());
    assertTrue(card.question().contains("- SF accounts (Legacy)"), card.question());
    assertTrue(card.question().contains("does not exist yet and will be created"));
    verify(catalog, never()).moveChain(anyString(), any());

    when(folders.resolveOrCreateId("/Salesforce")).thenReturn("f9");
    assertEquals(
        "Moved 2 chains to Salesforce.",
        text(scenario.dispatch(card(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, card), CONVERSATION)));
    verify(catalog).moveChain("c2", "f9");
    verify(catalog).moveChain("c3", "f9");
    assertEquals(
        ChainListScenario.CARD_GONE_FACTS,
        text(scenario.dispatch(card(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, card), CONVERSATION)));
  }

  @Test
  void asksWhichFolderWhenTheTargetNameIsShared() {
    when(folders.tree()).thenReturn(List.of(CRM, LEGACY, LEGACY_CRM));
    answer(command(ChainListAction.MOVE_CHAINS, null, "crm", List.of("Orders sync"), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS));

    assertEquals(
        "Several folders are named crm: CRM, Legacy/CRM. Ask which one; the user replies with its"
            + " full path.",
        text(scenario.dispatch(message(), CONVERSATION)));
    verify(catalog, never()).moveChain(anyString(), any());
  }

  @Test
  void deletesAFolderOnlyAfterTheCardListsItsChains() {
    answer(command(ChainListAction.DELETE_FOLDER, "Legacy", null, List.of(), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS, SF_ACCOUNTS));

    ChatEvent.Decision card =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));

    assertEquals(
        "Confirm: delete folder Legacy. This also deletes and undeploys 1 chain, and cannot be"
            + " undone.\n- SF accounts (Legacy)",
        card.question());
    assertEquals(
        "The user cancelled the change. Nothing changed.",
        text(scenario.dispatch(card(ChatEvent.CANCEL_CHAIN_LIST_CHANGE_ACTION, card), CONVERSATION)));
    verify(catalog, never()).deleteFolder(anyString());
  }

  @Test
  void renameSendsTheDescriptionBack() {
    answer(new ChainListCommand(
        ChainListAction.RENAME_FOLDER, "crm", "Customers", null, null, null, null, null));

    assertEquals(
        "Renamed folder CRM to Customers.", text(scenario.dispatch(message(), CONVERSATION)));
    verify(catalog).updateFolder("f1", new FolderWriteRequest(null, "Customers", "Customer chains"));
  }

  @Test
  void createReadsABareNameAsANewTopLevelFolder() {
    when(folders.tree()).thenReturn(List.of(LEGACY, LEGACY_CRM));
    answer(command(ChainListAction.CREATE_FOLDER, "CRM", null, List.of(), List.of()));
    when(folders.create("CRM")).thenReturn(CRM);

    assertEquals("Created folder CRM.", text(scenario.dispatch(message(), CONVERSATION)));
  }

  @Test
  void renamesOneNamedChainAtOnce() {
    answer(new ChainListCommand(ChainListAction.RENAME_CHAIN, null, "Orders import", null,
        List.of(), List.of("Orders sync"), List.of(), null));
    when(table.find(List.of())).thenReturn(List.of(ORDERS, SF_CONTACTS));

    assertEquals(
        "Renamed chain Orders sync to Orders import.",
        text(scenario.dispatch(message(), CONVERSATION)));
    verify(catalog).updateChain("c1", new ChainUpdateRequest("Orders import", null, null));
  }

  @Test
  void renameAsksWhichChainWhenSeveralMatch() {
    List<ChainFilter> startsWithSf =
        List.of(new ChainFilter(ChainFilter.Field.NAME, ChainFilter.Condition.STARTS_WITH, "SF"));
    answer(new ChainListCommand(ChainListAction.RENAME_CHAIN, null, "SF", null, List.of(),
        List.of(), startsWithSf, null));
    when(table.find(startsWithSf)).thenReturn(List.of(SF_ACCOUNTS, SF_CONTACTS));

    assertTrue(text(scenario.dispatch(message(), CONVERSATION)).startsWith("Several chains match"));
    verify(catalog, never()).updateChain(anyString(), any());
  }

  @Test
  void labelEditKeepsOtherAndTechnicalLabels() {
    answer(new ChainListCommand(ChainListAction.EDIT_LABELS, null, null, null, List.of(),
        List.of("Orders sync"), List.of(), null, null, List.of("crm"), List.of("OLD"), null, null,
        null, null));
    when(table.find(List.of())).thenReturn(List.of(ORDERS));
    when(catalog.getChain("c1"))
        .thenReturn(new ChainDto("c1", "Orders sync", null, null, false, List.of(
            new CatalogChainLabel("old", false),
            new CatalogChainLabel("keep", false),
            new CatalogChainLabel("qip-ai-publication-attempt:run", true))));

    assertEquals(
        "Updated the labels of 1 chain.", text(scenario.dispatch(message(), CONVERSATION)));
    verify(catalog).updateChain("c1", new ChainUpdateRequest(null, null, List.of(
        new CatalogChainLabel("keep", false), new CatalogChainLabel("crm", false))));
  }

  @Test
  void deletesEvenOneNamedChainOnlyAfterTheCardAndReportsEachFailure() {
    answer(command(
        ChainListAction.DELETE_CHAINS, null, null, List.of("Orders sync", "SF accounts"),
        List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS, SF_ACCOUNTS));
    doThrow(new IllegalStateException("chain is locked")).when(catalog).deleteChain("c2");

    ChatEvent.Decision card =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));
    verify(catalog, never()).deleteChain(anyString());

    assertEquals(
        "Deleted 1 chain.\nFailed:\n- SF accounts (Legacy): chain is locked",
        text(scenario.dispatch(card(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, card), CONVERSATION)));
    verify(catalog).deleteChain("c1");
  }

  @Test
  void duplicatesChainsPickedByAConditionAfterTheCard() {
    List<ChainFilter> startsWithSf =
        List.of(new ChainFilter(ChainFilter.Field.NAME, ChainFilter.Condition.STARTS_WITH, "SF"));
    answer(command(ChainListAction.COPY_CHAINS, null, null, List.of(), startsWithSf));
    when(table.find(startsWithSf)).thenReturn(List.of(SF_ACCOUNTS, SF_CONTACTS));

    ChatEvent.Decision card =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));
    assertTrue(
        card.question().startsWith("Confirm: duplicate 2 chains, each in its folder."),
        card.question());

    assertEquals(
        "Duplicated 2 chains in their folders.",
        text(scenario.dispatch(card(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, card), CONVERSATION)));
    verify(catalog).duplicateChain("c2");
    verify(catalog).duplicateChain("c3");
  }

  @Test
  void exportHandsTheChainIdsToTheBrowserWithoutTouchingTheCatalog() {
    answer(command(ChainListAction.EXPORT_CHAINS, "Legacy", null, List.of(), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS, SF_ACCOUNTS));

    ChatEvent.Decision card =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));

    assertEquals(
        "Offer 1 chain for download. The card's button opens the export dialog in the browser.\n"
            + "- SF accounts (Legacy)",
        card.question());
    assertEquals(List.of("c2"), card.chainIds());
    assertEquals(List.of(ChatEvent.EXPORT_CHAINS_ACTION), card.actions());
    verifyNoInteractions(catalog);
  }

  @Test
  void picksChainsByNumberFromTheLastListAndKeepsTheNumbersAfterAChange() {
    long sept17 = java.time.Instant.parse("2026-09-17T10:00:00Z").toEpochMilli();
    Row orders =
        new Row(
            "c1", "Orders sync", "", sept17, "alice", sept17, "alice", List.of("crm"),
            List.of("DEPLOYED"));
    List<ChainFilter> sync =
        List.of(new ChainFilter(ChainFilter.Field.NAME, ChainFilter.Condition.CONTAINS, "s"));
    answer(command(ChainListAction.FIND_CHAINS, null, null, List.of(), sync));
    when(table.find(sync)).thenReturn(List.of(orders, SF_ACCOUNTS, SF_CONTACTS));

    assertEquals(
        "Found 3 chains.\n"
            + "1. Orders sync · 2026-09-17 · alice · crm · DEPLOYED\n"
            + "2. SF accounts (Legacy)\n"
            + "3. SF contacts",
        text(scenario.dispatch(message(), CONVERSATION)));

    answer(fromList(ChainListAction.MOVE_CHAINS, "Archive", List.of(1, 3)));
    when(table.find(List.of())).thenReturn(List.of(orders, SF_ACCOUNTS, SF_CONTACTS));
    ChatEvent.Decision card =
        assertInstanceOf(ChatEvent.Decision.class, scenario.dispatch(message(), CONVERSATION));
    assertTrue(
        card.question()
            .endsWith("Folder Archive does not exist yet and will be created.\n"
                + "- Orders sync\n- SF contacts"),
        card.question());

    answer(fromList(ChainListAction.MOVE_CHAINS, "Archive", List.of(2)));
    when(folders.resolveOrCreateId("/Archive")).thenReturn("f9");
    assertEquals(
        "Created folder Archive. Moved 1 chain to Archive.",
        text(scenario.dispatch(message(), CONVERSATION)));
    verify(catalog).moveChain("c2", "f9");

    answer(fromList(ChainListAction.MOVE_CHAINS, "Archive", List.of(4)));
    assertEquals(
        "The last list has 3 chains, so there is no number 4.",
        text(scenario.dispatch(message(), CONVERSATION)));
  }

  @Test
  void replacesTheLastListAfterAnEmptySearchButKeepsItAfterAFailedSearch() {
    List<Row> rows = List.of(ORDERS, SF_ACCOUNTS, SF_CONTACTS, row("c4", "SF leads", "Legacy/CRM"));
    answer(command(ChainListAction.FIND_CHAINS, null, null, List.of(), List.of()));
    when(table.find(List.of())).thenReturn(rows, List.of(), rows);

    assertTrue(text(scenario.dispatch(message(), CONVERSATION)).startsWith("Found 4 chains."));
    assertEquals("No chains match.", text(scenario.dispatch(message(), CONVERSATION)));
    answer(fromList(ChainListAction.FIND_CHAINS, null, List.of(1)));
    assertEquals(
        "The last list has 0 chains, so there is no number 1.",
        text(scenario.dispatch(message(), CONVERSATION)));

    answer(command(ChainListAction.FIND_CHAINS, null, null, List.of(), List.of()));
    when(table.find(List.of()))
        .thenReturn(rows)
        .thenThrow(new IllegalStateException("Catalog unavailable"))
        .thenReturn(rows);
    scenario.dispatch(message(), CONVERSATION);
    assertTrue(
        text(scenario.dispatch(message(), CONVERSATION)).startsWith("The catalog request failed:"));
    answer(fromList(ChainListAction.FIND_CHAINS, null, List.of(1)));
    assertEquals("Found 1 chain.\n1. Orders sync", text(scenario.dispatch(message(), CONVERSATION)));
    verifyNoInteractions(catalog);
  }

  @Test
  void asksWhichChainsWhenTheChatHasNoListYet() {
    answer(fromList(ChainListAction.DELETE_CHAINS, null, List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS));

    assertTrue(
        text(scenario.dispatch(message(), CONVERSATION))
            .startsWith("This chat has no chain list to pick from yet."));
    verifyNoInteractions(catalog);
  }

  @Test
  void countsEveryChainByLabelWithoutListingThem() {
    answer(new ChainListCommand(ChainListAction.FIND_CHAINS, null, null, null, null, null, null,
        null, null, null, null, null, null, null, ChainListCommand.Grouping.LABEL));
    when(table.find(List.of()))
        .thenReturn(
            List.of(
                labeled("c1", "crm", "sync"), labeled("c2", "crm"), labeled("c3", "sync"),
                labeled("c4"), labeled("c5", "crm")));

    assertEquals(
        "Found 5 chains. Counted by label; a chain can count under more than one label.\n"
            + "- crm: 3\n- sync: 2\n- —: 1",
        text(scenario.dispatch(message(), CONVERSATION)));
  }

  @Test
  void theReplyAgentWordsTheAnswerAndJavaKeepsTheList() {
    answer(command(ChainListAction.FIND_CHAINS, null, null, List.of(), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS, SF_ACCOUNTS));
    when(replies.reply(any(), eq("which chains?"), anyString())).thenReturn("2 Ketten gefunden.");

    assertEquals(
        "2 Ketten gefunden.\n1. Orders sync\n2. SF accounts (Legacy)",
        text(scenario.dispatch(message("which chains?"), CONVERSATION)));

    ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
    verify(replies).reply(any(), any(), outcome.capture());
    assertTrue(
        outcome.getValue().startsWith("{\"facts\":\"Found 2 chains.\""), outcome.getValue());
    assertTrue(
        outcome.getValue()
            .contains("{\"number\":2,\"name\":\"SF accounts\",\"folder\":\"Legacy\""),
        outcome.getValue());
  }

  private void answer(ChainListCommand command) {
    when(agent.resolve(any(), any(), any(), any())).thenReturn(command);
  }

  private static ChainListCommand command(
      ChainListAction action,
      String folder,
      String target,
      List<String> names,
      List<ChainFilter> filters) {
    return new ChainListCommand(action, folder, null, target, List.of(), names, filters, null);
  }

  private static ChainListCommand fromList(
      ChainListAction action, String target, List<Integer> positions) {
    return new ChainListCommand(action, null, null, target, null, null, null, null, null, null,
        null, true, positions, null, null);
  }

  private static Row labeled(String id, String... labels) {
    return new Row(id, id, "", null, "", null, "", List.of(labels), List.of("DRAFT"));
  }

  private static Row row(String id, String name, String folderPath) {
    return new Row(id, name, folderPath, null, "", null, "");
  }

  private static String text(ChatEvent event) {
    return assertInstanceOf(ChatEvent.Token.class, event).text();
  }

  private static ChatRequest message() {
    return message("request");
  }

  private static ChatRequest message(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
  }

  private static ChatRequest card(String action, ChatEvent.Decision card) {
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactHash(card.artifactHash());
    ChatRequest request = new ChatRequest();
    request.setDecision(command);
    return request;
  }
}

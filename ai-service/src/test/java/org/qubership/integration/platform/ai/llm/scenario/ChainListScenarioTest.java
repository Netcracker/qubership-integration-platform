package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder;
import org.qubership.integration.platform.ai.chain.folder.ChainFilter;
import org.qubership.integration.platform.ai.chain.folder.ChainListAction;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;
import org.qubership.integration.platform.ai.chain.folder.ChainTable;
import org.qubership.integration.platform.ai.chain.folder.ChainTable.Row;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderWriteRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainListIntentAgent;

class ChainListScenarioTest {

  private static final String CONVERSATION = "chain-list-test";
  private static final Folder CRM = new Folder("f1", "CRM", null, "CRM", "Customer chains");
  private static final Folder LEGACY = new Folder("f2", "Legacy", null, "Legacy");
  private static final Folder LEGACY_CRM = new Folder("f3", "CRM", "f2", "Legacy/CRM");
  private static final Row ORDERS = row("c1", "Orders sync", "");
  private static final Row SF_ACCOUNTS = row("c2", "SF accounts", "Legacy");
  private static final Row SF_CONTACTS = row("c3", "SF contacts", "");

  private ChainListIntentAgent agent;
  private CatalogFolders folders;
  private ChainTable table;
  private CatalogRestClient catalog;
  private ChainListScenario scenario;

  @BeforeEach
  void setUp() {
    agent = mock(ChainListIntentAgent.class);
    folders = mock(CatalogFolders.class);
    table = mock(ChainTable.class);
    catalog = mock(CatalogRestClient.class);
    ChainContextExtractor chainContext = mock(ChainContextExtractor.class);
    when(chainContext.resolveChainId(any(), anyString())).thenReturn(Optional.empty());
    scenario =
        new ChainListScenario(
            agent, folders, table, catalog, mock(ConversationService.class), chainContext);
    when(folders.tree()).thenReturn(List.of(CRM, LEGACY));
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

    assertTrue(card.question().startsWith("Move 2 chains to Salesforce?"), card.question());
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
        ChainListScenario.CARD_GONE_MESSAGE,
        text(scenario.dispatch(card(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, card), CONVERSATION)));
  }

  @Test
  void asksWhichFolderWhenTheTargetNameIsShared() {
    when(folders.tree()).thenReturn(List.of(CRM, LEGACY, LEGACY_CRM));
    answer(command(ChainListAction.MOVE_CHAINS, null, "crm", List.of("Orders sync"), List.of()));
    when(table.find(List.of())).thenReturn(List.of(ORDERS));

    assertEquals(
        "Several folders are named crm: CRM, Legacy/CRM. Which one do you mean? Reply with its full"
            + " path.",
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
        "Delete folder Legacy? This also deletes and undeploys 1 chain:\n- SF accounts (Legacy)",
        card.question());
    assertEquals(
        "Nothing changed.",
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
    when(folders.create("CRM", List.of(LEGACY, LEGACY_CRM))).thenReturn(CRM);

    assertEquals("Created folder CRM.", text(scenario.dispatch(message(), CONVERSATION)));
  }

  private void answer(ChainListCommand command) {
    when(agent.resolve(any(), any(), any())).thenReturn(command);
  }

  private static ChainListCommand command(
      ChainListAction action,
      String folder,
      String target,
      List<String> names,
      List<ChainFilter> filters) {
    return new ChainListCommand(action, folder, null, target, List.of(), names, filters, null);
  }

  private static Row row(String id, String name, String folderPath) {
    return new Row(id, name, folderPath, null, "", null, "");
  }

  private static String text(ChatEvent event) {
    return assertInstanceOf(ChatEvent.Token.class, event).text();
  }

  private static ChatRequest message() {
    ChatRequest request = new ChatRequest();
    request.setMessage("request");
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

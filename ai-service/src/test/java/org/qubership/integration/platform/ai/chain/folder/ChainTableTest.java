package org.qubership.integration.platform.ai.chain.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.folder.ChainFilter.Condition;
import org.qubership.integration.platform.ai.chain.folder.ChainFilter.Field;
import org.qubership.integration.platform.ai.chain.folder.ChainTable.Row;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FilterDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderItemDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.UserDto;

class ChainTableTest {

  private static final long SEPT_29 = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli();
  private static final long OCT_01 = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli();

  private final CatalogRestClient catalog = mock(CatalogRestClient.class);
  private final ChainTable table = new ChainTable(catalog);

  @Test
  void sendsColumnFiltersToTheCatalogAndPlacesChainsInTheirFolders() {
    when(catalog.filterFolderItems(
            List.of(
                new FilterDto("NAME", "CONTAINS", "sync"),
                new FilterDto("STATUS", "IN", "DEPLOYED,DRAFT"))))
        .thenReturn(
            List.of(
                folder("f1", "Integrations", null),
                folder("f2", "Salesforce", "f1"),
                chain("c1", "Orders sync", "f2", "alice", SEPT_29),
                chain("c2", "Users sync", null, "bob", OCT_01)));

    List<Row> rows =
        table.find(
            List.of(
                new ChainFilter(Field.NAME, Condition.CONTAINS, "sync"),
                new ChainFilter(Field.STATUS, Condition.IN, "deployed, draft")));

    assertEquals(
        List.of(
            new Row(
                "c1", "Orders sync", "Integrations/Salesforce", SEPT_29, "alice", SEPT_29, "alice"),
            new Row("c2", "Users sync", "", OCT_01, "bob", OCT_01, "bob")),
        rows);
  }

  @Test
  void appliesAuthorAndDateColumnsItself() {
    when(catalog.filterFolderItems(List.of()))
        .thenReturn(
            List.of(
                chain("c1", "Orders sync", null, "alice", SEPT_29),
                chain("c2", "Users sync", null, "Bob", OCT_01)));

    assertEquals(
        List.of("c2"),
        ids(table.find(List.of(new ChainFilter(Field.CREATED_BY, Condition.IS, "bob")))));
    assertEquals(
        List.of("c2"),
        ids(table.find(
            List.of(new ChainFilter(Field.MODIFIED_AT, Condition.IS_AFTER, "2026-09-30")))));
    assertEquals(
        List.of("c1"),
        ids(table.find(
            List.of(new ChainFilter(Field.CREATED_AT, Condition.IS_BEFORE, "2026-09-30")))));
  }

  @Test
  void refusesAFilterTheCatalogCannotRunBeforeCallingIt() {
    IllegalArgumentException status =
        assertThrows(
            IllegalArgumentException.class,
            () -> table.find(List.of(new ChainFilter(Field.STATUS, Condition.IN, "RUNNING"))));
    assertEquals(
        "Status RUNNING is unknown. Use DEPLOYED, FAILED, PROCESSING, or DRAFT.",
        status.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> table.find(List.of(new ChainFilter(Field.NAME, Condition.IS_AFTER, "x"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> table.find(List.of(new ChainFilter(Field.CREATED_AT, Condition.IS_AFTER, "soon"))));
    verify(catalog, never()).filterFolderItems(any());
  }

  @Test
  void keepsOnlyChainsInsideAFolderTree() {
    List<Row> rows =
        List.of(
            new Row("c1", "A", "Integrations", null, null, null, null),
            new Row("c2", "B", "Integrations/Salesforce", null, null, null, null),
            new Row("c3", "C", "Integrations2", null, null, null, null));

    assertEquals(List.of("c1", "c2"), ids(ChainTable.inFolder(rows, "Integrations")));
  }

  private static List<String> ids(List<Row> rows) {
    return rows.stream().map(Row::id).toList();
  }

  private static FolderItemDto folder(String id, String name, String parentId) {
    return new FolderItemDto(id, name, null, "FOLDER", null, parentId, null, null, null, null);
  }

  private static FolderItemDto chain(
      String id, String name, String parentId, String author, long when) {
    UserDto user = new UserDto("u-" + author, author);
    return new FolderItemDto(id, name, null, "CHAIN", null, parentId, when, user, when, user);
  }
}

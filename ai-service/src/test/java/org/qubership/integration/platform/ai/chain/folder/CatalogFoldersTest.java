package org.qubership.integration.platform.ai.chain.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Resolution;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderItemDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderWriteRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ListFolderRequest;

class CatalogFoldersTest {

  private static final Folder INTEGRATIONS = new Folder("f1", "Integrations", null, "Integrations");
  private static final Folder SALESFORCE =
      new Folder("f2", "Salesforce", "f1", "Integrations/Salesforce");
  private static final Folder LEGACY = new Folder("f3", "Legacy", null, "Legacy");
  private static final Folder LEGACY_SALESFORCE =
      new Folder("f4", "Salesforce", "f3", "Legacy/Salesforce");

  @Test
  void concurrentPublicationsResolveToOneFolder() throws Exception {
    var client = mock(CatalogRestClient.class);
    var items = new java.util.concurrent.CopyOnWriteArrayList<FolderItemDto>();
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    var firstRead = new java.util.concurrent.CountDownLatch(1);
    var releaseRead = new java.util.concurrent.CountDownLatch(1);
    when(client.listFolder(ListFolderRequest.of(null))).thenAnswer(invocation -> {
      var snapshot = List.copyOf(items);
      if (firstRead.getCount() != 0) {
        firstRead.countDown();
        assertTrue(releaseRead.await(2, java.util.concurrent.TimeUnit.SECONDS));
      }
      return snapshot;
    });
    when(client.createFolder(any())).thenAnswer(invocation -> {
      var request = invocation.getArgument(0, FolderWriteRequest.class);
      var created = item("f" + writes.incrementAndGet(), request.name(), "FOLDER");
      items.add(created);
      return created;
    });
    var folders = new CatalogFolders(client);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> folders.resolveOrCreateId("/Campaign"));
      assertTrue(firstRead.await(2, java.util.concurrent.TimeUnit.SECONDS));
      var second = pool.submit(() -> folders.resolveOrCreateId("/Campaign"));
      try {
        second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException serialized) {
        // The second resolver may correctly wait for the first publication.
      } finally {
        releaseRead.countDown();
      }
      assertEquals(first.get(2, java.util.concurrent.TimeUnit.SECONDS),
          second.get(2, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, writes.get());
    }
  }

  @Test
  void bareNameFindsTheFolderAnywhereIgnoringCase() {
    Resolution resolution =
        CatalogFolders.resolve("salesforce", List.of(INTEGRATIONS, SALESFORCE, LEGACY));

    assertEquals(new Resolution.Found(SALESFORCE), resolution);
  }

  @Test
  void bareNameMatchingSeveralFoldersIsAmbiguous() {
    Resolution resolution =
        CatalogFolders.resolve(
            "Salesforce", List.of(INTEGRATIONS, SALESFORCE, LEGACY, LEGACY_SALESFORCE));

    assertEquals(new Resolution.Ambiguous(List.of(SALESFORCE, LEGACY_SALESFORCE)), resolution);
  }

  @Test
  void unknownBareNameIsMissingAtTheRoot() {
    assertEquals(
        new Resolution.Missing("Billing"),
        CatalogFolders.resolve(" Billing ", List.of(INTEGRATIONS, SALESFORCE)));
  }

  @Test
  void pathResolvesFromTheRoot() {
    assertEquals(
        new Resolution.Found(LEGACY_SALESFORCE),
        CatalogFolders.resolve(
            "legacy / salesforce", List.of(INTEGRATIONS, SALESFORCE, LEGACY, LEGACY_SALESFORCE)));
  }

  @Test
  void pathWithAMissingLevelIsMissing() {
    assertEquals(
        new Resolution.Missing("Integrations/Salesforce/Orders"),
        CatalogFolders.resolve(
            "Integrations/Salesforce/Orders", List.of(INTEGRATIONS, SALESFORCE)));
  }

  @Test
  void leadingSlashPinsABareNameToTheRoot() {
    assertEquals(
        new Resolution.Missing("Salesforce"),
        CatalogFolders.resolve("/Salesforce", List.of(INTEGRATIONS, SALESFORCE)));
  }

  @Test
  void slashOrBlankIsTheRoot() {
    assertInstanceOf(Resolution.Root.class, CatalogFolders.resolve("/", List.of(INTEGRATIONS)));
    assertInstanceOf(Resolution.Root.class, CatalogFolders.resolve("  ", List.of(INTEGRATIONS)));
    assertInstanceOf(Resolution.Root.class, CatalogFolders.resolve(null, List.of(INTEGRATIONS)));
  }

  @Test
  void treeWalksEveryLevelAndSkipsChains() {
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.listFolder(ListFolderRequest.of(null)))
        .thenReturn(
            List.of(
                item("f1", "Integrations", "FOLDER"), item("c1", "Orders sync", "CHAIN")));
    when(client.listFolder(ListFolderRequest.of("f1")))
        .thenReturn(List.of(item("f2", "Salesforce", "FOLDER")));
    when(client.listFolder(ListFolderRequest.of("f2"))).thenReturn(List.of());

    List<Folder> tree = new CatalogFolders(client).tree();

    assertEquals(List.of(INTEGRATIONS, SALESFORCE), tree);
  }

  @Test
  void createMakesOnlyTheMissingLevels() {
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.listFolder(ListFolderRequest.of(null))).thenReturn(List.of(item("f1", "Integrations", "FOLDER")));
    when(client.listFolder(ListFolderRequest.of("f1"))).thenReturn(List.of(item("f2", "Salesforce", "FOLDER")));
    when(client.createFolder(new FolderWriteRequest("f2", "Orders", null)))
        .thenReturn(item("f9", "Orders", "FOLDER"));

    Folder created =
        new CatalogFolders(client)
            .create("integrations/Salesforce/Orders");

    assertEquals(new Folder("f9", "Orders", "f2", "Integrations/Salesforce/Orders"), created);
    verify(client).createFolder(new FolderWriteRequest("f2", "Orders", null));
  }

  @Test
  void resolveOrCreateIdReturnsNullForTheRootAndRefusesAnAmbiguousName() {
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.listFolder(ListFolderRequest.of(null)))
        .thenReturn(List.of(item("f1", "A", "FOLDER"), item("f3", "B", "FOLDER")));
    when(client.listFolder(ListFolderRequest.of("f1")))
        .thenReturn(List.of(item("f2", "Same", "FOLDER")));
    when(client.listFolder(ListFolderRequest.of("f3")))
        .thenReturn(List.of(item("f4", "Same", "FOLDER")));
    when(client.listFolder(ListFolderRequest.of("f2"))).thenReturn(List.of());
    when(client.listFolder(ListFolderRequest.of("f4"))).thenReturn(List.of());
    CatalogFolders folders = new CatalogFolders(client);

    assertNull(folders.resolveOrCreateId("/"));
    AmbiguousFolderException error =
        assertThrows(AmbiguousFolderException.class, () -> folders.resolveOrCreateId("same"));
    assertEquals(List.of("A/Same", "B/Same"), error.candidatePaths());
    verify(client, never()).createFolder(any());
  }

  private static FolderItemDto item(String id, String name, String itemType) {
    return new FolderItemDto(id, name, null, itemType, List.of());
  }
}

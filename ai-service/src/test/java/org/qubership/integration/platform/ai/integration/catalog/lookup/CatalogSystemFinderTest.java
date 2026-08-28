package org.qubership.integration.platform.ai.integration.catalog.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemFilter;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;

class CatalogSystemFinderTest {

  private static final CatalogRestClient.SystemDto OM =
      new CatalogRestClient.SystemDto(
          "sys-om", "om-order-lifecycle-manager-WFMS", "INTERNAL", "kafka");

  private static CatalogQuery fullQuery() {
    return new CatalogQuery(
        "om-order-lifecycle-manager async",
        "WFMS Create Work Order",
        "kafka",
        null,
        "/orders",
        "onTaskResult",
        null);
  }

  @Test
  @DisplayName("predicates run most selective first")
  void ordersPredicates() {
    List<CatalogSystemFilter> predicates = CatalogSystemFinder.predicates(fullQuery());

    assertEquals(
        List.of("URL", "SPECIFICATION_GROUP", "PROTOCOL", "NAME"),
        predicates.stream().map(CatalogSystemFilter::column).toList());
    assertEquals("lifecycle", predicates.get(3).value());
    // PROTOCOL rejects IS: the catalog matches nothing for it and throws on an unknown value.
    assertEquals("IN", predicates.get(2).condition());
  }

  @Test
  @DisplayName("an empty answer drops the least selective predicate and asks again")
  void dropsPredicatesFromTheEnd() {
    CatalogRestClient client = mock(CatalogRestClient.class);
    List<List<String>> asked = new ArrayList<>();
    when(client.filterSystems(anyList()))
        .thenAnswer(
            invocation -> {
              List<CatalogSystemFilter> sent = invocation.getArgument(0);
              asked.add(sent.stream().map(CatalogSystemFilter::column).toList());
              return sent.size() > 2 ? List.of() : List.of(OM);
            });

    CatalogSystemFinder.Narrowed narrowed = new CatalogSystemFinder(client).narrow(fullQuery());

    assertEquals(new CatalogSystemFinder.Narrowed.Systems(List.of(OM)), narrowed);
    assertEquals(
        List.of(
            List.of("URL", "SPECIFICATION_GROUP", "PROTOCOL", "NAME"),
            List.of("URL", "SPECIFICATION_GROUP", "PROTOCOL"),
            List.of("URL", "SPECIFICATION_GROUP")),
        asked);
    verify(client, never()).searchSystems(any());
  }

  @Test
  @DisplayName("the plain search answers when no predicate does")
  void fallsBackToSearch() {
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.filterSystems(anyList())).thenReturn(List.of());
    when(client.searchSystems(any())).thenReturn(List.of(OM));

    CatalogSystemFinder.Narrowed narrowed = new CatalogSystemFinder(client).narrow(fullQuery());

    assertEquals(new CatalogSystemFinder.Narrowed.Systems(List.of(OM)), narrowed);
    verify(client)
        .searchSystems(new CatalogSystemSearchRequest("om-order-lifecycle-manager async"));
  }

  @Test
  @DisplayName("a catalog too broad to read says so instead of being walked")
  void reportsTooBroad() {
    CatalogRestClient client = mock(CatalogRestClient.class);
    List<CatalogRestClient.SystemDto> many =
        IntStream.range(0, CatalogSystemFinder.TOO_BROAD + 1)
            .mapToObj(
                i -> new CatalogRestClient.SystemDto("sys-" + i, "svc " + i, "INTERNAL", "http"))
            .toList();
    when(client.filterSystems(anyList())).thenReturn(many);

    CatalogSystemFinder.Narrowed narrowed = new CatalogSystemFinder(client).narrow(fullQuery());

    assertEquals(
        new CatalogSystemFinder.Narrowed.TooBroad(CatalogSystemFinder.TOO_BROAD + 1), narrowed);
  }

  @Test
  @DisplayName("a query with nothing to filter by and nothing to search for reads nothing")
  void emptyQueryReadsNothing() {
    CatalogRestClient client = mock(CatalogRestClient.class);

    CatalogSystemFinder.Narrowed narrowed =
        new CatalogSystemFinder(client)
            .narrow(new CatalogQuery(null, null, null, null, null, null, null));

    assertEquals(new CatalogSystemFinder.Narrowed.Systems(List.of()), narrowed);
    verify(client, never()).filterSystems(anyList());
    verify(client, never()).searchSystems(any());
  }
}

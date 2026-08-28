package org.qubership.integration.platform.ai.integration.catalog.lookup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;

/** Test finders for suites that set up the catalog through {@link CatalogSystemReadTool}. */
public final class CatalogSystemFinders {

  private CatalogSystemFinders() {}

  /**
   * A finder that narrows by name search alone, through the given read tool.
   *
   * <p>For suites whose subject is what happens once services are found, not how they are found.
   * Narrowing has its own coverage in {@code CatalogSystemFinderTest}.
   */
  public static CatalogSystemFinder byNameSearch(CatalogSystemReadTool readTool) {
    CatalogSystemFinder finder = mock(CatalogSystemFinder.class);
    when(finder.narrow(any()))
        .thenAnswer(
            invocation -> {
              CatalogQuery query = invocation.getArgument(0);
              String search =
                  query.systemHint() == null ? query.operationHint() : query.systemHint();
              return new CatalogSystemFinder.Narrowed.Systems(
                  search == null ? List.of() : readTool.searchCatalogSystems(search));
            });
    return finder;
  }
}

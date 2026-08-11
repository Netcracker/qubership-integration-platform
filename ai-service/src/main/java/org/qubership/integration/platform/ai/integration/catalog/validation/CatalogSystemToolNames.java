package org.qubership.integration.platform.ai.integration.catalog.validation;

/** LangChain4j catalog system tool names (must match {@code @Tool} method names). */
public final class CatalogSystemToolNames {

  public static final String SEARCH = "searchCatalogSystems";
  public static final String SPECS = "getApiSpecifications";
  public static final String OPS = "listCatalogOperations";
  public static final String CREATE_SYSTEM = "createSystem";
  public static final String IMPORT_APIHUB = "importApiHubSpecToSystem";

  private CatalogSystemToolNames() {}
}

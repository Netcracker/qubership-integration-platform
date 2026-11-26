package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationSchemasReadCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

/** Test-only factory for the package-private catalog schema loader. */
public final class SchemaLoaderTestSupport {

  private SchemaLoaderTestSupport() {}

  /** Memoizes like the Quarkus cache, which is inactive outside the container. */
  public static OperationSchemaLoader catalogLoader(
      CatalogRestClient catalog, CompilationArtifacts artifacts, ObjectMapper mapper) {
    return new CatalogOperationSchemaLoader(
        new CatalogOperationSchemasReadCache(catalog) {
          private final Map<String, CatalogRestClient.OperationSchemaMapsDto> memo =
              new ConcurrentHashMap<>();

          @Override
          public CatalogRestClient.OperationSchemaMapsDto getOperationSchemas(String operationId) {
            return memo.computeIfAbsent(operationId, super::getOperationSchemas);
          }

          @Override
          public void invalidate(String operationId) {
            memo.remove(operationId);
          }
        },
        artifacts,
        mapper);
  }
}

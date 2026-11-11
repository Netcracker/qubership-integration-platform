package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

/** Test-only factory for the package-private catalog schema loader. */
public final class SchemaLoaderTestSupport {

  private SchemaLoaderTestSupport() {}

  public static OperationSchemaLoader catalogLoader(
      CatalogRestClient catalog, CompilationArtifacts artifacts, ObjectMapper mapper) {
    return new CatalogOperationSchemaLoader(catalog, artifacts, mapper);
  }
}

package org.qubership.integration.platform.ai.plan.mapping.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogRestClientOperationSchemasTest {

  @Test
  void requestSchemaRequiresContentTypeQueryWithoutDefault() throws Exception {
    Method method =
        CatalogRestClient.class.getMethod(
            "getOperationRequestSchema", String.class, String.class);
    QueryParam query = method.getParameters()[1].getAnnotation(QueryParam.class);
    assertEquals("contentType", query.value());
    assertNull(method.getParameters()[1].getAnnotation(DefaultValue.class));
  }

  @Test
  void responseSchemaRequiresContentTypeAndResponseCodeWithoutDefaults() throws Exception {
    Method method =
        CatalogRestClient.class.getMethod(
            "getOperationResponseSchema", String.class, String.class, String.class);
    assertEquals(
        "contentType", method.getParameters()[1].getAnnotation(QueryParam.class).value());
    assertEquals(
        "responseCode", method.getParameters()[2].getAnnotation(QueryParam.class).value());
    assertNull(method.getParameters()[1].getAnnotation(DefaultValue.class));
    assertNull(method.getParameters()[2].getAnnotation(DefaultValue.class));
  }
}

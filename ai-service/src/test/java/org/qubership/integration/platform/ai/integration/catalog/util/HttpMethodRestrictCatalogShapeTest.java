package org.qubership.integration.platform.ai.integration.catalog.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpMethodRestrictCatalogShapeTest {

  @Test
  void wrapsAMethodStringAsCatalogObject() {
    assertEquals(
        Map.of("httpMethods", List.of("GET")),
        HttpMethodRestrictCatalogShape.toCatalogValue("GET"));
  }

  @Test
  void splitsACommaSeparatedMethodString() {
    assertEquals(
        Map.of("httpMethods", List.of("GET", "POST")),
        HttpMethodRestrictCatalogShape.toCatalogValue("GET,POST"));
  }

  @Test
  void keepsAnAlreadyCatalogShapedObject() {
    Map<String, Object> catalog = Map.of("httpMethods", List.of("PUT"));
    assertEquals(
        Map.of("httpMethods", List.of("PUT")),
        HttpMethodRestrictCatalogShape.toCatalogValue(catalog));
  }

  @Test
  void leavesUnknownShapesUnchanged() {
    List<String> array = List.of("GET");
    assertSame(array, HttpMethodRestrictCatalogShape.toCatalogValue(array));
  }

  @Test
  void rewritesThePropertyOnAPatchBody() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("httpMethodRestrict", "POST");
    properties.put("contextPath", "/api");
    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put("properties", properties);

    HttpMethodRestrictCatalogShape.applyToPatchBody(patch);

    assertEquals(
        Map.of("httpMethods", List.of("POST")), properties.get("httpMethodRestrict"));
    assertEquals("/api", properties.get("contextPath"));
  }
}

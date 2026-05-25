package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogElementPropertiesMergeTest {

  @Test
  void mergePreservesAccessControlTypeWhenPatchAddsEndpointFields() {
    Map<String, Object> base = new LinkedHashMap<>();
    base.put("accessControlType", "NONE");
    base.put("externalRoute", true);

    Map<String, Object> overlay = new LinkedHashMap<>();
    overlay.put("contextPath", "/hello-world");
    overlay.put("httpMethodRestrict", "GET");

    Map<String, Object> merged = CatalogElementPropertiesMerge.merge(base, overlay);

    assertEquals("NONE", merged.get("accessControlType"));
    assertEquals(true, merged.get("externalRoute"));
    assertEquals("/hello-world", merged.get("contextPath"));
    assertEquals("GET", merged.get("httpMethodRestrict"));
  }

  @Test
  void mergeNestedMapsAreMergedRecursiveScalarsOverlayReplace() {
    Map<String, Object> innerBase = new LinkedHashMap<>();
    innerBase.put("x", 1);
    innerBase.put("keep", "base");

    Map<String, Object> base = new LinkedHashMap<>();
    base.put("nested", innerBase);
    base.put("top", "fromBase");

    Map<String, Object> innerOverlay = new LinkedHashMap<>();
    innerOverlay.put("y", 2);
    innerOverlay.put("keep", "patch");

    Map<String, Object> overlay = new LinkedHashMap<>();
    overlay.put("nested", innerOverlay);

    Map<String, Object> merged = CatalogElementPropertiesMerge.merge(base, overlay);

    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) merged.get("nested");
    assertEquals(1, nested.get("x"));
    assertEquals(2, nested.get("y"));
    assertEquals("patch", nested.get("keep"));
    assertEquals("fromBase", merged.get("top"));
  }
}

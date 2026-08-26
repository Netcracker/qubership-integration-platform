package org.qubership.integration.platform.ai.integration.catalog.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live catalog JSON for {@code httpMethodRestrict} is {@code {"httpMethods":["GET"]}}.
 *
 * <p>CIP YAML and CREATE {@code ConfiguredTriggerSet} / GraphPatch plan values still use a method
 * string ({@code "GET"} or {@code "GET,POST"}). Plan properties store that as JSON text. Apply this
 * shape once on the catalog write body so CREATE materialization and harness PATCH both store the
 * object.
 */
public final class HttpMethodRestrictCatalogShape {

  public static final String PROPERTY_KEY = "httpMethodRestrict";
  public static final String HTTP_METHODS = "httpMethods";

  private HttpMethodRestrictCatalogShape() {}

  /**
   * Rewrites {@code properties.httpMethodRestrict} on a catalog PATCH body when the key is present.
   */
  public static void applyToPatchBody(Map<String, Object> patchBody) {
    if (patchBody == null) {
      return;
    }
    Object properties = patchBody.get("properties");
    if (properties instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> props = (Map<String, Object>) map;
      applyToProperties(props);
    }
  }

  static void applyToProperties(Map<String, Object> properties) {
    if (properties == null || !properties.containsKey(PROPERTY_KEY)) {
      return;
    }
    properties.put(PROPERTY_KEY, toCatalogValue(properties.get(PROPERTY_KEY)));
  }

  /**
   * Catalog object for a plan string, JSON object, or value that is already {@code {httpMethods}}.
   * Unknown shapes are left unchanged so validation can reject them.
   */
  public static Object toCatalogValue(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Map<?, ?> map) {
      Object methods = map.get(HTTP_METHODS);
      if (methods instanceof List<?> list) {
        return catalogObject(methodNames(list));
      }
      return raw;
    }
    if (raw instanceof String text) {
      return fromMethodString(text);
    }
    return raw;
  }

  private static Object fromMethodString(String text) {
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return catalogObject(List.of());
    }
    return catalogObject(splitMethods(trimmed));
  }

  private static List<String> splitMethods(String text) {
    List<String> methods = new ArrayList<>();
    for (String part : text.split(",")) {
      String method = part.trim();
      if (!method.isEmpty()) {
        methods.add(method);
      }
    }
    return methods;
  }

  private static List<String> methodNames(List<?> list) {
    List<String> methods = new ArrayList<>();
    for (Object item : list) {
      if (item != null) {
        String method = String.valueOf(item).trim();
        if (!method.isEmpty()) {
          methods.add(method);
        }
      }
    }
    return methods;
  }

  private static Map<String, Object> catalogObject(List<String> methods) {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put(HTTP_METHODS, List.copyOf(methods));
    return object;
  }
}

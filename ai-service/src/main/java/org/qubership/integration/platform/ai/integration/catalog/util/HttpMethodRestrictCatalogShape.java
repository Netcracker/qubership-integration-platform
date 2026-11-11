package org.qubership.integration.platform.ai.integration.catalog.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Catalog {@code httpMethodRestrict} is a comma-separated method string ({@code "GET"} or
 * {@code "GET,POST"}). UI widgets and {@code TriggerUtils} read that string.
 *
 * <p>Plan properties already store the string. A previous PATCH wrote {@code {httpMethods:[...]}};
 * flatten that object back to the string on write and when comparing reconcile values.
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
   * Method string for a plan value, a catalog string, or a legacy {@code {httpMethods}} object.
   * Unknown shapes are left unchanged so validation can reject them.
   */
  public static Object toCatalogValue(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Map<?, ?> map) {
      Object methods = map.get(HTTP_METHODS);
      if (methods instanceof List<?> list) {
        return joinMethods(methodNames(list));
      }
      return raw;
    }
    if (raw instanceof String text) {
      return joinMethods(splitMethods(text));
    }
    return raw;
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

  private static String joinMethods(List<String> methods) {
    return String.join(",", methods);
  }
}

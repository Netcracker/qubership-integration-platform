package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep-merge for catalog element {@code properties}: overlay wins for scalars and lists; nested
 * {@link Map} values are merged recursively.
 */
public final class CatalogElementPropertiesMerge {

  private CatalogElementPropertiesMerge() {}

  /**
   * @param base current properties from catalog (may be null)
   * @param overlay keys from the PATCH (may be null)
   * @return merged properties map; never null (empty if both inputs empty/null)
   */
  public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (base != null) {
      for (Map.Entry<String, Object> e : base.entrySet()) {
        result.put(e.getKey(), copyForMerge(e.getValue()));
      }
    }
    if (overlay != null) {
      for (Map.Entry<String, Object> e : overlay.entrySet()) {
        String key = e.getKey();
        Object patchVal = e.getValue();
        Object existing = result.get(key);
        if (patchVal instanceof Map<?, ?> patchMap && existing instanceof Map<?, ?> existingMap) {
          Map<String, Object> existingObj = shallowStringKeyMap(existingMap);
          Map<String, Object> patchObj = shallowStringKeyMap(patchMap);
          result.put(key, merge(existingObj, patchObj));
        } else {
          result.put(key, copyForMerge(patchVal));
        }
      }
    }
    return result;
  }

  /** One-level copy with string keys (values kept by reference). */
  private static Map<String, Object> shallowStringKeyMap(Map<?, ?> source) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : source.entrySet()) {
      out.put(String.valueOf(e.getKey()), e.getValue());
    }
    return out;
  }

  private static Object copyForMerge(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        copy.put(String.valueOf(e.getKey()), copyForMerge(e.getValue()));
      }
      return copy;
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(copyForMerge(item));
      }
      return copy;
    }
    return value;
  }
}

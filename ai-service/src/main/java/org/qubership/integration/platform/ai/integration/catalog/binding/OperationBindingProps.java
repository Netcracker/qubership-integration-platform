package org.qubership.integration.platform.ai.integration.catalog.binding;

import java.util.Map;

/** Shared helpers for operation binding property maps. */
public final class OperationBindingProps {

  private OperationBindingProps() {}

  public static String stringProp(Map<String, Object> props, String key) {
    Object value = props.get(key);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }
}

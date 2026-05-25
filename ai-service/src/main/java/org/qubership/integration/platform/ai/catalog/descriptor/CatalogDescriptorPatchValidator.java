package org.qubership.integration.platform.ai.catalog.descriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogCustomTabModel;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogDescriptorPropertyModel;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogDescriptorValuePresence;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogPropertyValidationModel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates merged element {@code properties} using the same rules as runtime-catalog element
 * descriptors.
 */
@ApplicationScoped
public class CatalogDescriptorPatchValidator {

  static final String SOURCE = "catalog-descriptor";

  private static final String PROPERTIES_DOT = "properties.";

  private final ObjectMapper objectMapper;

  @Inject
  public CatalogDescriptorPatchValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void validateAndThrow(
      String elementType, CatalogElementDescriptorModel descriptor, Map<String, Object> props) {
    if (descriptor == null || props == null) {
      return;
    }
    ArrayNode errors = objectMapper.createArrayNode();

    for (CatalogDescriptorPropertyModel p : descriptor.allProperties()) {
      if (p.getName() == null || p.getName().isBlank()) {
        continue;
      }
      if (!isMandatorySatisfied(p, props)) {
        addError(
            errors,
            PROPERTIES_DOT + p.getName(),
            "Mandatory property is missing or empty per catalog descriptor",
            null,
            null,
            null);
        continue;
      }
      Object raw = props.get(p.getName());
      if (p.getMask() != null && !p.getMask().isBlank() && raw != null) {
        if (!maskMatches(p.getMask(), raw)) {
          addError(
              errors,
              PROPERTIES_DOT + p.getName(),
              "Value does not match catalog descriptor mask",
              null,
              null,
              null);
        }
      }
      List<String> allowed = p.getAllowedValues();
      if (allowed != null && !allowed.isEmpty() && CatalogDescriptorValuePresence.isPresent(raw)) {
        if (!allowedValuesSatisfied(p, raw, allowed)) {
          addError(
              errors,
              PROPERTIES_DOT + p.getName(),
              "Value is not among catalog descriptor allowedValues",
              null,
              null,
              arrayOfStrings(allowed));
        }
      }
    }

    for (CatalogCustomTabModel tab : descriptor.getCustomTabs()) {
      CatalogPropertyValidationModel v = tab.getValidation();
      if (v == null) {
        continue;
      }
      if (!v.arePropertiesValid(props)) {
        addError(
            errors,
            "properties",
            "Some mandatory properties are missing on tab " + tab.getName(),
            tab.getName(),
            arrayOfStrings(v.getAnyOf()),
            arrayOfStrings(v.getAllOf()));
      }
    }

    if (!errors.isEmpty()) {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("valid", false);
      root.set("errors", errors);
      String blob = root.toString();
      throw new IllegalArgumentException(
          "Catalog descriptor validation failed for element type " + elementType + ": " + blob);
    }
  }

  private void addError(
      ArrayNode errors,
      String path,
      String message,
      String tab,
      ArrayNode missingAnyOf,
      ArrayNode missingAllOf) {
    ObjectNode e = objectMapper.createObjectNode();
    e.put("source", SOURCE);
    e.put("path", path);
    e.put("message", message);
    if (tab != null) {
      e.put("tab", tab);
    }
    if (missingAnyOf != null && !missingAnyOf.isEmpty()) {
      e.set("missingAnyOf", missingAnyOf);
    }
    if (missingAllOf != null && !missingAllOf.isEmpty()) {
      e.set("missingAllOf", missingAllOf);
    }
    errors.add(e);
  }

  private ArrayNode arrayOfStrings(List<String> list) {
    ArrayNode arr = objectMapper.createArrayNode();
    if (list == null) {
      return arr;
    }
    for (String s : list) {
      if (s != null) {
        arr.add(s);
      }
    }
    return arr;
  }

  /** Mirrors runtime-catalog {@code ElementUtils.isMandatoryPropertyPresent} for descriptors. */
  private static boolean isMandatorySatisfied(
      CatalogDescriptorPropertyModel p, Map<String, Object> props) {
    if (p.isCustomType() && p.getValidation() != null) {
      return p.getValidation().arePropertiesValid(props);
    }
    return !p.isMandatory() || CatalogDescriptorValuePresence.isPresent(props.get(p.getName()));
  }

  private static boolean maskMatches(String mask, Object raw) {
    String value = stringify(raw);
    if (value == null) {
      return false;
    }
    try {
      return Pattern.compile(mask).matcher(value).find();
    } catch (PatternSyntaxException e) {
      return true;
    }
  }

  private static String stringify(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof String s) {
      return s;
    }
    if (raw instanceof Boolean || raw instanceof Number) {
      return String.valueOf(raw);
    }
    return String.valueOf(raw);
  }

  private static boolean allowedValuesSatisfied(
      CatalogDescriptorPropertyModel p, Object raw, List<String> allowed) {
    if (p.isMultiple() && raw instanceof Collection<?> c) {
      for (Object o : c) {
        if (o == null || !allowed.contains(String.valueOf(o))) {
          return false;
        }
      }
      return true;
    }
    return allowed.contains(String.valueOf(raw));
  }
}

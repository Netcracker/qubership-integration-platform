package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.integration.catalog.util.HttpMethodRestrictCatalogShape;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Applies generator-enriched plan properties to materialized catalog elements. */
@ApplicationScoped
public class ChainPlanPropertiesMaterializer {

  private static final Logger LOG = Logger.getLogger(ChainPlanPropertiesMaterializer.class);

  private final CatalogRestClient catalogRestClient;
  private final DeterministicElementSchemaService schemaService;
  private final ObjectMapper objectMapper;

  @Inject
  public ChainPlanPropertiesMaterializer(
      @RestClient CatalogRestClient catalogRestClient,
      DeterministicElementSchemaService schemaService,
      ObjectMapper objectMapper) {
    this.catalogRestClient = catalogRestClient;
    this.schemaService = schemaService;
    this.objectMapper = objectMapper;
  }

  private static final int MAX_ALLOWED_KEYS_IN_MESSAGE = 10;

  public PropertiesApplyResult apply(ChainPlanGraph graph, MaterializationMap map) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(map, "map");

    int[] patchedCount = {0};
    List<String> failedNodeIds = new ArrayList<>();
    String[] firstValidationError = {null};

    for (ChainPlanNode node : graph.nodes()) {
      applyNode(map, node, patchedCount, failedNodeIds, firstValidationError);
    }

    return new PropertiesApplyResult(
        patchedCount[0], List.copyOf(failedNodeIds), firstValidationError[0]);
  }

  private void applyNode(
      MaterializationMap map,
      ChainPlanNode node,
      int[] patchedCount,
      List<String> failedNodeIds,
      String[] firstValidationError) {
    String elementId = map.nodeIdToElementId().get(node.nodeId());
    if (elementId == null) {
      failedNodeIds.add(node.nodeId());
    } else if (hasPatchableContent(node)) {
      // Property-less shells (condition/else/try) still need plan labels applied so reconcile
      // matches catalog element names.
      applyNodeProperties(map, node, elementId, patchedCount, failedNodeIds, firstValidationError);
    }
  }

  private static boolean hasPatchableContent(ChainPlanNode node) {
    return hasProperties(node) || hasLabel(node);
  }

  private static boolean hasProperties(ChainPlanNode node) {
    return node.properties() != null && !node.properties().isEmpty();
  }

  private static boolean hasLabel(ChainPlanNode node) {
    return node.label() != null && !node.label().isBlank();
  }

  private void applyNodeProperties(
      MaterializationMap map,
      ChainPlanNode node,
      String elementId,
      int[] patchedCount,
      List<String> failedNodeIds,
      String[] firstValidationError) {
    try {
      CatalogElementResponseDto current = catalogRestClient.getElement(map.chainId(), elementId);
      Map<String, Object> patchBody = buildPatchBody(node);
      if (patchBody.isEmpty()) {
        return;
      }
      // Catalog PatchElementRequest defaults properties to {} and MapStruct replaces the whole
      // map. Merge with the current element so label-only or partial patches keep mandatory
      // defaults (same contract as CatalogElementWriteTools.updateElement).
      Map<String, Object> mergedPatch = mergeWithCurrentElement(current, patchBody);
      HttpMethodRestrictCatalogShape.applyToPatchBody(mergedPatch);
      if (patchValidatedProperties(
          map, node, elementId, current, mergedPatch, firstValidationError)) {
        patchedCount[0]++;
      } else {
        failedNodeIds.add(node.nodeId());
      }
    } catch (Exception e) {
      failedNodeIds.add(node.nodeId());
    }
  }

  private boolean patchValidatedProperties(
      MaterializationMap map,
      ChainPlanNode node,
      String elementId,
      CatalogElementResponseDto current,
      Map<String, Object> patchBody,
      String[] firstValidationError)
      throws Exception {
    ValidatedPatch validated = validatePatch(node.type(), patchBody);
    if (!validated.valid()) {
      rememberFirstValidationError(
          firstValidationError,
          node.nodeId(),
          node.type(),
          validated.validationMessage());
      return false;
    }
    preservePlacementFields(validated.patchBody(), current);
    catalogRestClient.updateElement(map.chainId(), elementId, validated.patchBody());
    return true;
  }

  private Map<String, Object> buildPatchBody(ChainPlanNode node) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (node.label() != null && !node.label().isBlank()) {
      body.put("name", node.label());
    }

    Set<String> allowedKeys = schemaService.allowedPatchPropertyKeys(node.type());
    Map<String, Object> properties = new LinkedHashMap<>();
    if (node.properties() != null) {
      for (PlanProperty property : node.properties()) {
        if (isAllowedProperty(property, allowedKeys)) {
          try {
            properties.put(property.key(), propertyValueAsObject(node.type(), property));
          } catch (JsonProcessingException e) {
            LOG.warnf(
                "Skipping property %s on node type=%s: %s",
                property.key(),
                node.type(),
                e.getMessage());
          }
        } else if (property.key() != null && !property.key().isBlank()) {
          LOG.warnf(
              "Skipping disallowed property key=%s nodeId=%s type=%s allowedSample=%s",
              property.key(),
              node.nodeId(),
              node.type(),
              allowedKeySample(allowedKeys));
        }
      }
    }
    if (!properties.isEmpty()) {
      body.put("properties", properties);
    }
    return body;
  }

  /**
   * Renders one property's plan-model string value as the object the catalog PATCH body carries.
   *
   * <p>Object/array/boolean shapes are recognized from the string itself -- a script body never
   * looks like JSON true/false or starts with {@code {}}/{@code []}, so no schema lookup is needed
   * to tell them apart safely. A number is not safe to guess this way: a string property can
   * legitimately hold digits only (a version, a zip code), so the value keeps its schema-typed
   * shape -- integer, number, or otherwise left as a string -- via
   * {@link DeterministicElementSchemaService#coercePatchPropertyValue}, the same coercion the
   * CREATE-side compiler validation pipeline already uses.
   */
  private Object propertyValueAsObject(String elementType, PlanProperty property)
      throws JsonProcessingException {
    String value = property.value();
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      JsonNode node = objectMapper.readTree(trimmed);
      return objectMapper.convertValue(node, new TypeReference<>() {});
    }
    if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
      return Boolean.parseBoolean(trimmed);
    }
    return schemaService.coercePatchPropertyValue(elementType, property.key(), value);
  }

  private static boolean isAllowedProperty(PlanProperty property, Set<String> allowedKeys) {
    return property.key() != null
        && !property.key().isBlank()
        && (allowedKeys.isEmpty() || allowedKeys.contains(property.key()));
  }

  private static void preservePlacementFields(
      Map<String, Object> body, CatalogElementResponseDto current) {
    if (current == null) {
      return;
    }
    putIfPresent(body, "parentElementId", current.parentElementId);
    putIfPresent(body, "swimlaneId", current.swimlaneId);
  }

  /**
   * Merges a plan patch with the live catalog element so MapStruct property replacement does not
   * wipe mandatory defaults when the plan only carries a subset of keys (for example name).
   */
  static Map<String, Object> mergeWithCurrentElement(
      CatalogElementResponseDto current, Map<String, Object> patch) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (current != null && current.name != null && !current.name.isBlank()) {
      merged.put("name", current.name);
    }
    if (current != null && current.description != null) {
      merged.put("description", current.description);
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    if (current != null && current.properties != null) {
      properties.putAll(current.properties);
    }
    Object patchProperties = patch.get("properties");
    if (patchProperties instanceof Map<?, ?> patchMap) {
      for (Map.Entry<?, ?> entry : patchMap.entrySet()) {
        if (entry.getKey() != null) {
          properties.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
    }
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      if ("properties".equals(entry.getKey())) {
        continue;
      }
      merged.put(entry.getKey(), entry.getValue());
    }
    merged.put("properties", properties);
    return merged;
  }

  private static void putIfPresent(Map<String, Object> body, String key, String value) {
    if (body.containsKey(key)) {
      return;
    }
    String normalized = CatalogStrings.blankToNull(value);
    if (normalized != null) {
      body.put(key, normalized);
    }
  }

  private ValidatedPatch validatePatch(String elementType, Map<String, Object> patchBody)
      throws Exception {
    String patchJson = objectMapper.writeValueAsString(patchBody);
    String validationJson = schemaService.validateElementPatch(elementType, patchJson);
    JsonNode root = objectMapper.readTree(validationJson);
    if (root.has("error") || !root.path("valid").asBoolean(false)) {
      String validationMessage =
          root.has("error")
              ? root.get("error").asText()
              : "Element patch validation failed for type=" + elementType;
      LOG.warnf(
          "Element patch validation failed type=%s result=%s body=%s",
          elementType,
          validationJson,
          patchJson);
      return new ValidatedPatch(false, patchBody, validationMessage);
    }
    JsonNode patchWithDefaults = root.get("patchWithDefaults");
    if (patchWithDefaults != null && patchWithDefaults.isObject()) {
      Map<String, Object> enriched = objectMapper.convertValue(patchWithDefaults, new TypeReference<>() {
      });
      return new ValidatedPatch(true, enriched, null);
    }
    return new ValidatedPatch(true, patchBody, null);
  }

  private static void rememberFirstValidationError(
      String[] firstValidationError, String nodeId, String type, String message) {
    if (firstValidationError[0] == null && message != null && !message.isBlank()) {
      firstValidationError[0] = "node " + nodeId + " (" + type + "): " + message;
    }
  }

  private static String allowedKeySample(Set<String> allowedKeys) {
    if (allowedKeys == null || allowedKeys.isEmpty()) {
      return "(none)";
    }
    return allowedKeys.stream().sorted().limit(MAX_ALLOWED_KEYS_IN_MESSAGE).toList().toString();
  }

  private record ValidatedPatch(boolean valid, Map<String, Object> patchBody, String validationMessage) {
  }

  public record PropertiesApplyResult(
      int patchedCount, List<String> failedNodeIds, String firstValidationError) {
  }
}

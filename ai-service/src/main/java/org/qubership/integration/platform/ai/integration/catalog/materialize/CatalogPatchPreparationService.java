package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorDefaultsApplicator;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorPatchValidator;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorResourceLoader;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingEnrichResult;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingResolver;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingMergeNormalizer;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingProps;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogElementReadCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.ElementPatchValidationMessages;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Normalizes LLM patch JSON, merges partial {@code properties} with live catalog state when needed,
 * applies catalog descriptor defaults intersected with JSON Schema keys, runs deterministic JSON
 * Schema validation ({@code patchWithDefaults}), then validates merged {@code properties} against
 * embedded catalog descriptors (runtime-catalog {@code description.yaml} semantics) before {@code
 * updateElement}.
 */
@ApplicationScoped
public class CatalogPatchPreparationService {

  private static final Logger LOG = Logger.getLogger(CatalogPatchPreparationService.class);

  private static final String KEY_PARENT_ELEMENT_ID = "parentElementId";
  private static final String KEY_SWIMLANE_ID = "swimlaneId";
  private static final String KEY_MANDATORY_CHECKS_PASSED = "mandatoryChecksPassed";
  private static final String KEY_PROPERTIES = "properties";

  static final Set<String> PATCH_TOP_LEVEL_KEYS =
      Set.of(
          "name",
          "description",
          "type",
          KEY_PARENT_ELEMENT_ID,
          KEY_SWIMLANE_ID,
          KEY_MANDATORY_CHECKS_PASSED,
          KEY_PROPERTIES);

  private final CatalogRestClient catalogRestClient;
  private final CatalogElementReadCache catalogElementReadCache;
  private final DeterministicElementSchemaService deterministicElementSchemaService;
  private final ObjectMapper objectMapper;
  private final CatalogDescriptorResourceLoader catalogDescriptorResourceLoader;
  private final CatalogDescriptorDefaultsApplicator catalogDescriptorDefaultsApplicator;
  private final CatalogDescriptorPatchValidator catalogDescriptorPatchValidator;
  private final OperationBindingMergeNormalizer operationBindingMergeNormalizer;
  private final CatalogOperationBindingResolver operationBindingResolver;

  @Inject
  public CatalogPatchPreparationService(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogElementReadCache catalogElementReadCache,
      DeterministicElementSchemaService deterministicElementSchemaService,
      ObjectMapper objectMapper,
      CatalogDescriptorResourceLoader catalogDescriptorResourceLoader,
      CatalogDescriptorDefaultsApplicator catalogDescriptorDefaultsApplicator,
      CatalogDescriptorPatchValidator catalogDescriptorPatchValidator,
      OperationBindingMergeNormalizer operationBindingMergeNormalizer,
      CatalogOperationBindingResolver operationBindingResolver) {
    this.catalogRestClient = catalogRestClient;
    this.catalogElementReadCache = catalogElementReadCache;
    this.deterministicElementSchemaService = deterministicElementSchemaService;
    this.objectMapper = objectMapper;
    this.catalogDescriptorResourceLoader = catalogDescriptorResourceLoader;
    this.catalogDescriptorDefaultsApplicator = catalogDescriptorDefaultsApplicator;
    this.catalogDescriptorPatchValidator = catalogDescriptorPatchValidator;
    this.operationBindingMergeNormalizer = operationBindingMergeNormalizer;
    this.operationBindingResolver = operationBindingResolver;
  }

  /**
   * @param body mutable map safe to pass to {@link CatalogRestClient#updateElement}
   * @param elementType element type used for validation (may be null if validation skipped)
   * @param mergedWithCatalog true if {@code getElement} ran for properties merge
   * @param schemaValidated true if {@link DeterministicElementSchemaService#validateElementPatch}
   *     ran and passed
   */
  public record PreparedUpdateBody(
      Map<String, Object> body,
      String elementType,
      boolean mergedWithCatalog,
      boolean schemaValidated) {}

  /**
   * @throws IllegalArgumentException invalid JSON or schema validation failed when validation ran
   */
  public PreparedUpdateBody prepareUpdateElementBody(
      String chainId, String elementId, String patchJson) {
    Map<String, Object> body = copyToLinkedHashMap(normalizePatchBody(patchJson));
    Object propsObj = body.get(KEY_PROPERTIES);
    boolean nonEmptyProps = propsObj instanceof Map<?, ?> m && !m.isEmpty();

    if (!nonEmptyProps) {
      return new PreparedUpdateBody(body, null, false, false);
    }

    CatalogElementResponseDto current = catalogElementReadCache.getElement(chainId, elementId);
    String elementType = current != null ? CatalogStrings.blankToNull(current.type) : null;
    @SuppressWarnings("unchecked")
    Map<String, Object> patchProps = (Map<String, Object>) propsObj;
    Map<String, Object> base =
        current != null && current.properties != null ? current.properties : Map.of();
    Map<String, Object> merged = CatalogElementPropertiesMerge.merge(base, patchProps);
    body.put(KEY_PROPERTIES, merged);
    preservePlacementFields(body, current);

    LOG.infof(
        "catalog patch prep: merged properties chainId=%s elementId=%s mergedKeys=%s preview=%s",
        chainId,
        elementId,
        merged.keySet(),
        AiTraceLog.preview(writeJsonSafe(Map.of(KEY_PROPERTIES, merged)), 400));

    if (elementType == null || elementType.isBlank()) {
      LOG.warnf(
          "catalog patch prep: skipping schema validation (missing element type) chainId=%s"
              + " elementId=%s",
          chainId, elementId);
      return new PreparedUpdateBody(body, null, true, false);
    }

    enrichOperationBindingIfApplicable(elementType, merged);

    try {
      operationBindingMergeNormalizer.normalizeMergedProperties(elementType, merged);
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          e,
          "catalog patch prep: operation binding normalize failed chainId=%s elementId=%s"
              + " elementType=%s",
          chainId,
          elementId,
          elementType);
      throw e;
    }

    Optional<CatalogElementDescriptorModel> descriptor =
        catalogDescriptorResourceLoader.load(elementType);
    java.util.Set<String> schemaKeys =
        deterministicElementSchemaService.allowedPatchPropertyKeys(elementType);
    descriptor.ifPresent(
        d -> catalogDescriptorDefaultsApplicator.applyDefaults(d, merged, schemaKeys));

    String mergedJson = writeJsonSafe(body);
    String validationJson =
        deterministicElementSchemaService.validateElementPatch(elementType, mergedJson);
    applyPatchWithDefaultsIfPresent(body, validationJson);
    preservePlacementFields(body, current);

    boolean valid = readValidFlag(validationJson);
    if (!valid) {
      String summary =
          ElementPatchValidationMessages.summarizeFailure(validationJson, objectMapper);
      LOG.warnf(
          "catalog patch prep: schema validation failed chainId=%s elementId=%s elementType=%s"
              + " summary=%s validationPreview=%s",
          chainId,
          elementId,
          elementType,
          summary,
          AiTraceLog.previewOneLine(validationJson, 1200));
      throw new IllegalArgumentException(
          "Schema validation failed for element type " + elementType + ": " + summary);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> finalProps = (Map<String, Object>) body.get(KEY_PROPERTIES);
    descriptor.ifPresent(
        d -> catalogDescriptorPatchValidator.validateAndThrow(elementType, d, finalProps));

    return new PreparedUpdateBody(body, elementType, true, true);
  }

  private void enrichOperationBindingIfApplicable(String elementType, Map<String, Object> merged) {
    if (elementType == null
        || elementType.isBlank()
        || merged == null
        || OperationBindingProps.stringProp(merged, "integrationOperationId") == null) {
      return;
    }
    ElementPlan node = new ElementPlan();
    node.setType(elementType);
    node.setExpectedProperties(new LinkedHashMap<>(merged));
    CatalogOperationBindingEnrichResult enrichResult =
        operationBindingResolver.enrichForProperties(node);
    enrichResult
        .unresolvedReason()
        .ifPresent(reason -> {
          throw new IllegalArgumentException(reason);
        });
    Map<String, Object> enriched = enrichResult.properties();
    if (enriched != null && !enriched.isEmpty()) {
      merged.clear();
      merged.putAll(enriched);
    }
  }

  private static void preservePlacementFields(
      Map<String, Object> body, CatalogElementResponseDto current) {
    if (current == null) {
      return;
    }
    putIfPresent(body, KEY_PARENT_ELEMENT_ID, current.parentElementId);
    putIfPresent(body, KEY_SWIMLANE_ID, current.swimlaneId);
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

  private void applyPatchWithDefaultsIfPresent(Map<String, Object> body, String validationJson) {
    try {
      JsonNode root = objectMapper.readTree(validationJson);
      if (root.has("error") || !root.has("patchWithDefaults")) {
        return;
      }
      JsonNode patchDefaults = root.get("patchWithDefaults");
      if (!patchDefaults.isObject()) {
        return;
      }
      Map<String, Object> replaced =
          objectMapper.convertValue(patchDefaults, new TypeReference<>() {});
      body.clear();
      body.putAll(replaced);
      LOG.infof(
          "catalog patch prep: applied patchWithDefaults preview=%s",
          AiTraceLog.preview(writeJsonSafe(body), 400));
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "catalog patch prep: failed to parse validation JSON for patchWithDefaults");
    }
  }

  private boolean readValidFlag(String validationJson) {
    try {
      JsonNode root = objectMapper.readTree(validationJson);
      if (root.has("error")) {
        return false;
      }
      return root.path("valid").asBoolean(false);
    } catch (Exception e) {
      return false;
    }
  }

  private String writeJsonSafe(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return String.valueOf(value);
    }
  }

  Map<String, Object> normalizePatchBody(String patchJson) {
    Map<String, Object> root = parseJsonObjectMandatory(patchJson, "updateElement");
    boolean hasKnownTop = false;
    for (String k : PATCH_TOP_LEVEL_KEYS) {
      if (root.containsKey(k)) {
        hasKnownTop = true;
        break;
      }
    }
    if (!hasKnownTop) {
      Map<String, Object> wrapped = new LinkedHashMap<>();
      wrapped.put(KEY_PROPERTIES, new LinkedHashMap<>(root));
      return wrapped;
    }
    return root;
  }

  private static Map<String, Object> copyToLinkedHashMap(Map<String, Object> src) {
    return new LinkedHashMap<>(src);
  }

  private Map<String, Object> parseJsonObjectMandatory(String json, String step) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("JSON body is required for " + step);
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      if (!node.isObject()) {
        throw new IllegalArgumentException("JSON for " + step + " must be an object");
      }
      return objectMapper.convertValue(node, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Invalid JSON for " + step + ": " + e.getOriginalMessage(), e);
    }
  }
}

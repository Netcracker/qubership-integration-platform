package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.rag.RagRetriever;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

import java.util.Map;

/**
 * Tools exposing QIP element schemas for PATCH planning (deterministic classpath YAML) with
 * optional RAG fallback.
 */
@ApplicationScoped
public class ElementSchemaTools {

  private static final String TOOL_PATCH_SCHEMA = "describeElementPatchSchema";
  private static final String TOOL_PROPERTY = "describeElementProperty";
  private static final String TOOL_RAG_DOC = "getElementSchemaDocumentation";

  private static final Logger LOG = Logger.getLogger(ElementSchemaTools.class);

  @Inject RagRetriever ragRetriever;

  @Inject DeterministicElementSchemaService deterministicElementSchemaService;

  @Inject CatalogToolSupport support;

  @Inject ObjectMapper objectMapper;

  @Tool(
      "Return a compact deterministic JSON summary of allowed catalog properties for PATCHing this"
          + " element type. Call before the first updateElement for that element (required for"
          + " branching/container types: condition, split-2, try-catch-finally-2,"
          + " circuit-breaker-2, loop-2). Use describeElementProperty when detailsAvailable is true"
          + " for a field. Returns JSON: { ok, tool, data: schema summary }.")
  public String describeElementPatchSchema(
      @P("Element type, e.g. service-call, http-trigger, script, condition, split-2")
          String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return support.catalogToolError(
          TOOL_PATCH_SCHEMA,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "elementType is required",
          "Pass a catalog element type such as service-call or http-trigger.");
    }
    String trimmed = elementType.trim();
    LOG.infof("Schema tool describeElementPatchSchema: elementType=%s", trimmed);
    try {
      String schemaJson = deterministicElementSchemaService.describeElementPatchSchema(trimmed);
      return support.catalogToolSuccess(TOOL_PATCH_SCHEMA, parseSchemaPayload(schemaJson));
    } catch (Exception e) {
      LOG.warnf(e, "describeElementPatchSchema failed for type=%s", trimmed);
      return support.catalogToolError(
          TOOL_PATCH_SCHEMA, CatalogToolResult.CODE_TOOL_EXECUTION_ERROR, e.getMessage());
    }
  }

  @Tool(
      "Return a compact JSON description for one catalog property path (lazy details). Use when"
          + " describeElementPatchSchema marks detailsAvailable or after updateElement/schema"
          + " errors when you need enums, const values, or structure for a known property path."
          + " Returns JSON: { ok, tool, data: property description }.")
  public String describeElementProperty(
      @P("Element type, e.g. service-call") String elementType,
      @P(
              "Property path under catalog properties, e.g. authorizationConfiguration or"
                  + " properties.authorizationConfiguration")
          String propertyPath) {
    if (elementType == null || elementType.isBlank()) {
      return support.catalogToolError(
          TOOL_PROPERTY,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "elementType is required",
          null);
    }
    if (propertyPath == null || propertyPath.isBlank()) {
      return support.catalogToolError(
          TOOL_PROPERTY,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "propertyPath is required",
          null);
    }
    LOG.infof(
        "Schema tool describeElementProperty: elementType=%s propertyPath=%s",
        elementType.trim(), propertyPath.trim());
    try {
      String propertyJson =
          deterministicElementSchemaService.describeElementProperty(
              elementType.trim(), propertyPath.trim());
      return support.catalogToolSuccess(TOOL_PROPERTY, parseSchemaPayload(propertyJson));
    } catch (Exception e) {
      LOG.warnf(e, "describeElementProperty failed for type=%s", elementType.trim());
      return support.catalogToolError(
          TOOL_PROPERTY, CatalogToolResult.CODE_TOOL_EXECUTION_ERROR, e.getMessage());
    }
  }

  @Tool(
      "Optional: load QIP element schema documentation from the embedded knowledge base (RAG"
          + " chunks). Prefer describeElementPatchSchema for allowed PATCH keys; use"
          + " describeElementProperty for one path; use this for extra prose. Returns JSON: { ok,"
          + " tool, data: { documentation } }.")
  public String getElementSchemaDocumentation(
      @P("Element type, e.g. service-call, http-trigger, script") String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return support.catalogToolError(
          TOOL_RAG_DOC,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "elementType is required",
          null);
    }
    String trimmed = elementType.trim();
    LOG.infof("Schema tool getElementSchemaDocumentation: elementType=%s", trimmed);
    try {
      String doc = ragRetriever.toContextString(ragRetriever.retrieveElementSchema(trimmed));
      return support.catalogToolSuccess(TOOL_RAG_DOC, Map.of("documentation", doc));
    } catch (Exception e) {
      LOG.warnf(e, "getElementSchemaDocumentation failed for type=%s", trimmed);
      return support.catalogToolError(
          TOOL_RAG_DOC, CatalogToolResult.CODE_TOOL_EXECUTION_ERROR, e.getMessage());
    }
  }

  private Object parseSchemaPayload(String json) throws Exception {
    String trimmed = json != null ? json.trim() : "";
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return objectMapper.readValue(trimmed, Object.class);
    }
    return Map.of("raw", json);
  }
}

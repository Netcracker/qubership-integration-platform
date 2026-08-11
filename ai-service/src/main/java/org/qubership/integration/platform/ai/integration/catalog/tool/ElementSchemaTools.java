package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exposes deterministic classpath element schemas for PATCH planning. */
@ApplicationScoped
public class ElementSchemaTools {

  private static final String TOOL_PATCH_SCHEMA = "describeElementPatchSchema";
  private static final String TOOL_PROPERTY = "describeElementProperty";

  private static final Logger LOG = Logger.getLogger(ElementSchemaTools.class);

  private final DeterministicElementSchemaService deterministicElementSchemaService;
  private final CatalogToolSupport support;
  private final ObjectMapper objectMapper;

  @Inject
  public ElementSchemaTools(
      DeterministicElementSchemaService deterministicElementSchemaService,
      CatalogToolSupport support,
      ObjectMapper objectMapper) {
    this.deterministicElementSchemaService = deterministicElementSchemaService;
    this.support = support;
    this.objectMapper = objectMapper;
  }

  @Tool("Return a compact deterministic JSON summary of allowed catalog properties for PATCHing this"
      + " element type (catalog PATCH shape: properties object map). Includes"
      + " requiredProperties (unconditional) and rootOneOfAlternatives (per-branch required"
      + " keys, e.g. http-trigger Custom needs contextPath). For captureChainPlan, use plan"
      + " node properties as [{key,value}] array instead. Call before the first updateElement for"
      + " that element (required for branching/container types: condition, split-2,"
      + " try-catch-finally-2, circuit-breaker-2, loop-2). Use describeElementProperty when"
      + " detailsAvailable is true for a field. Returns JSON: { ok, tool, data: schema summary }.")
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
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, TOOL_PATCH_SCHEMA, null, "elementType=" + trimmed);
    try {
      String schemaJson = deterministicElementSchemaService.describeElementPatchSchema(trimmed);
      String out =
          support.catalogToolSuccess(TOOL_PATCH_SCHEMA, withPlanCaptureNote(parseSchemaPayload(schemaJson)));
      support.logCatalogToolDone(TOOL_PATCH_SCHEMA, out);
      ToolTraceLog.logToolComplete(LOG, TOOL_PATCH_SCHEMA, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      LOG.warnf(e, "describeElementPatchSchema failed for type=%s", trimmed);
      String out =
          support.catalogToolError(
              TOOL_PATCH_SCHEMA, CatalogToolResult.CODE_TOOL_EXECUTION_ERROR, e.getMessage());
      support.logCatalogToolDone(TOOL_PATCH_SCHEMA, out);
      ToolTraceLog.logToolComplete(LOG, TOOL_PATCH_SCHEMA, null, System.currentTimeMillis() - startMs, out);
      return out;
    }
  }

  @Tool("Return a compact JSON description for one catalog property path (lazy details). Use when"
      + " describeElementPatchSchema marks detailsAvailable or after updateElement/schema"
      + " errors when you need enums, const values, or structure for a known property path."
      + " Returns JSON: { ok, tool, data: property description }.")
  public String describeElementProperty(
      @P("Element type, e.g. service-call") String elementType,
      @P("Property path under catalog properties, e.g. authorizationConfiguration") String propertyPath) {
    if (elementType == null || elementType.isBlank()) {
      return support.catalogToolError(
          TOOL_PROPERTY, CatalogToolResult.CODE_INVALID_ARGUMENT, "elementType is required", null);
    }
    if (propertyPath == null || propertyPath.isBlank()) {
      return support.catalogToolError(
          TOOL_PROPERTY, CatalogToolResult.CODE_INVALID_ARGUMENT, "propertyPath is required", null);
    }
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        TOOL_PROPERTY,
        null,
        "elementType=" + elementType.trim() + " propertyPath=" + propertyPath.trim());
    try {
      String propertyJson =
          deterministicElementSchemaService.describeElementProperty(
              elementType.trim(), propertyPath.trim());
      String out = support.catalogToolSuccess(TOOL_PROPERTY, parseSchemaPayload(propertyJson));
      support.logCatalogToolDone(TOOL_PROPERTY, out);
      ToolTraceLog.logToolComplete(LOG, TOOL_PROPERTY, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      LOG.warnf(e, "describeElementProperty failed for type=%s", elementType.trim());
      String out =
          support.catalogToolError(
              TOOL_PROPERTY, CatalogToolResult.CODE_TOOL_EXECUTION_ERROR, e.getMessage());
      support.logCatalogToolDone(TOOL_PROPERTY, out);
      ToolTraceLog.logToolComplete(LOG, TOOL_PROPERTY, null, System.currentTimeMillis() - startMs, out);
      return out;
    }
  }

  private Object withPlanCaptureNote(Object payload) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (payload instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        data.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    } else {
      data.put("schema", payload);
    }
    data.put(
        "planCaptureNote",
        "describeElementPatchSchema describes catalog PATCH shape (properties object)."
            + " captureChainPlan uses plan node properties as [{key,value}] array; use [] when empty.");
    return data;
  }

  private Object parseSchemaPayload(String json) throws Exception {
    String trimmed = json != null ? json.trim() : "";
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return objectMapper.readValue(trimmed, Object.class);
    }
    return Map.of("raw", json);
  }
}

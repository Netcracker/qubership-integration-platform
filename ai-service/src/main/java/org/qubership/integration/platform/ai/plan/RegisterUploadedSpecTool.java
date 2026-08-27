package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.attachment.SpecType;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecEntry;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecStore;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.storage.S3Service;

/**
 * Registers an uploaded OpenAPI or AsyncAPI spec as an {@link UploadedSpecEntry} for the active
 * conversation so that downstream plan generation can reference it.
 */
@ApplicationScoped
public class RegisterUploadedSpecTool {

  private static final Logger LOG = Logger.getLogger(RegisterUploadedSpecTool.class);

  static final String TOOL_NAME = "registerUploadedSpec";

  private static final int MAX_OPERATIONS_IN_SUMMARY = 5;

  private static final Map<String, String> OPENAPI_METHODS =
      Map.of(
          "get", "GET",
          "post", "POST",
          "put", "PUT",
          "delete", "DELETE",
          "patch", "PATCH",
          "head", "HEAD",
          "options", "OPTIONS",
          "trace", "TRACE");

  private final S3Service s3Service;
  private final UploadedSpecStore uploadedSpecStore;
  private final ConversationService conversationService;
  private final ObjectMapper objectMapper;

  @Inject
  public RegisterUploadedSpecTool(
      S3Service s3Service,
      UploadedSpecStore uploadedSpecStore,
      ConversationService conversationService,
      ObjectMapper objectMapper) {
    this.s3Service = s3Service;
    this.uploadedSpecStore = uploadedSpecStore;
    this.conversationService = conversationService;
    this.objectMapper = objectMapper;
  }

  RegisterUploadedSpecTool(S3Service s3Service, UploadedSpecStore uploadedSpecStore) {
    this(s3Service, uploadedSpecStore, null, new ObjectMapper());
  }

  @Tool("""
      Register an uploaded API specification (OpenAPI or AsyncAPI) so it can be used by planning
      tools. The server reads the object from S3, extracts title/version/type, records an operations
      summary, and stores the metadata in the conversation. Required: s3Key. Optional: originalFilename.
      Returns JSON: { ok, tool, title, version, type, operationsSummary }.
      """)
  public String registerUploadedSpec(
      @P("S3 object key of the uploaded spec file") String s3Key,
      @P("Original filename of the uploaded spec") String originalFilename) {
    String conversationId = ChainPlanTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        TOOL_NAME,
        conversationId,
        "s3Key="
            + AiTraceLog.preview(s3Key, 80)
            + " originalFilename="
            + AiTraceLog.preview(originalFilename, 60));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(
            conversationId, startMs, errorJson("conversationId is required (no active chat session)"));
      }

      String resolvedKey = CatalogStrings.blankToNull(s3Key);
      if (resolvedKey == null) {
        return finish(conversationId, startMs, errorJson("s3Key is required"));
      }

      if (conversationService != null
          && !conversationService.getAllowedAttachmentKeys(conversationId).contains(resolvedKey)) {
        return finish(
            conversationId,
            startMs,
            errorJson("s3Key is not registered for this conversation: " + resolvedKey));
      }

      byte[] bytes = s3Service.readObjectBytes(resolvedKey);
      JsonNode root = objectMapper.readTree(bytes);

      SpecHeader header = extractHeader(root);
      if (header.specType() == null) {
        return finish(
            conversationId,
            startMs,
            errorJson("Unsupported spec: expected 'openapi' or 'asyncapi' field"));
      }

      String operationsSummary = buildOperationsSummary(root, header.specType());
      UploadedSpecEntry entry =
          new UploadedSpecEntry(
              resolvedKey,
              CatalogStrings.blankToNull(originalFilename),
              header.specType(),
              header.title(),
              header.version(),
              operationsSummary);
      uploadedSpecStore.register(conversationId, entry);

      ObjectNode result = objectMapper.createObjectNode();
      result.put("ok", true);
      result.put("tool", TOOL_NAME);
      result.put("title", header.title());
      result.put("version", header.version());
      result.put("type", header.specType().name());
      result.put("operationsSummary", operationsSummary);
      String json = objectMapper.writeValueAsString(result);

      LOG.infof(
          "%s: registered conversationId=%s s3Key=%s type=%s title=%s version=%s",
          TOOL_NAME,
          conversationId,
          resolvedKey,
          header.specType(),
          header.title(),
          header.version());
      return finish(conversationId, startMs, json);
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, e);
      return errorJson("Error registering uploaded spec: " + e.getMessage());
    }
  }

  private SpecHeader extractHeader(JsonNode root) {
    String title = null;
    String version = null;
    SpecType specType = null;

    if (root.hasNonNull("openapi")) {
      specType = SpecType.OPENAPI;
    } else if (root.hasNonNull("asyncapi")) {
      specType = SpecType.ASYNCAPI;
    }

    JsonNode info = root.path("info");
    if (!info.isMissingNode()) {
      title = CatalogStrings.blankToNull(info.path("title").asText(null));
      version = CatalogStrings.blankToNull(info.path("version").asText(null));
    }

    return new SpecHeader(title, version, specType);
  }

  private String buildOperationsSummary(JsonNode root, SpecType specType) {
    List<String> items = new ArrayList<>();
    if (specType == SpecType.OPENAPI) {
      JsonNode paths = root.path("paths");
      if (paths.isObject()) {
        for (Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields(); pathIt.hasNext(); ) {
          Map.Entry<String, JsonNode> pathEntry = pathIt.next();
          JsonNode pathNode = pathEntry.getValue();
          if (!pathNode.isObject()) {
            continue;
          }
          for (Iterator<Map.Entry<String, JsonNode>> methodIt = pathNode.fields();
              methodIt.hasNext(); ) {
            Map.Entry<String, JsonNode> methodEntry = methodIt.next();
            String verb = OPENAPI_METHODS.get(methodEntry.getKey().toLowerCase());
            if (verb != null) {
              items.add(verb + " " + pathEntry.getKey());
            }
          }
        }
      }
    } else if (specType == SpecType.ASYNCAPI) {
      JsonNode channels = root.path("channels");
      if (channels.isObject()) {
        for (Iterator<String> it = channels.fieldNames(); it.hasNext(); ) {
          items.add("channel " + it.next());
        }
      }
    }

    if (items.isEmpty()) {
      return "";
    }
    if (items.size() <= MAX_OPERATIONS_IN_SUMMARY) {
      return String.join(", ", items);
    }
    List<String> head = items.subList(0, MAX_OPERATIONS_IN_SUMMARY);
    int remaining = items.size() - MAX_OPERATIONS_IN_SUMMARY;
    return String.join(", ", head) + " and " + remaining + " more";
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, TOOL_NAME, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private String errorJson(String message) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("ok", false);
      root.put("tool", TOOL_NAME);
      root.put("error", message);
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"ok\":false,\"tool\":\""
          + TOOL_NAME
          + "\",\"error\":\""
          + message.replace("\"", "\\\"")
          + "\"}";
    }
  }

  private record SpecHeader(String title, String version, SpecType specType) {}
}

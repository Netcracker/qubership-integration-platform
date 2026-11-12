package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;

/** Private opt-in evidence at provider delegation; recording cannot change model execution. */
final class ProviderBoundaryEvidence {
  private static final Logger LOG = Logger.getLogger(ProviderBoundaryEvidence.class);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
      .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
      .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
      .disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
  private static final java.util.regex.Pattern SENSITIVE_FIELD = java.util.regex.Pattern.compile(
      "(?i).*(password|secret|api.?key|authorization|access.?token|refresh.?token|headers).*");
  private final Path directory;
  private final String conversationId;

  ProviderBoundaryEvidence(Path directory) {
    this.directory = directory;
    String owner = null;
    if (directory != null) {
      try { owner = json.readTree(directory.resolve("plan.json").toFile()).path("conversationId").asText(null); }
      catch (java.io.IOException failure) { LOG.warn("Provider evidence ownership is unavailable", failure); }
    }
    this.conversationId = owner;
  }

  String request(ChatRequest request, String runId) {
    if (directory == null || conversationId == null) return null;
    String id = UUID.randomUUID().toString();
    try {
      var event = json.createObjectNode();
      event.put("id", id);
      event.put("runId", runId);
      event.put("conversationId", String.valueOf(MDC.get("conversationId")));
      event.put("capabilityId", String.valueOf(MDC.get("compilerSkillCapabilityId")));
      var contextFile = directory.resolve("provider-context.json");
      JsonNode context = Files.exists(contextFile) ? json.readTree(contextFile.toFile()) : json.nullNode();
      boolean owned = conversationId.equals(String.valueOf(MDC.get("conversationId")))
          || runId != null && runId.equals(context.path("runId").asText())
              && conversationId.equals(context.path("conversationId").asText());
      if (!owned) return null;
      if (!context.isNull()) event.set("context", context);
      event.put("at", java.time.Instant.now().toString());
      JsonNode original = json.valueToTree(request);
      event.put("originalRequestHash", hash(original.toString()));
      var redacted = redact(original);
      event.put("redactedRequestHash", hash(redacted.toString()));
      event.put("redacted", !original.equals(redacted));
      event.set("request", redacted);
      save(id + "-request", event);
    } catch (Exception failure) { LOG.warn("Provider request evidence is incomplete", failure); }
    return id;
  }

  static Object response(dev.langchain4j.model.chat.response.ChatResponse response) {
    if (response == null) return null;
    var value = new java.util.LinkedHashMap<String, Object>();
    value.put("message", response.aiMessage());
    value.put("usage", response.tokenUsage());
    value.put("finishReason", response.finishReason());
    value.put("id", response.metadata() == null ? null : response.metadata().id());
    return value;
  }

  void outcome(String id, String kind, Object value) {
    if (id == null) return;
    try {
      var event = json.createObjectNode();
      event.put("requestId", id);
      event.put("kind", kind);
      event.put("at", java.time.Instant.now().toString());
      event.set("value", redact(json.valueToTree(value)));
      save(id + "-" + kind, event);
    } catch (Exception failure) { LOG.warn("Provider outcome evidence is incomplete", failure); }
  }

  private void save(String name, JsonNode value) throws java.io.IOException {
    var output = directory.resolve("provider-boundary");
    Files.createDirectories(output);
    var temporary = Files.createTempFile(output, "record-", ".tmp");
    json.writeValue(temporary.toFile(), value);
    Files.move(temporary, output.resolve(name + ".json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  static String hash(String value) {
    return org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash.sha256Hex(value);
  }

  private JsonNode redact(JsonNode node) {
    if (node.isObject()) {
      var copy = ((ObjectNode) node).deepCopy();
      node.properties().forEach(entry -> copy.set(entry.getKey(),
          SENSITIVE_FIELD.matcher(entry.getKey()).matches()
              ? json.getNodeFactory().textNode("[REDACTED]") : redact(entry.getValue())));
      if (node.has("key") && SENSITIVE_FIELD.matcher(node.path("key").asText()).matches() && node.has("value")) {
        copy.put("value", "[REDACTED]");
      }
      return copy;
    }
    if (node.isArray()) {
      var copy = json.createArrayNode();
      node.forEach(child -> copy.add(redact(child)));
      return copy;
    }
    if (node.isTextual()) {
      String text = node.asText();
      if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
        try {
          var parsed = json.readTree(text);
          var clean = redact(parsed);
          if (!parsed.equals(clean)) text = clean.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ordinaryText) {
          // Tool errors and source prose need not be JSON.
        }
      }
      return json.getNodeFactory().textNode(text
        .replaceAll("sk-[A-Za-z0-9_-]{16,}", "[REDACTED]")
        .replaceAll("(?i)Bearer [A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]")
        .replaceAll("(?i)((?:api[-_]?key|password|secret|access_token|refresh_token)\\s*[=:]\\s*)[^\\s&;]+", "$1[REDACTED]"));
    }
    return node;
  }
}

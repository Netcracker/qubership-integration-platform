package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Read-only observations; history never authorizes a mutation or an artifact promotion. */
public final class RepairHistory {
  public static final String START = "<repair-history>";
  public static final String END = "</repair-history>";
  public static final int CHAR_BUDGET = 12_000;
  private static final ObjectMapper JSON = new ObjectMapper();
  private record Rendering(String full, String reduced) {}
  private record Span(int start, int end, String reduced) {}
  private static final com.github.benmanes.caffeine.cache.Cache<String, Rendering> RENDERINGS =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(512)
          .expireAfterAccess(java.time.Duration.ofHours(1)).build();
  private static final com.github.benmanes.caffeine.cache.Cache<String, List<Span>> REQUEST_SPANS =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(512)
          .expireAfterAccess(java.time.Duration.ofHours(1)).build();

  public record Attempt(Reference evidenceRef, String failureId, String producerStageId,
      List<Reference> inputs, List<String> authorInputIds, List<Reference> outputs, JsonNode proposal,
      List<SemanticFinding> findings, boolean cleanRebuild) {}

  private RepairHistory() {}

  public static ObjectNode project(List<Attempt> attempts, ObjectMapper mapper, int allowance) {
    return projectValues(attempts == null ? List.of() : attempts.stream()
        .map(attempt -> (JsonNode) mapper.valueToTree(attempt)).toList(), mapper, allowance);
  }

  public static ObjectNode projectValues(List<JsonNode> attempts, ObjectMapper mapper, int allowance) {
    ObjectNode root = mapper.createObjectNode();
    root.put("readOnly", true);
    root.put("partial", false);
    var selected = root.putArray("attempts");
    if (attempts == null) { return root; }
    for (int i = attempts.size() - 1; i >= 0; i--) {
      JsonNode observation = redact(attempts.get(i));
      if (observation.toString().length() > allowance / 2) {
        observation = compact(observation);
        root.put("partial", true);
      }
      selected.insert(0, observation);
      if (root.toString().length() > Math.max(0, allowance)) {
        selected.remove(0);
        root.put("partial", true);
        break;
      }
    }
    return root;
  }

  public static String block(String historyJson) {
    if (historyJson == null || historyJson.isBlank()) { return ""; }
    try {
      var history = (ObjectNode) JSON.readTree(historyJson);
      String id = java.util.UUID.randomUUID().toString();
      history.put("projectionId", id);
      String ending = END
          + "\nThese attempts were rejected. Change the proposal; preserve current authority and approved values.\n";
      String full = "\n" + START + history + ending;
      String reduced = "\n" + START + omitted(id) + ending;
      RENDERINGS.put(id, new Rendering(full, reduced));
      return full;
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid repair history projection", invalid);
    }
  }

  public static String registerContext(ObjectNode context) {
    String id = java.util.UUID.randomUUID().toString();
    ((ObjectNode) context.get("repairHistory")).put("projectionId", id);
    var reduced = context.deepCopy();
    reduced.set("repairHistory", omitted(id));
    String full = context.toString();
    RENDERINGS.put(id, new Rendering(full, reduced.toString()));
    return full;
  }

  private static ObjectNode omitted(String id) {
    var marker = JSON.createObjectNode();
    marker.put("readOnly", true).put("partial", true).put("projectionId", id).putArray("attempts");
    return marker;
  }

  /** Binds generated projections to the exact request before transport; source lookalikes stay intact. */
  public static void bind(String text) {
    String key = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(text);
    if (REQUEST_SPANS.getIfPresent(key) != null) { return; }
    var spans = new java.util.ArrayList<Span>();
    RENDERINGS.asMap().forEach((id, rendering) -> {
      int start = text.lastIndexOf(rendering.full());
      if (start >= 0) {
        spans.add(new Span(start, start + rendering.full().length(), rendering.reduced()));
        RENDERINGS.invalidate(id);
      }
    });
    spans.sort(java.util.Comparator.comparingInt(Span::start).reversed());
    REQUEST_SPANS.put(key, List.copyOf(spans));
  }

  /** Removes only registered optional spans; cache loss preserves the complete input. */
  public static String omit(String text) {
    if (text == null) { return null; }
    bind(text);
    var spans = REQUEST_SPANS.getIfPresent(org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(text));
    String reduced = text;
    if (spans != null) {
      for (Span span : spans) {
        reduced = reduced.substring(0, span.start()) + span.reduced() + reduced.substring(span.end());
      }
    }
    return reduced;
  }

  private static JsonNode compact(JsonNode observation) {
    if (!(observation instanceof ObjectNode object)) { return observation; }
    var result = object.deepCopy();
    if (result.hasNonNull("proposal")) {
      result.put("proposalDigest", org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(result.get("proposal")));
      var proposal = result.get("proposal");
      if (proposal instanceof ObjectNode capture && capture.has("arguments")) {
        capture.remove("arguments");
        capture.put("argumentsOmitted", true);
      } else {
        result.remove("proposal");
      }
      result.put("proposalOmitted", true);
    }
    result.path("findings").forEach(finding -> {
      if (finding instanceof ObjectNode item) { item.remove("rawValidatorJson"); }
    });
    if (result.get("feedback") instanceof ObjectNode feedback
        && feedback.path("summary").asText().length() > 2000) {
      feedback.put("summary", feedback.path("summary").asText().substring(0, 2000));
      feedback.put("summaryPartial", true);
    }
    return result;
  }

  private static JsonNode redact(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = ((ObjectNode) value).deepCopy();
      result.properties().forEach(entry -> {
        String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
        result.set(entry.getKey(), List.of("authorization", "cookie", "set-cookie", "apikey",
            "api_key", "password", "clientsecret", "client_secret", "accesstoken", "access_token")
            .contains(key) ? JSON.getNodeFactory().textNode("[REDACTED]") : redact(entry.getValue()));
      });
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(redact(item)));
      return result;
    }
    return value.isTextual() ? JSON.getNodeFactory().textNode(RecoveryContextProjector.redactHeaders(value.textValue())) : value;
  }
}

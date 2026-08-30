package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Projects {@link RecoveryContext} into lossless, redacted JSON for the recovery LLM turn. */
public final class RecoveryContextProjector {

  public static final int CHAR_BUDGET = 100_000;

  private static final List<String> SENSITIVE_HEADERS =
      List.of("Authorization", "Cookie", "Set-Cookie");

  private RecoveryContextProjector() {}

  public static String project(RecoveryContext context, ObjectMapper objectMapper) {
    if (context == null) {
      return "{}";
    }
    ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    RecoveryEvidence evidence = context.evidence();
    ObjectNode root = mapper.createObjectNode();
    if (evidence != null) {
      root.put("failureId", evidence.failureId());
      root.set("evidence", evidenceNode(redactedEvidence(evidence), mapper));
    }
    root.set("approvedBrief", briefNode(context.approvedBrief(), mapper));
    if (context.rejectedArtifact() != null) {
      root.set("rejectedArtifact", mapper.valueToTree(context.rejectedArtifact()));
    }
    if (context.responseLocale() != null && !context.responseLocale().isBlank()) {
      root.put("responseLocale", context.responseLocale().trim());
    }

    String inline = write(root, mapper);
    if (inline.length() <= CHAR_BUDGET) {
      return inline;
    }
    return projectWithAttachments(context, mapper);
  }

  private static String projectWithAttachments(RecoveryContext context, ObjectMapper mapper) {
    RecoveryEvidence evidence = context.evidence();
    Map<String, String> attachments = new LinkedHashMap<>();
    List<String> manifest = new ArrayList<>();

    ObjectNode root = mapper.createObjectNode();
    if (evidence != null) {
      root.put("failureId", evidence.failureId());
      ObjectNode evidenceNode = evidenceNode(redactedEvidence(evidence), mapper);
      externalizeFindings(evidenceNode, attachments, manifest);
      externalizeTechnicalFailure(evidenceNode, attachments, manifest, mapper);
      root.set("evidence", evidenceNode);
    }
    root.set("approvedBrief", briefNode(context.approvedBrief(), mapper));
    if (context.rejectedArtifact() != null) {
      String key = "rejectedArtifact";
      manifest.add(key);
      attachments.put(key, write(mapper.valueToTree(context.rejectedArtifact()), mapper));
      root.put("rejectedArtifactRef", key);
    }
    if (context.responseLocale() != null && !context.responseLocale().isBlank()) {
      root.put("responseLocale", context.responseLocale().trim());
    }

    ArrayNode manifestNode = mapper.createArrayNode();
    manifest.forEach(manifestNode::add);
    root.set("manifest", manifestNode);
    root.set("attachments", mapper.valueToTree(attachments));
    return write(root, mapper);
  }

  private static void externalizeFindings(
      ObjectNode evidenceNode, Map<String, String> attachments, List<String> manifest) {
    if (!evidenceNode.has("findings") || !evidenceNode.get("findings").isArray()) {
      return;
    }
    ArrayNode findings = (ArrayNode) evidenceNode.get("findings");
    for (int index = 0; index < findings.size(); index++) {
      if (!findings.get(index).isObject()) {
        continue;
      }
      ObjectNode finding = (ObjectNode) findings.get(index);
      if (!finding.has("rawValidatorJson")) {
        continue;
      }
      String key = "finding-" + index;
      manifest.add(key);
      attachments.put(key, finding.remove("rawValidatorJson").asText());
      finding.put("attachmentRef", key);
    }
  }

  private static void externalizeTechnicalFailure(
      ObjectNode evidenceNode,
      Map<String, String> attachments,
      List<String> manifest,
      ObjectMapper mapper) {
    if (!evidenceNode.has("technicalFailure") || !evidenceNode.get("technicalFailure").isObject()) {
      return;
    }
    ObjectNode technical = (ObjectNode) evidenceNode.remove("technicalFailure");
    String key = "technicalFailure";
    manifest.add(key);
    attachments.put(key, write(technical, mapper));
    evidenceNode.put("technicalFailureRef", key);
  }

  private static RecoveryEvidence redactedEvidence(RecoveryEvidence evidence) {
    return new RecoveryEvidence(
        evidence.schemaVersion(),
        evidence.failureId(),
        evidence.observedCauseCode(),
        evidence.observingStageId(),
        evidence.approvedBriefRef(),
        evidence.approvedSemanticRef(),
        evidence.rejectedArtifactRefs(),
        evidence.findings(),
        redactedTechnicalFailure(evidence.technicalFailure()),
        evidence.priorAttemptRefs());
  }

  private static TechnicalFailureRecord redactedTechnicalFailure(TechnicalFailureRecord record) {
    if (record == null) {
      return null;
    }
    return new TechnicalFailureRecord(
        record.retryable(),
        record.attemptCount(),
        redactHeaders(record.dependencyName()),
        redactHeaders(record.operation()),
        redactHeaders(record.timeout()),
        record.correlationId(),
        redactHeaders(record.exceptionType()),
        redactHeaders(record.exceptionMessage()),
        redactHeaders(record.responseStatus()),
        redactHeaders(record.sanitizedTarget()));
  }

  static String redactHeaders(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = text;
    for (String header : SENSITIVE_HEADERS) {
      Pattern pattern =
          Pattern.compile("(?i)(" + Pattern.quote(header) + "\\s*:\\s*)([^\\r\\n;,]+)");
      result = pattern.matcher(result).replaceAll("$1[REDACTED]");
    }
    return result;
  }

  private static ObjectNode evidenceNode(RecoveryEvidence evidence, ObjectMapper mapper) {
    ObjectNode node = mapper.createObjectNode();
    node.put("schemaVersion", evidence.schemaVersion());
    node.put("failureId", evidence.failureId());
    node.put("observedCauseCode", evidence.observedCauseCode());
    node.put("observingStageId", evidence.observingStageId());
    if (evidence.approvedBriefRef() != null) {
      node.set("approvedBriefRef", mapper.valueToTree(evidence.approvedBriefRef()));
    }
    if (evidence.approvedSemanticRef() != null) {
      node.set("approvedSemanticRef", mapper.valueToTree(evidence.approvedSemanticRef()));
    }
    node.set("rejectedArtifactRefs", mapper.valueToTree(evidence.rejectedArtifactRefs()));
    node.set("findings", mapper.valueToTree(evidence.findings()));
    if (evidence.technicalFailure() != null) {
      node.set("technicalFailure", mapper.valueToTree(evidence.technicalFailure()));
    }
    node.set("priorAttemptRefs", mapper.valueToTree(evidence.priorAttemptRefs()));
    return node;
  }

  private static ObjectNode briefNode(RequirementBrief brief, ObjectMapper mapper) {
    ObjectNode node = mapper.createObjectNode();
    if (brief == null) {
      return node;
    }
    node.put("goal", brief.goal());
    node.set("facts", mapper.valueToTree(brief.facts()));
    return node;
  }

  private static String write(com.fasterxml.jackson.databind.JsonNode node, ObjectMapper mapper) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}

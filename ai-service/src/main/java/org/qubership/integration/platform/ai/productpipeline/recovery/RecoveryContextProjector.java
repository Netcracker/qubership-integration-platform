package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.regex.Pattern;

/** Projects complete, redacted context and refuses diagnostic turns over the size budget. */
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
    root.set("approvedBrief", mapper.valueToTree(context.approvedBrief()));
    if (context.approvedPlanRef() != null) {
      root.set("approvedPlanRef", mapper.valueToTree(context.approvedPlanRef()));
      root.set("approvedPlan", mapper.valueToTree(context.approvedPlan()));
    }
    root.set("evidenceRefIds", mapper.valueToTree(
        RecoveryDecisionValidator.evidenceReferenceIds(context).stream().sorted().toList()));
    var inputs = root.putArray("authorInputs");
    context.authorInputs().forEach(input -> inputs.addObject()
        .put("inputId", input.inputId()).put("text", input.text()));
    if (context.rejectedArtifact() != null) {
      root.set("rejectedArtifact", mapper.valueToTree(context.rejectedArtifact()));
    }
    if (context.responseLocale() != null && !context.responseLocale().isBlank()) {
      root.put("responseLocale", context.responseLocale().trim());
    }

    String inline = write(root, mapper);
    if (inline.length() > CHAR_BUDGET) {
      throw new IllegalArgumentException("Recovery context exceeds the diagnostic character budget");
    }
    return inline;
  }

  private static RecoveryEvidence redactedEvidence(RecoveryEvidence evidence) {
    return new RecoveryEvidence(
        evidence.schemaVersion(),
        evidence.failureId(),
        evidence.observedCauseCode(),
        evidence.observingStageId(),
        evidence.producerStageId(),
        evidence.approvedBriefRef(),
        evidence.approvedSemanticRef(),
        evidence.rejectedArtifactRefs(),
        evidence.findings(),
        redactedTechnicalFailure(evidence.technicalFailure()),
        evidence.priorAttemptRefs(),
        evidence.documentRecordRefs());
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
    if (evidence.producerStageId() != null && !evidence.producerStageId().isBlank()) {
      node.put("producerStageId", evidence.producerStageId());
    }
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
    node.set("documentRecordRefs", mapper.valueToTree(evidence.documentRecordRefs()));
    return node;
  }

  private static String write(com.fasterxml.jackson.databind.JsonNode node, ObjectMapper mapper) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Recovery context serialization failed", e);
    }
  }
}

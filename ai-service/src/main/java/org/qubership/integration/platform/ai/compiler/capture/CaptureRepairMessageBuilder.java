package org.qubership.integration.platform.ai.compiler.capture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Builds compact repair user messages from capture failures. */
@ApplicationScoped
public class CaptureRepairMessageBuilder {

  private static final int MAX_MESSAGE_CHARS = 800;
  private static final int MAX_VALIDATION_LINES = 3;
  private static final int MAX_ALLOWED_KEYS = 8;

  private static final Pattern UNKNOWN_PROPERTY_KEY =
      Pattern.compile(
          "node '([^']+)' \\(([^)]+)\\) has unknown property key '([^']+)'",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern MISSING_EDGE =
      Pattern.compile("must have an (outgoing|execution) edge", Pattern.CASE_INSENSITIVE);

  private static final Map<String, String> COMPLETENESS_INSTRUCTIONS = buildCompletenessInstructions();

  private final DeterministicElementSchemaService schemaService;

  @Inject
  public CaptureRepairMessageBuilder(DeterministicElementSchemaService schemaService) {
    this.schemaService = schemaService;
  }

  public String build(CaptureAttemptFeedback feedback, String captureToolName) {
    if (feedback == null) {
      return toolArgumentsMessage(captureToolName);
    }
    return switch (feedback.kind()) {
      case TOOL_ARGUMENTS -> toolArgumentsMessage(captureToolName);
      case CONVERSION -> conversionMessage(feedback.summary(), captureToolName);
      case VALIDATION ->
          validationMessage(feedback.summary(), captureToolName, feedback.fieldHints());
    };
  }

  public String toolArgumentsMessage(String captureToolName) {
    if ("repairScriptBodies".equals(captureToolName)) {
      return truncate(
          "Your last repairScriptBodies call had invalid tool JSON (often unescaped quotes"
              + " inside Groovy). Resubmit one valid capture with targetNodeId + script for every"
              + " listed id. Prefer"
              + " exchange.in.body = groovy.json.JsonOutput.toJson([error: exception?.message])"
              + " — do not embed JSON object literals with double quotes inside the script"
              + " string. Escape every \" as \\\".");
    }
    return truncate(
        "Your last "
            + captureToolName
            + " call had invalid tool JSON. Resubmit one full "
            + captureToolName
            + " object with valid schema. Do not duplicate property entries or embed huge"
            + " repeated blocks.");
  }

  /** One targeted instruction per unmet completeness signal. */
  public List<String> completenessLines(List<String> unmetSignals) {
    if (unmetSignals == null || unmetSignals.isEmpty()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (String signal : unmetSignals) {
      String instruction = COMPLETENESS_INSTRUCTIONS.get(signal);
      if (instruction != null) {
        lines.add(instruction);
      }
    }
    return List.copyOf(lines);
  }

  public String completenessSummary(List<String> unmetSignals) {
    List<String> lines = completenessLines(unmetSignals);
    if (lines.isEmpty()) {
      return "Graph patch is incomplete.";
    }
    return String.join("\n", lines);
  }

  public String requirementBriefEmptyMessage(String captureToolName) {
    return truncate(
        "Requirement brief needs a non-empty goal or summary. Call "
            + captureToolName
            + " again with at least one of those fields populated.");
  }

  public String scriptBodiesRepairMessage(
      List<String> missingNodeIds, CaptureAttemptFeedback feedback) {
    StringBuilder message = new StringBuilder();
    message.append("Repair missing script bodies by calling repairScriptBodies.\n");
    message.append("Submit only scripts for these targetNodeIds: ")
        .append(String.join(", ", missingNodeIds))
        .append(".\n");
    message.append("Include all listed ids in one call. Do not call captureGraphPatch.\n");
    if (feedback != null
        && feedback.kind() == CaptureFailureKind.TOOL_ARGUMENTS) {
      message.append(toolArgumentsMessage("repairScriptBodies")).append('\n');
    } else if (feedback != null
        && feedback.kind() == CaptureFailureKind.VALIDATION
        && feedback.summary() != null
        && !feedback.summary().isBlank()) {
      message.append("Previous attempt: ").append(feedback.summary().trim()).append('\n');
    }
    return truncate(message.toString());
  }

  public String validationResultMessage(String validationError) {
    String detail = validationError != null ? validationError.trim() : "Invalid validation report";
    return truncate(
        detail
            + " Call captureValidationResult again with a non-blank summary and structured issues.");
  }

  private String conversionMessage(String summary, String captureToolName) {
    String detail = summary != null ? summary.trim() : "Invalid property value";
    return truncate(
        detail
            + " Resubmit one complete "
            + captureToolName
            + " with structured JSON property values (not JSON inside strings).");
  }

  private String validationMessage(
      String summary, String captureToolName, List<CaptureFieldHint> fieldHints) {
    StringBuilder message = new StringBuilder();
    if (fieldHints != null && !fieldHints.isEmpty()) {
      for (CaptureFieldHint hint : fieldHints) {
        message
            .append("Set top-level '")
            .append(hint.missingTopPath())
            .append("' to the value already present at '")
            .append(hint.nestedSourcePath())
            .append("' (")
            .append(hint.nestedPreview())
            .append(").\n");
      }
    } else {
      message
          .append("Capture failed validation. Fix and call ")
          .append(captureToolName)
          .append(" again.");
    }
    List<String> lines = validationLines(summary);
    boolean propertyDefect = false;
    boolean missingEdge = false;
    for (String line : lines) {
      message.append("\n- ").append(line);
      // Graph / schema cues only when summary matches unknown-property structure patterns.
      propertyDefect |= appendAllowedKeysHint(message, line);
      missingEdge |= MISSING_EDGE.matcher(line).find();
    }
    if (missingEdge) {
      message.append(
          "\nAdd the missing connection to the graph's edges array as one entry with edgeId,"
              + " fromNodeId, and toNodeId. A parentNodeId nests an element; it does not connect"
              + " the flow.");
    }
    // Only a property defect is worth a property-schema lookup. Sending the generator to the
    // schema tool for an edge or containment defect costs it a repair turn on the wrong question.
    if (propertyDefect) {
      message.append(
          "\nCall describeElementPatchSchema for allowed property keys before adding properties.");
    }
    return truncate(message.toString());
  }

  private static List<String> validationLines(String summary) {
    if (summary == null || summary.isBlank()) {
      return List.of();
    }
    String body = summary.trim();
    if (body.startsWith("Plan validation failed:")) {
      body = body.substring("Plan validation failed:".length()).trim();
    }
    if (body.startsWith("Structure validation failed:")) {
      body = body.substring("Structure validation failed:".length()).trim();
    }
    if (body.startsWith("Invalid graph patch shape:")) {
      body = body.substring("Invalid graph patch shape:".length()).trim();
    }
    if (body.startsWith("Patch apply failed:")) {
      body = body.substring("Patch apply failed:".length()).trim();
    }
    String[] rawLines = body.split("\\r?\\n|; ");
    List<String> lines = new ArrayList<>();
    for (String rawLine : rawLines) {
      String line = rawLine.trim();
      if (!line.isEmpty()) {
        lines.add(line);
      }
      if (lines.size() >= MAX_VALIDATION_LINES) {
        break;
      }
    }
    return lines;
  }

  /** Appends the per-key advice for a property defect, and reports whether the line was one. */
  private boolean appendAllowedKeysHint(StringBuilder message, String validationLine) {
    Matcher matcher = UNKNOWN_PROPERTY_KEY.matcher(validationLine);
    if (!matcher.find()) {
      return false;
    }
    String nodeId = matcher.group(1);
    String elementType = matcher.group(2);
    String key = matcher.group(3);
    message
        .append("\n  Remove property key '")
        .append(key)
        .append("' from node '")
        .append(nodeId)
        .append("'.");
    Set<String> allowedKeys =
        schemaService.allowedPatchPropertyKeys(elementType);
    if (allowedKeys.isEmpty()) {
      return true;
    }
    String sample =
        allowedKeys.stream()
            .sorted()
            .limit(MAX_ALLOWED_KEYS)
            .collect(Collectors.joining(", "));
    message
        .append(" If it is a misspelling, replace it only with a schema-defined key")
        .append(" for ")
        .append(elementType)
        .append(": ")
        .append(sample)
        .append(". Use describeElementPatchSchema('")
        .append(elementType)
        .append("').");
    return true;
  }

  private static String truncate(String message) {
    if (message == null) {
      return "";
    }
    if (message.length() <= MAX_MESSAGE_CHARS) {
      return message;
    }
    return message.substring(0, MAX_MESSAGE_CHARS - 3) + "...";
  }

  private static Map<String, String> buildCompletenessInstructions() {
    Map<String, String> instructions = new LinkedHashMap<>();
    instructions.put(
        "script_nodes_missing_body",
        "Every script node needs a non-empty script body. Fill each script node.");
    instructions.put(
        "rbac_roles_missing",
        "Nodes with accessControlType=RBAC need a non-empty roles array.");
    instructions.put(
        "incomplete_http_trigger_endpoint",
        "http-trigger needs contextPath, httpMethodRestrict, and externalRoute.");
    instructions.put(
        "incomplete_try_catch_nodes",
        "try-catch-finally-2 needs a try-2 child and a catch-2 with exception.");
    instructions.put(
        "incomplete_routing_nodes",
        "Each condition needs an if child; each if needs a non-blank condition.");
    instructions.put(
        "incomplete_service_call_bindings",
        "Catalog identity for each service-call operation branch is server-owned and already"
            + " hydrated. Do not add or modify catalog identity properties; set only"
            + " generator-owned execution properties.");
    return Map.copyOf(instructions);
  }
}

package org.qubership.integration.platform.ai.chain.edit.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.PlannerReportFormatException;

/** Parses the JSON object {@code cip-chain-edit-planner} must return. */
public final class ChainEditStructuralPlanParser {

  private final ObjectMapper objectMapper;

  public ChainEditStructuralPlanParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
  }

  public ChainEditStructuralPlan parse(String raw) {
    JsonNode root = readObject(raw);
    return new ChainEditStructuralPlan(
        enumValue(root, "failureDelivery", FailureDeliveryStrategy.class),
        stringList(root.get("originalTargetNodeIds")),
        stringList(root.get("tryMoveExisting")),
        optionalEnum(root, "catchRole", CatchBranchRole.class, CatchBranchRole.NONE),
        text(root, "catchScriptLabel"),
        stringList(root.get("finallyMoveExisting")),
        text(root, "reporterNodeId"),
        text(root, "reporterSchemaVariant"),
        stringList(root.get("ambiguities")),
        text(root, "clarificationQuestion"),
        stringList(root.get("clarificationChoices")),
        text(root, "rationale"));
  }

  private JsonNode readObject(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new PlannerReportFormatException("cip-chain-edit-planner returned an empty plan");
    }
    try {
      JsonNode root = objectMapper.readTree(raw.trim());
      if (root == null || !root.isObject()) {
        throw new PlannerReportFormatException(
            "cip-chain-edit-planner must return a JSON object");
      }
      return root;
    } catch (PlannerReportFormatException e) {
      throw e;
    } catch (Exception e) {
      throw new PlannerReportFormatException(
          "cip-chain-edit-planner must return a JSON object: " + e.getMessage(), e);
    }
  }

  private static <E extends Enum<E>> E enumValue(JsonNode root, String field, Class<E> type) {
    String raw = text(root, field);
    if (raw == null) {
      throw new PlannerReportFormatException("plan is missing " + field);
    }
    try {
      return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new PlannerReportFormatException("plan has unknown " + field + " '" + raw + "'", e);
    }
  }

  private static <E extends Enum<E>> E optionalEnum(
      JsonNode root, String field, Class<E> type, E fallback) {
    String raw = text(root, field);
    if (raw == null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new PlannerReportFormatException("plan has unknown " + field + " '" + raw + "'", e);
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual() && !node.isNumber() && !node.isBoolean()) {
      throw new PlannerReportFormatException("plan field " + field + " must be a string");
    }
    String value = node.asText();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static List<String> stringList(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new PlannerReportFormatException("plan list fields must be JSON arrays");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      if (item == null || item.isNull()) {
        continue;
      }
      if (!item.isTextual()) {
        throw new PlannerReportFormatException("plan list fields must contain strings");
      }
      String value = item.asText();
      if (value != null && !value.isBlank()) {
        values.add(value.trim());
      }
    }
    return List.copyOf(values);
  }
}

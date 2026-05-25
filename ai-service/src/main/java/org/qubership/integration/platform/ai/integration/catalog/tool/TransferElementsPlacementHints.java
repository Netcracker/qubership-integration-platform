package org.qubership.integration.platform.ai.integration.catalog.tool;

import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

/**
 * Actionable remediation text appended to transferElements placement errors so
 * the LLM can retry
 * correctly.
 */
final class TransferElementsPlacementHints {

  private TransferElementsPlacementHints() {
  }

  static String enrichPlacementError(
      CatalogElementResponseDto parentEl, CatalogElementResponseDto movedEl, String baseReason) {
    StringBuilder sb = new StringBuilder(baseReason);
    sb.append(" How to fix:");
    String parentType = normalizeType(parentEl.type);
    String childType = normalizeType(movedEl.type);

    if ("condition".equalsIgnoreCase(parentType) && !isBranchShellType(childType)) {
      sb.append(
          " Do not pass the condition element id as parentElementId for any workflow step (only"
              + " if/else shells belong there).");
      appendShellChildrenLine(sb, parentEl);
      sb.append(
          " Retry with parentElementId set to the branch shell id (if or else), e.g."
              + " transferElements(chainId, <ifShellId>, elementIdsJson, swimlaneId),"
              + " or createElement(chainId, type, parentElementId=<ifShellId>, swimlaneId)"
              + " instead of creating at root and transferring.");
    } else if (isInboundTrigger(movedEl)) {
      sb.append(" Keep ")
          .append(childType)
          .append(" at chain root (empty parentElementId on create).");
      sb.append(" Wire flow with createConnection(from=")
          .append(movedEl.id)
          .append(", to=<next element id>), not transferElements.");
    } else {
      appendShellChildrenLine(sb, parentEl);
      sb.append(
          " Use one of those shell ids as parentElementId, or call getElement(parentElementId)"
              + " after creating the parent container to discover auto-shelled children.");
    }
    return sb.toString();
  }

  private static void appendShellChildrenLine(
      StringBuilder sb, CatalogElementResponseDto parentEl) {
    if (parentEl.children == null || parentEl.children.isEmpty()) {
      sb.append(
          " Shell ids: call getElement("
              + parentEl.id
              + ") after createElement(condition|split-2|try-catch-finally-2|...)"
              + " — the response lists auto-created shell children.");
      return;
    }
    sb.append(" Shell ids from getElement(").append(parentEl.id).append("):");
    for (CatalogElementResponseDto child : parentEl.children) {
      if (child == null || child.id == null || child.id.isBlank()) {
        continue;
      }
      String type = child.type != null ? child.type.trim() : "element";
      sb.append(" ").append(type).append("=").append(child.id).append(";");
    }
  }

  private static boolean isBranchShellType(String type) {
    if (type == null || type.isBlank()) {
      return false;
    }
    return switch (type.toLowerCase()) {
      case "if",
          "else",
          "main-split-element-2",
          "split-element-2",
          "async-split-element-2",
          "try-2",
          "catch-2",
          "finally-2",
          "circuit-breaker-configuration-2",
          "on-fallback-2" ->
        true;
      default -> false;
    };
  }

  private static boolean isInboundTrigger(CatalogElementResponseDto el) {
    if (el == null || el.type == null) {
      return false;
    }
    return el.type.toLowerCase().endsWith("trigger") || "trigger".equalsIgnoreCase(el.type);
  }

  private static String normalizeType(String type) {
    return type == null ? "" : type.trim();
  }
}

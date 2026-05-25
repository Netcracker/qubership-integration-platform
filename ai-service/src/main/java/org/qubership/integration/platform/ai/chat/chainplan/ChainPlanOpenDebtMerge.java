package org.qubership.integration.platform.ai.chat.chainplan;

import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainConnectionsApplier;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport.StageFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges and reconciles {@link PlanOpenItem} rows on active chain plan
 * snapshots.
 */
public final class ChainPlanOpenDebtMerge {

  private ChainPlanOpenDebtMerge() {
  }

  /**
   * True when non-dismissed service binding debt would block plan approval (Gate
   * 1).
   */
  public static boolean hasBlockingServiceBindingDebt(List<PlanOpenItem> openItems) {
    if (openItems == null || openItems.isEmpty()) {
      return false;
    }
    return openItems.stream()
        .anyMatch(
            o -> !o.dismissedByUser() && o.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED);
  }

  public static boolean hasBlockingServiceBindingDebt(ActiveChainPlanSnapshot active) {
    return active != null && hasBlockingServiceBindingDebt(active.openItems());
  }

  /**
   * True when non-dismissed open debt blocks Gate 1 approval or Gate 2 implement
   * start.
   */
  public static boolean hasBlockingOpenDebt(List<PlanOpenItem> openItems) {
    if (openItems == null || openItems.isEmpty()) {
      return false;
    }
    return openItems.stream()
        .anyMatch(
            o -> !o.dismissedByUser()
                && (o.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED
                    || o.kind() == PlanOpenItemKind.MISSING_RUNTIME_CONNECTIONS));
  }

  public static boolean hasBlockingOpenDebt(ActiveChainPlanSnapshot active) {
    return active != null && hasBlockingOpenDebt(active.openItems());
  }

  /**
   * Multiple root elements imply a multi-step flow; at least one
   * {@code connections[]} edge is
   * required somewhere in the plan (root or nested).
   */
  public static List<PlanOpenItem> collectMissingRuntimeConnectionsItems(
      ChainImplementationPlan plan) {
    if (plan == null || plan.getElements() == null || plan.getElements().size() < 2) {
      return List.of();
    }
    if (!ChainConnectionsApplier.collectAllConnections(plan).isEmpty()) {
      return List.of();
    }
    return List.of(
        new PlanOpenItem(
            PlanOpenItem.idMissingRuntimeConnections(),
            PlanOpenItemKind.MISSING_RUNTIME_CONNECTIONS,
            null,
            null,
            null,
            "Plan has multiple root elements but no connections[] entries. Add runtime dependencies"
                + " (fromClientId and toClientId) at the chain root and/or between siblings under a"
                + " container.",
            List.of(),
            false));
  }

  public static List<PlanOpenItem> mergeDistinctOpenItems(
      List<PlanOpenItem> first, List<PlanOpenItem> second) {
    Map<String, PlanOpenItem> byId = new LinkedHashMap<>();
    for (PlanOpenItem o : first) {
      byId.put(o.itemId(), o);
    }
    for (PlanOpenItem o : second) {
      byId.put(o.itemId(), o);
    }
    return List.copyOf(byId.values());
  }

  public static List<PlanOpenItem> replaceNonDismissedServiceBindingOpenItems(
      List<PlanOpenItem> merged, ChainImplementationPlan plan) {
    List<PlanOpenItem> without = new ArrayList<>();
    for (PlanOpenItem o : merged) {
      if (!o.dismissedByUser() && o.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED) {
        continue;
      }
      without.add(o);
    }
    return mergeDistinctOpenItems(without, collectServiceBindingUnresolvedItems(plan));
  }

  public static List<PlanOpenItem> collectServiceBindingUnresolvedItems(
      ChainImplementationPlan plan) {
    List<PlanOpenItem> out = new ArrayList<>();
    PlanServiceBindingRules.walkForUnresolvedBinding(plan == null ? null : plan.getElements(), out);
    return out;
  }

  public static List<PlanOpenItem> mergeOpenItems(
      List<PlanOpenItem> previous,
      boolean applyUnknownSanitize,
      List<PlanOpenItem> sanitizeUnknownItems,
      CreateElementsByJsonReport report) {
    Map<String, PlanOpenItem> byId = new LinkedHashMap<>();
    for (PlanOpenItem o : previous) {
      if (o.dismissedByUser()) {
        byId.put(o.itemId(), o);
        continue;
      }
      if (applyUnknownSanitize && o.kind() == PlanOpenItemKind.UNKNOWN_PROPERTY_KEY) {
        continue;
      }
      if (report != null
          && (o.kind() == PlanOpenItemKind.PATCH_VALIDATION
              || o.kind() == PlanOpenItemKind.SKIPPED_PROPERTIES_PARTIAL)) {
        continue;
      }
      byId.put(o.itemId(), o);
    }
    if (applyUnknownSanitize && sanitizeUnknownItems != null) {
      for (PlanOpenItem o : sanitizeUnknownItems) {
        byId.put(o.itemId(), o);
      }
    }
    if (report != null) {
      for (PlanOpenItem o : openItemsFromReport(report)) {
        byId.put(o.itemId(), o);
      }
    }
    return List.copyOf(byId.values());
  }

  public static List<PlanOpenItem> openItemsFromReport(CreateElementsByJsonReport report) {
    List<PlanOpenItem> out = new ArrayList<>();
    if (report == null) {
      return out;
    }
    if (report.stages != null
        && report.stages.properties != null
        && report.stages.properties.failures != null) {
      for (StageFailure f : report.stages.properties.failures) {
        if (f == null || f.clientId == null || f.clientId.isBlank()) {
          continue;
        }
        out.add(
            new PlanOpenItem(
                PlanOpenItem.idPatchFailure(f.clientId),
                PlanOpenItemKind.PATCH_VALIDATION,
                f.clientId,
                f.elementId,
                null,
                f.reason != null ? f.reason : "Property PATCH failed",
                List.of(),
                false));
      }
    }
    if (report.skippedPropertyPatches != null) {
      for (CreateElementsByJsonReport.SkippedPropertyPatch sp : report.skippedPropertyPatches) {
        if (sp == null || sp.clientId == null || sp.clientId.isBlank()) {
          continue;
        }
        List<String> keys = sp.removedKeys == null ? List.of() : List.copyOf(sp.removedKeys);
        List<String> sortedKeys = new ArrayList<>(keys);
        sortedKeys.sort(String::compareTo);
        out.add(
            new PlanOpenItem(
                PlanOpenItem.idSkippedPartial(sp.clientId, sortedKeys),
                PlanOpenItemKind.SKIPPED_PROPERTIES_PARTIAL,
                sp.clientId,
                sp.elementId,
                sp.elementType,
                "Skipped property keys were not sent in PATCH — verify values in the UI.",
                sortedKeys,
                false));
      }
    }
    return out;
  }

  public static String fingerprintNonDismissedOpenItems(List<PlanOpenItem> items) {
    if (items == null || items.isEmpty()) {
      return "";
    }
    return items.stream()
        .filter(o -> !o.dismissedByUser())
        .map(PlanOpenItem::itemId)
        .sorted()
        .collect(java.util.stream.Collectors.joining("|"));
  }
}

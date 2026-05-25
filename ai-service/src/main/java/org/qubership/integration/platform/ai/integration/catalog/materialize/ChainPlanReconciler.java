package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.CatalogDependencyKeys;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ChainPlanReconciler {

  private static final Logger LOG = Logger.getLogger(ChainPlanReconciler.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainPlanReconciler(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  record ReconcileOutcome(
      int catalogElements,
      int planCatalogIds,
      List<String> missingPlanCatalogIds,
      List<String> orphanRootTypes,
      List<String> missingDependencyPairs,
      List<String> parentMismatches) {}

  /**
   * Package-private for {@link ChainPlanReconcilerTest}: pure reconcile diagnostics without REST
   * calls.
   */
  static ReconcileOutcome buildOutcome(
      ChainImplementationPlan plan,
      CreateElementsByJsonReport report,
      Map<String, CatalogElementResponseDto> byId,
      List<CatalogDependencyDto> deps) {
    Set<String> planCatalogIds = new HashSet<>(report.clientIds.values());
    List<String> missingCatalogIds = new ArrayList<>();
    visitPlanForMissingIds(plan != null ? plan.getElements() : null, missingCatalogIds);

    Set<String> depKeys = new HashSet<>(CatalogDependencyKeys.edgeKeysFromDependencies(deps));

    List<String> orphanIds = new ArrayList<>();
    for (Map.Entry<String, CatalogElementResponseDto> e : byId.entrySet()) {
      String id = e.getKey();
      if (!planCatalogIds.contains(id)) {
        CatalogElementResponseDto el = e.getValue();
        String pid = CatalogStrings.blankToNull(el != null ? el.parentElementId : null);
        if (pid == null && el != null && CatalogStrings.blankToNull(el.type) != null) {
          orphanIds.add(id + ":" + el.type);
        }
      }
    }

    List<String> parentMismatches = new ArrayList<>();
    visitPlanForParentMismatch(plan != null ? plan.getElements() : null, byId, parentMismatches);

    List<String> missingEdges = new ArrayList<>();
    for (ConnectionPlan cp : ChainConnectionsApplier.collectAllConnections(plan)) {
      if (cp == null) {
        continue;
      }
      String fromC = CatalogStrings.blankToNull(cp.getFromClientId());
      String toC = CatalogStrings.blankToNull(cp.getToClientId());
      if (fromC == null || toC == null) {
        continue;
      }
      String fromId = report.clientIds.get(fromC);
      String toId = report.clientIds.get(toC);
      if (fromId == null || toId == null) {
        continue;
      }
      if (!depKeys.contains(CatalogDependencyKeys.edgeKey(fromId, toId))) {
        missingEdges.add(fromC + "->" + toC);
      }
    }

    return new ReconcileOutcome(
        byId.size(),
        planCatalogIds.size(),
        missingCatalogIds,
        orphanIds,
        missingEdges,
        parentMismatches);
  }

  public void reconcileAndLog(
      String chainId, ChainImplementationPlan plan, CreateElementsByJsonReport report) {
    Map<String, CatalogElementResponseDto> byId = new HashMap<>();
    List<CatalogDependencyDto> deps = List.of();
    try {
      List<CatalogElementResponseDto> roots = catalogRestClient.listElements(chainId);
      flattenElements(roots, byId);
    } catch (Exception e) {
      LOG.warnf(e, "plan reconcile: listElements failed chainId=%s", chainId);
      return;
    }
    try {
      List<CatalogDependencyDto> listed = catalogRestClient.listDependencies(chainId);
      deps = listed != null ? listed : List.of();
    } catch (Exception e) {
      LOG.warnf(e, "plan reconcile: listDependencies failed chainId=%s", chainId);
    }

    ReconcileOutcome outcome = buildOutcome(plan, report, byId, deps);

    LOG.infof(
        "plan reconcile: chainId=%s catalogElements=%d planCatalogIds=%d missingPlanCatalogIds=%s"
            + " orphanRootTypes=%s missingDependencyPairs=%s parentMismatches=%s",
        chainId,
        outcome.catalogElements(),
        outcome.planCatalogIds(),
        outcome.missingPlanCatalogIds().isEmpty()
            ? "[]"
            : outcome.missingPlanCatalogIds().toString(),
        outcome.orphanRootTypes().isEmpty()
            ? "[]"
            : AiTraceLog.previewOneLine(outcome.orphanRootTypes().toString(), 800),
        outcome.missingDependencyPairs().isEmpty()
            ? "[]"
            : outcome.missingDependencyPairs().toString(),
        outcome.parentMismatches().isEmpty()
            ? "[]"
            : AiTraceLog.previewOneLine(outcome.parentMismatches().toString(), 1200));

    for (String pm : outcome.parentMismatches()) {
      LOG.warnf("plan reconcile parent mismatch: chainId=%s detail=%s", chainId, pm);
    }
  }

  private static void visitPlanForMissingIds(List<ElementPlan> roots, List<String> out) {
    PlanTreeUtils.preOrder(
        roots,
        node -> {
          String c = CatalogStrings.blankToNull(node.getClientId());
          if (c != null && CatalogStrings.blankToNull(node.getElementId()) == null) {
            out.add(c);
          }
        });
  }

  private static void visitPlanForParentMismatch(
      List<ElementPlan> roots, Map<String, CatalogElementResponseDto> byId, List<String> out) {
    PlanTreeUtils.visitWithRootFlag(
        roots,
        (node, rootFlag) -> {
          boolean isRoot = Boolean.TRUE.equals(rootFlag);
          String cid = CatalogStrings.blankToNull(node.getClientId());
          String catId = CatalogStrings.blankToNull(node.getElementId());
          if (catId != null) {
            CatalogElementResponseDto live = byId.get(catId);
            String expectedParent =
                isRoot ? null : CatalogStrings.blankToNull(node.getParentElementId());
            String catalogParent =
                live != null ? CatalogStrings.blankToNull(live.parentElementId) : null;
            // listElements often returns a flat top-level array: nested elements may omit
            // parentElementId.
            // Only treat as mismatch when the catalog reported a parent and it disagrees with the
            // plan.
            boolean canVerifyParent = catalogParent != null || expectedParent == null;
            if (canVerifyParent && !java.util.Objects.equals(expectedParent, catalogParent)) {
              out.add(
                  "clientId="
                      + cid
                      + " elementId="
                      + catId
                      + " expectedParent="
                      + expectedParent
                      + " catalogParent="
                      + catalogParent);
            }
          }
        });
  }

  private static void flattenElements(
      List<CatalogElementResponseDto> roots, Map<String, CatalogElementResponseDto> byId) {
    if (roots == null) {
      return;
    }
    for (CatalogElementResponseDto n : roots) {
      flattenVisit(n, byId);
    }
  }

  private static void flattenVisit(
      CatalogElementResponseDto node, Map<String, CatalogElementResponseDto> byId) {
    if (node == null) {
      return;
    }
    String id = CatalogStrings.blankToNull(node.id);
    if (id != null) {
      CatalogElementResponseDto prev = byId.get(id);
      if (prev == null) {
        byId.put(id, node);
      } else {
        String prevP = CatalogStrings.blankToNull(prev.parentElementId);
        String newP = CatalogStrings.blankToNull(node.parentElementId);
        if (prevP == null && newP != null) {
          byId.put(id, node);
        } else if (prevP != null && newP == null) {
          // keep prev (has parent from nested tree)
        } else {
          byId.put(id, node);
        }
      }
    }
    if (node.children != null) {
      for (CatalogElementResponseDto c : node.children) {
        flattenVisit(c, byId);
      }
    }
  }
}

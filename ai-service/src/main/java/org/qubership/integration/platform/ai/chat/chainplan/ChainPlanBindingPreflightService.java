package org.qubership.integration.platform.ai.chat.chainplan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingEnrichResult;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingResolver;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enriches operation bindings on a plan and collects unresolved binding open items. */
@ApplicationScoped
public class ChainPlanBindingPreflightService {

  private final CatalogOperationBindingResolver operationBindingResolver;

  @Inject
  public ChainPlanBindingPreflightService(CatalogOperationBindingResolver operationBindingResolver) {
    this.operationBindingResolver = operationBindingResolver;
  }

  public record PreflightResult(List<PlanOpenItem> openItems) {}

  /**
   * Walks the plan tree, enriches bound elements from catalog when possible, and returns open
   * items for unresolved bindings.
   */
  public PreflightResult enrichOperationBindings(ChainImplementationPlan plan) {
    List<PlanOpenItem> openItems = new ArrayList<>();
    if (plan == null || plan.getElements() == null) {
      return new PreflightResult(openItems);
    }
    PlanTreeUtils.preOrder(plan.getElements(), node -> enrichNode(node, openItems));
    return new PreflightResult(openItems);
  }

  private void enrichNode(ElementPlan node, List<PlanOpenItem> openItems) {
    if (node == null || !PlanServiceBindingRules.requiresOperationBinding(node)) {
      return;
    }
    if (isUserAcceptedUnbound(node.getBindingStatus())) {
      return;
    }
    if (Boolean.TRUE.equals(node.getImportRequired())) {
      return;
    }
    if (ChainPlanImportPathNormalizer.misplacedApiHubOperationId(node) != null) {
      return;
    }
    if (PlanServiceBindingRules.hasCompleteApiHubImportMetadata(node)) {
      return;
    }
    CatalogOperationBindingEnrichResult result = operationBindingResolver.enrichForProperties(node);
    String unresolvedReason = result.unresolvedReason().orElse(null);
    if (unresolvedReason != null) {
      openItems.add(unresolvedBindingOpenItem(node, unresolvedReason));
      return;
    }
    applyEnrichedProperties(node, result.properties());
  }

  private static PlanOpenItem unresolvedBindingOpenItem(ElementPlan node, String reason) {
    String clientId = CatalogStrings.blankToNull(node.getClientId());
    String elementType = node.getType() != null ? node.getType().trim() : "";
    return new PlanOpenItem(
        PlanOpenItem.idServiceBindingUnresolved(clientId != null ? clientId : ""),
        PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED,
        clientId,
        node.getElementId(),
        elementType,
        reason,
        List.of(),
        false);
  }

  private static void applyEnrichedProperties(ElementPlan node, Map<String, Object> enriched) {
    if (enriched != null && !enriched.isEmpty()) {
      node.setExpectedProperties(new LinkedHashMap<>(enriched));
    }
  }

  public boolean hasUnresolvedBindings(ChainImplementationPlan plan) {
    return !enrichOperationBindings(plan).openItems().isEmpty();
  }

  private static boolean isUserAcceptedUnbound(String bindingStatus) {
    return bindingStatus != null && "user_accepted_unbound".equalsIgnoreCase(bindingStatus.trim());
  }
}

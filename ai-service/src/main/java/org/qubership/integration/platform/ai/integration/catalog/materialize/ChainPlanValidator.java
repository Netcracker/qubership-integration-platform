package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogElementPlacementRules;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Validates {@link ChainImplementationPlan} tree shape and element placement rules before catalog
 * HTTP calls.
 */
@ApplicationScoped
public class ChainPlanValidator {

  private static final String ROOT_PARENT_ARG = "";

  private final CatalogElementPlacementRules catalogElementPlacementRules;

  @Inject
  public ChainPlanValidator(CatalogElementPlacementRules catalogElementPlacementRules) {
    this.catalogElementPlacementRules = catalogElementPlacementRules;
  }

  /**
   * @return empty when the plan passes validation; otherwise a single human-readable error reason
   */
  public Optional<String> validate(String chainId, ChainImplementationPlan plan) {
    if (plan == null) {
      return Optional.of("plan is null");
    }
    Set<String> globalClientIds = new HashSet<>();
    if (plan.getElements() == null) {
      return Optional.empty();
    }
    for (int i = 0; i < plan.getElements().size(); i++) {
      Optional<String> err =
          validateSubtree(
              chainId, plan.getElements().get(i), globalClientIds, true, "elements[" + i + "]");
      if (err.isPresent()) {
        return err;
      }
    }
    return Optional.empty();
  }

  private Optional<String> validateSubtree(
      String chainId,
      ElementPlan node,
      Set<String> globalClientIds,
      boolean isPlanRoot,
      String path) {
    if (node == null) {
      return Optional.of(path + " must not be null");
    }
    String clientId = CatalogStrings.blankToNull(node.getClientId());
    if (clientId == null) {
      return Optional.of(path + ".clientId is required");
    }
    if (!globalClientIds.add(clientId)) {
      return Optional.of("duplicate clientId in plan: " + clientId);
    }
    String type = CatalogStrings.blankToNull(node.getType());
    if (type == null) {
      return Optional.of(path + ".type is required (clientId=" + clientId + ")");
    }
    String placementParent =
        isPlanRoot ? ROOT_PARENT_ARG : CatalogStrings.blankToNull(node.getParentElementId());
    Optional<String> placementErr = validatePlacement(chainId, type, isPlanRoot, placementParent);
    if (placementErr.isPresent()) {
      return placementErr;
    }
    if (node.getChildren() != null) {
      for (int j = 0; j < node.getChildren().size(); j++) {
        Optional<String> err =
            validateSubtree(
                chainId,
                node.getChildren().get(j),
                globalClientIds,
                false,
                path + ".children[" + j + "]");
        if (err.isPresent()) {
          return err;
        }
      }
    }
    return Optional.empty();
  }

  private Optional<String> validatePlacement(
      String chainId, String type, boolean isPlanRoot, String placementParent) {
    if (isPlanRoot) {
      return catalogElementPlacementRules.validateCreatePlacement(chainId, type, ROOT_PARENT_ARG);
    }
    if (placementParent != null) {
      return catalogElementPlacementRules.validateCreatePlacement(chainId, type, placementParent);
    }
    return Optional.empty();
  }
}

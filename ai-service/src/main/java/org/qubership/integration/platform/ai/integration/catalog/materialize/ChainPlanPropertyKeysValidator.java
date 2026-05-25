package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItem;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItemKind;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strips unknown {@code expectedProperties} keys using embedded JSON Schema allow-lists and records
 * {@link PlanOpenItemKind#UNKNOWN_PROPERTY_KEY} rows for the active plan snapshot.
 */
@ApplicationScoped
public class ChainPlanPropertyKeysValidator {

  private final DeterministicElementSchemaService deterministicElementSchemaService;

  @Inject
  public ChainPlanPropertyKeysValidator(
      DeterministicElementSchemaService deterministicElementSchemaService) {
    this.deterministicElementSchemaService = deterministicElementSchemaService;
  }

  /**
   * Mutates {@code plan} in place: removes property keys not present in {@link
   * DeterministicElementSchemaService#allowedPatchPropertyKeys(String)} when the schema exists.
   *
   * @return new open items describing removed keys (not merged with existing snapshot items)
   */
  public List<PlanOpenItem> sanitizeAndCollectUnknownKeys(ChainImplementationPlan plan) {
    List<PlanOpenItem> out = new ArrayList<>();
    if (plan == null || plan.getElements() == null) {
      return out;
    }
    PlanTreeUtils.preOrder(plan.getElements(), node -> collectForNode(node, out));
    return out;
  }

  private void collectForNode(ElementPlan node, List<PlanOpenItem> out) {
    if (node == null) {
      return;
    }
    String clientId = CatalogStrings.blankToNull(node.getClientId());
    String elementType = CatalogStrings.blankToNull(node.getType());
    Map<String, Object> props = node.getExpectedProperties();
    if (clientId == null || props == null || props.isEmpty()) {
      return;
    }
    if (elementType == null) {
      return;
    }
    Set<String> allowed = deterministicElementSchemaService.allowedPatchPropertyKeys(elementType);
    if (allowed.isEmpty()) {
      return;
    }
    List<String> removed = new ArrayList<>();
    for (Iterator<Map.Entry<String, Object>> it = props.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, Object> e = it.next();
      String key = e.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      if (!allowed.contains(key)) {
        removed.add(key);
        it.remove();
      }
    }
    if (removed.isEmpty()) {
      return;
    }
    String elementId = CatalogStrings.blankToNull(node.getElementId());
    for (String key : removed) {
      out.add(
          new PlanOpenItem(
              PlanOpenItem.idUnknownProperty(clientId, key),
              PlanOpenItemKind.UNKNOWN_PROPERTY_KEY,
              clientId,
              elementId,
              elementType,
              "Unknown or disallowed property key \""
                  + key
                  + "\" for element type "
                  + elementType
                  + " — removed from plan; verify or map to a supported key in the UI.",
              List.of(key),
              false));
    }
  }
}

package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingEnrichResult;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingResolver;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport.SkippedPropertyPatch;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ChainPropertiesApplier {

  private static final Logger LOG = Logger.getLogger(ChainPropertiesApplier.class);

  private final ObjectMapper objectMapper;
  private final CatalogPatchPreparationService catalogPatchPreparationService;
  private final CatalogRestClient catalogRestClient;
  private final DeterministicElementSchemaService deterministicElementSchemaService;
  private final CatalogOperationBindingResolver operationBindingResolver;

  @Inject
  public ChainPropertiesApplier(
      ObjectMapper objectMapper,
      CatalogPatchPreparationService catalogPatchPreparationService,
      @RestClient CatalogRestClient catalogRestClient,
      DeterministicElementSchemaService deterministicElementSchemaService,
      CatalogOperationBindingResolver operationBindingResolver) {
    this.objectMapper = objectMapper;
    this.catalogPatchPreparationService = catalogPatchPreparationService;
    this.catalogRestClient = catalogRestClient;
    this.deterministicElementSchemaService = deterministicElementSchemaService;
    this.operationBindingResolver = operationBindingResolver;
  }

  public void applyPropertiesStage(
      String chainId, ChainImplementationPlan plan, CreateElementsByJsonReport report) {
    List<CreateElementsByJsonReport.StageFailure> failures = new ArrayList<>();
    if (plan.getElements() == null || plan.getElements().isEmpty()) {
      report.stages.properties = ReportFactories.finalizeStage(failures);
      return;
    }
    PlanTreeUtils.postOrder(
        plan.getElements(),
        node -> {
          String clientId = CatalogStrings.blankToNull(node.getClientId());
          String catalogElementId = CatalogStrings.blankToNull(node.getElementId());
          if (catalogElementId == null || clientId == null) {
            return;
          }
          if (!hasNonEmptyProperties(node.getExpectedProperties())) {
            LOG.infof(
                "stage properties: chainId=%s clientId=%s elementId=%s"
                    + " skipped=empty_expected_properties",
                chainId, clientId, catalogElementId);
            return;
          }
          String elementType = CatalogStrings.blankToNull(node.getType());
          CatalogOperationBindingEnrichResult enrichResult = enrichBindingIfApplicable(node);
          if (enrichResult.unresolvedReason().isPresent()) {
            failures.add(
                ReportFactories.property(
                    clientId, catalogElementId, enrichResult.unresolvedReason().get()));
            LOG.warnf(
                "stage properties: chainId=%s clientId=%s elementId=%s result=unresolved_binding",
                chainId, clientId, catalogElementId);
            return;
          }
          Map<String, Object> propsToPatch = enrichResult.properties();
          LOG.infof(
              "stage properties: chainId=%s clientId=%s elementId=%s overlayKeys=%s",
              chainId, clientId, catalogElementId, propsToPatch.keySet());
          if (elementType != null) {
            Set<String> allowed =
                deterministicElementSchemaService.allowedPatchPropertyKeys(elementType);
            if (!allowed.isEmpty()) {
              List<String> removed = new ArrayList<>();
              Map<String, Object> known = new LinkedHashMap<>();
              for (Map.Entry<String, Object> e : propsToPatch.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isBlank()) {
                  continue;
                }
                if (allowed.contains(key)) {
                  known.put(key, e.getValue());
                } else {
                  removed.add(key);
                }
              }
              if (!removed.isEmpty()) {
                SkippedPropertyPatch sp = new SkippedPropertyPatch();
                sp.clientId = clientId;
                sp.elementId = catalogElementId;
                sp.elementType = elementType;
                sp.removedKeys = new ArrayList<>(removed);
                sp.removedKeys.sort(String::compareTo);
                report.skippedPropertyPatches.add(sp);
                LOG.infof(
                    "stage properties: chainId=%s clientId=%s elementId=%s skipped_unknown_keys=%s",
                    chainId, clientId, catalogElementId, sp.removedKeys);
              }
              if (known.isEmpty()) {
                LOG.infof(
                    "stage properties: chainId=%s clientId=%s elementId=%s"
                        + " skipped=all_keys_unknown",
                    chainId, clientId, catalogElementId);
                return;
              }
              propsToPatch = known;
            }
          }
          try {
            String patchJson = objectMapper.writeValueAsString(Map.of("properties", propsToPatch));
            CatalogPatchPreparationService.PreparedUpdateBody prepared =
                catalogPatchPreparationService.prepareUpdateElementBody(
                    chainId, catalogElementId, patchJson);
            catalogRestClient.updateElement(chainId, catalogElementId, prepared.body());
            LOG.infof(
                "stage properties: chainId=%s clientId=%s elementId=%s result=ok merged=%s",
                chainId, clientId, catalogElementId, prepared.mergedWithCatalog());
          } catch (IllegalArgumentException e) {
            failures.add(ReportFactories.property(clientId, catalogElementId, e.getMessage()));
            LOG.warnf(
                e,
                "stage properties: chainId=%s clientId=%s elementId=%s result=failed",
                chainId,
                clientId,
                catalogElementId);
          } catch (Exception e) {
            failures.add(
                ReportFactories.property(
                    clientId,
                    catalogElementId,
                    CatalogRestSupport.describeExceptionForToolResult(e)));
            LOG.warnf(
                e,
                "stage properties: chainId=%s clientId=%s elementId=%s result=failed",
                chainId,
                clientId,
                catalogElementId);
          }
        });
    report.stages.properties = ReportFactories.finalizeStage(failures);
  }

  private CatalogOperationBindingEnrichResult enrichBindingIfApplicable(ElementPlan node) {
    return operationBindingResolver.enrichForProperties(node);
  }

  private static boolean hasNonEmptyProperties(Map<String, Object> props) {
    return props != null && !props.isEmpty();
  }
}

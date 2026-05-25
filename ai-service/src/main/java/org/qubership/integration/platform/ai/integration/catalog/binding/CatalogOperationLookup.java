package org.qubership.integration.platform.ai.integration.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogIdPatterns;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves catalog operations and system metadata for operation binding enrich (UI
 * SystemOperationField).
 */
@ApplicationScoped
public class CatalogOperationLookup {

  private static final Logger LOG = Logger.getLogger(CatalogOperationLookup.class);

  private final ConversationCatalogCache cache;
  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogOperationLookup(
      ConversationCatalogCache cache, @RestClient CatalogRestClient catalogRestClient) {
    this.cache = cache;
    this.catalogRestClient = catalogRestClient;
  }

  public Optional<OperationBindingResolved> resolve(
      String conversationId, String operationId, String specIdFromPlan, String systemIdFromPlan) {
    String opId = CatalogStrings.blankToNull(operationId);
    if (opId == null) {
      return Optional.empty();
    }

    Optional<CatalogRestClient.OperationDto> operation =
        findOperation(conversationId, opId);
    if (operation.isEmpty()) {
      return Optional.empty();
    }

    String modelId = resolveModelId(operation.get(), specIdFromPlan);
    ensureSpecificationContext(conversationId, modelId);
    String specificationGroupId = resolveSpecificationGroupId(conversationId, modelId);
    String resolvedSystemId = resolveSystemId(conversationId, modelId, systemIdFromPlan);
    ensureSystemInCache(conversationId, resolvedSystemId);
    String protocol = resolveProtocol(conversationId, resolvedSystemId);
    Optional<CatalogRestClient.SystemDto> system =
        resolvedSystemId != null
            ? cache.findSystem(conversationId, resolvedSystemId)
            : Optional.empty();

    OperationBindingResolved resolved =
        new OperationBindingResolved(
            operation.get(), resolvedSystemId, specificationGroupId, protocol, system);
    return Optional.ofNullable(resolved);
  }

  private Optional<CatalogRestClient.OperationDto> findOperation(
      String conversationId, String operationId) {
    Optional<CatalogRestClient.OperationDto> operation =
        cache.findOperation(conversationId, operationId);
    if (operation.isPresent()) {
      return operation;
    }
    return loadOperationById(conversationId, operationId);
  }

  private Optional<CatalogRestClient.OperationDto> loadOperationById(
      String conversationId, String operationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    try {
      CatalogRestClient.OperationDto loaded = catalogRestClient.getOperation(operationId);
      if (loaded != null && loaded.id() != null && !loaded.id().isBlank()) {
        cache.rememberOperation(conversationId, loaded);
        return Optional.ofNullable(loaded);
      }
    } catch (Exception e) {
      LOG.debugf(e, "Failed to load catalog operation %s by id", operationId);
    }
    return Optional.empty();
  }

  private static String resolveModelId(
      CatalogRestClient.OperationDto operation, String specIdFromPlan) {
    String fromOperation = CatalogStrings.blankToNull(operation.modelId());
    if (fromOperation != null) {
      return fromOperation;
    }
    return CatalogStrings.blankToNull(specIdFromPlan);
  }

  private void ensureSpecificationContext(String conversationId, String modelId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (conversationId == null || conversationId.isBlank() || mid == null) {
      return;
    }
    if (cache.findSpecification(conversationId, mid).isPresent()) {
      return;
    }
    try {
      CatalogRestClient.SpecificationDto model = catalogRestClient.getModel(mid);
      if (model != null) {
        cache.rememberSpecifications(conversationId, List.of(model));
      }
    } catch (Exception e) {
      LOG.debugf(e, "Failed to load catalog model %s for binding context", mid);
    }
  }

  private String resolveSpecificationGroupId(String conversationId, String modelId) {
    return cache
        .findSpecification(conversationId, modelId)
        .map(CatalogRestClient.SpecificationDto::specificationGroupId)
        .map(CatalogStrings::blankToNull)
        .orElse(null);
  }

  void ensureSystemInCache(String conversationId, String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (conversationId == null
        || conversationId.isBlank()
        || sid == null
        || cache.findSystem(conversationId, sid).isPresent()) {
      return;
    }
    if (!CatalogIdPatterns.isUuidLike(sid)) {
      return;
    }
    try {
      CatalogRestClient.SystemDto dto = catalogRestClient.getSystem(sid);
      if (dto != null && dto.id() != null && !dto.id().isBlank()) {
        cache.rememberSystems(conversationId, List.of(dto));
      }
    } catch (Exception e) {
      LOG.debugf(e, "Failed to load catalog system %s for binding enrich", sid);
    }
  }

  private String resolveSystemId(
      String conversationId, String modelId, String systemIdFromPlan) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (mid != null) {
      Optional<String> specOwner = cache.findSpecificationOwnerSystemId(conversationId, mid);
      if (specOwner.isPresent()) {
        String owner = specOwner.get();
        ensureSystemInCache(conversationId, owner);
        return owner;
      }
    }
    String planSystemId = CatalogStrings.blankToNull(systemIdFromPlan);
    ensureSystemInCache(conversationId, planSystemId);
    if (planSystemId != null && cache.findSystem(conversationId, planSystemId).isPresent()) {
      return planSystemId;
    }
    String active = cache.activeSystemId(conversationId);
    if (active != null) {
      ensureSystemInCache(conversationId, active);
      if (cache.findSystem(conversationId, active).isPresent()) {
        return active;
      }
    }
    return planSystemId;
  }

  private String resolveProtocol(String conversationId, String systemId) {
    if (systemId != null) {
      ensureSystemInCache(conversationId, systemId);
      Optional<CatalogRestClient.SystemDto> system = cache.findSystem(conversationId, systemId);
      if (system.isPresent()) {
        String protocol = CatalogStrings.blankToNull(system.get().protocol());
        if (protocol != null) {
          return protocol.toLowerCase(Locale.ROOT);
        }
      }
    }
    return "http";
  }
}

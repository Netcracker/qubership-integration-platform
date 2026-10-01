package org.qubership.integration.platform.ai.integration.catalog.cache;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogLookupArgsValidator;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogSystemToolNames;

import java.util.List;
import java.util.Optional;

/**
 * Loads and caches catalog operations per conversation (paginated HTTP GET per
 * modelId).
 */
@ApplicationScoped
public class CatalogOperationsLookupService {

  private final ConversationCatalogCache cache;

  @Inject
  public CatalogOperationsLookupService(ConversationCatalogCache cache) {
    this.cache = cache;
  }

  public List<CatalogRestClient.OperationDto> listOperations(String modelId, String systemId) {
    return listOperations(conversationIdFromMdc(), modelId, systemId);
  }

  /**
   * Lists operations for an explicit conversation identity across worker hops.
   */
  public List<CatalogRestClient.OperationDto> listOperations(
      String conversationId, String modelId, String systemId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (mid == null) {
      return List.of();
    }
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid == null) {
      sid = cache.activeSystemId(conversationId);
    }
    return cache.getOrLoadOperations(conversationId, mid, sid);
  }

  public List<CatalogRestClient.OperationDto> findOperations(
      String modelId, String systemId, String searchFilter) {
    return findOperations(conversationIdFromMdc(), modelId, systemId, searchFilter);
  }

  public List<CatalogRestClient.OperationDto> findOperations(
      String conversationId, String modelId, String systemId, String searchFilter) {
    List<CatalogRestClient.OperationDto> all = listOperations(conversationId, modelId, systemId);
    return cache.filterOperations(all, searchFilter);
  }

  public void rememberSystems(List<CatalogRestClient.SystemDto> systems) {
    rememberSystems(conversationIdFromMdc(), systems);
  }

  public void rememberSystems(String conversationId, List<CatalogRestClient.SystemDto> systems) {
    cache.rememberSystems(conversationId, systems);
  }

  public void rememberActiveSystemId(String systemId) {
    rememberActiveSystemId(conversationIdFromMdc(), systemId);
  }

  public void rememberActiveSystemId(String conversationId, String systemId) {
    cache.rememberActiveSystemId(conversationId, systemId);
  }

  public void rememberSpecificationsForSystem(
      String systemId, List<CatalogRestClient.SpecificationDto> specifications) {
    rememberSpecificationsForSystem(conversationIdFromMdc(), systemId, specifications);
  }

  public void rememberSpecificationsForSystem(String conversationId,
      String systemId, List<CatalogRestClient.SpecificationDto> specifications) {
    cache.rememberSpecificationsForSystem(conversationId, systemId, specifications);
  }

  public Optional<CatalogToolResult.ErrorSpec> validateSystemIdForSpecifications(String systemId) {
    return CatalogLookupArgsValidator.validateSystemIdForSpecifications(
        systemId, cache, conversationIdFromMdc());
  }

  public Optional<CatalogToolResult.ErrorSpec> validateSpecificationIdForOperations(
      String specificationId) {
    return validateSpecificationIdForOperations(conversationIdFromMdc(), specificationId);
  }

  public Optional<CatalogToolResult.ErrorSpec> validateSpecificationIdForOperations(
      String conversationId, String specificationId) {
    return CatalogLookupArgsValidator.validateSpecificationIdForOperations(
        CatalogSystemToolNames.OPS, specificationId, cache, conversationId);
  }

  public Optional<CatalogRestClient.OperationDto> findRememberedOperation(String operationId) {
    return findRememberedOperation(conversationIdFromMdc(), operationId);
  }

  public Optional<CatalogRestClient.OperationDto> findRememberedOperation(
      String conversationId, String operationId) {
    return cache.findOperation(conversationId, operationId);
  }

  private static String conversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }
}

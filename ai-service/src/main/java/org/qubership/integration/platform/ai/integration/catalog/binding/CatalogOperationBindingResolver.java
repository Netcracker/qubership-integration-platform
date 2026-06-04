package org.qubership.integration.platform.ai.integration.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.chainplan.PlanServiceBindingRules;
import org.qubership.integration.platform.ai.integration.catalog.binding.AsyncApiTriggerBindingApplicator;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.binding.HttpTriggerBindingApplicator;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingApplicator;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingKeys;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingProps;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingUnresolved;
import org.qubership.integration.platform.ai.integration.catalog.binding.ServiceCallBindingApplicator;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enriches service-bound element {@code expectedProperties} from catalog (same
 * fields as UI
 * SystemOperationField). Delegates per element type to
 * {@link OperationBindingApplicator}.
 */
@ApplicationScoped
public class CatalogOperationBindingResolver {

  private final CatalogOperationLookup operationLookup;
  private final List<OperationBindingApplicator> applicators;

  @Inject
  public CatalogOperationBindingResolver(
      CatalogOperationLookup operationLookup, Instance<OperationBindingApplicator> applicators) {
    this.operationLookup = operationLookup;
    this.applicators = new ArrayList<>();
    applicators.forEach(this.applicators::add);
  }

  /** Test / manual wiring without CDI {@link Instance}. */
  public CatalogOperationBindingResolver(
      CatalogOperationLookup operationLookup, List<OperationBindingApplicator> applicators) {
    this.operationLookup = operationLookup;
    this.applicators = List.copyOf(applicators);
  }

  public CatalogOperationBindingResolver(
      ConversationCatalogCache cache, @RestClient CatalogRestClient catalogRestClient) {
    this(new CatalogOperationLookup(cache, catalogRestClient), defaultBindingApplicators());
  }

  private static List<OperationBindingApplicator> defaultBindingApplicators() {
    List<OperationBindingApplicator> applicators = new ArrayList<>(3);
    applicators.add(new ServiceCallBindingApplicator());
    applicators.add(new HttpTriggerBindingApplicator());
    applicators.add(new AsyncApiTriggerBindingApplicator());
    return applicators;
  }

  /**
   * Enriches operation binding fields from catalog when possible; otherwise
   * returns an explicit
   * unresolved reason for materialize reporting (no silent pass-through to schema
   * validation).
   */
  public CatalogOperationBindingEnrichResult enrichForProperties(ElementPlan node) {
    if (node == null || node.getExpectedProperties() == null) {
      return CatalogOperationBindingEnrichResult.unchanged(Map.of());
    }

    Optional<OperationBindingApplicator> applicator = findApplicator(node);
    if (applicator.isEmpty() || !applicator.get().shouldEnrich(node)) {
      return CatalogOperationBindingEnrichResult.unchanged(node.getExpectedProperties());
    }

    if (shouldSkipEnrichForUnbound(node)) {
      return CatalogOperationBindingEnrichResult.unchanged(node.getExpectedProperties());
    }

    Map<String, Object> props = new LinkedHashMap<>(node.getExpectedProperties());
    String operationId =
        OperationBindingProps.stringProp(props, OperationBindingKeys.INTEGRATION_OPERATION_ID);
    if (operationId == null) {
      return CatalogOperationBindingEnrichResult.unchanged(node.getExpectedProperties());
    }
    if (PlanServiceBindingRules.looksLikeApiHubOperationId(operationId)) {
      return CatalogOperationBindingEnrichResult.unchanged(node.getExpectedProperties());
    }

    String conversationId = conversationIdFromMdc();
    String specId =
        OperationBindingProps.stringProp(props, OperationBindingKeys.INTEGRATION_SPECIFICATION_ID);
    String systemId =
        OperationBindingProps.stringProp(props, OperationBindingKeys.INTEGRATION_SYSTEM_ID);

    Optional<OperationBindingResolved> resolved = operationLookup.resolve(conversationId, operationId, specId,
        systemId);
    if (resolved.isEmpty()) {
      return CatalogOperationBindingEnrichResult.unresolved(
          node.getExpectedProperties(), OperationBindingUnresolved.reason(operationId, specId));
    }

    applicator.get().applyResolved(node, resolved.get(), specId, props);
    return CatalogOperationBindingEnrichResult.unchanged(props);
  }

  private Optional<OperationBindingApplicator> findApplicator(ElementPlan node) {
    String type = node.getType();
    if (type == null) {
      return Optional.empty();
    }
    String trimmed = type.trim();
    for (OperationBindingApplicator candidate : this.applicators) {
      if (candidate.supports(trimmed)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * Skip enrich only when explicitly unbound and no real operation id in plan.
   */
  private static boolean shouldSkipEnrichForUnbound(ElementPlan node) {
    if (!isUserAcceptedUnbound(node.getBindingStatus())) {
      return false;
    }
    return !PlanServiceBindingRules.hasNonBlankIntegrationOperationId(node.getExpectedProperties());
  }

  private static boolean isUserAcceptedUnbound(String bindingStatus) {
    return bindingStatus != null && "user_accepted_unbound".equalsIgnoreCase(bindingStatus.trim());
  }

  private static String conversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }
}

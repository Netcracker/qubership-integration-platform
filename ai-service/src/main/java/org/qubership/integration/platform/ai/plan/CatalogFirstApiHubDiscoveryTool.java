package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubSearchAuthorizations;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Requirement-stage API resolver that makes a local catalog check a hard prerequisite for API Hub
 * discovery.
 *
 * <p>Every call is about one outbound service call, named by the fact text the caller will capture
 * for it. The answer is stored as that fact's assessment, so a chain with several outbound calls
 * ends up with one outcome per call rather than one binding for the whole draft.
 */
@ApplicationScoped
public class CatalogFirstApiHubDiscoveryTool {

  private final CatalogBindingMatcher catalogBindingMatcher;
  private final CatalogSystemReadTool catalogReadTool;
  private final ConversationCatalogCache catalogCache;
  private final ApiHubMcpTools apiHubMcpTools;
  private final ConversationApiResolutions conversationResolutions;
  private final ApiHubSearchAuthorizations searchAuthorizations;
  private final ObjectMapper objectMapper;
  private final RequirementDraftStore draftStore;

  @Inject
  public CatalogFirstApiHubDiscoveryTool(
      CatalogBindingMatcher catalogBindingMatcher,
      CatalogSystemReadTool catalogReadTool,
      ConversationCatalogCache catalogCache,
      ApiHubMcpTools apiHubMcpTools,
      ConversationApiResolutions conversationResolutions,
      ApiHubSearchAuthorizations searchAuthorizations,
      ObjectMapper objectMapper,
      RequirementDraftStore draftStore) {
    this.catalogBindingMatcher = catalogBindingMatcher;
    this.catalogReadTool = catalogReadTool;
    this.catalogCache = catalogCache;
    this.apiHubMcpTools = apiHubMcpTools;
    this.conversationResolutions = conversationResolutions;
    this.searchAuthorizations = searchAuthorizations;
    this.objectMapper = objectMapper;
    this.draftStore = draftStore;
  }

  CatalogFirstApiHubDiscoveryTool(
      CatalogBindingMatcher catalogBindingMatcher,
      CatalogSystemReadTool catalogReadTool,
      ConversationCatalogCache catalogCache,
      ApiHubMcpTools apiHubMcpTools,
      ConversationApiResolutions conversationResolutions,
      ApiHubSearchAuthorizations searchAuthorizations,
      ObjectMapper objectMapper) {
    this(
        catalogBindingMatcher,
        catalogReadTool,
        catalogCache,
        apiHubMcpTools,
        conversationResolutions,
        searchAuthorizations,
        objectMapper,
        null);
  }

  @Tool("""
      Resolve one required outbound service operation. This tool always checks the local runtime
      catalog first. It calls API Hub only when the catalog has no matching service and operation.
      Pass serviceCallId as the stable SERVICE_CALL occurrence id from the draft, and
      serviceCallFact as the exact SERVICE_CALL fact text you will capture for this call, so
      the answer stays attached to that call. Give the operation name, or the HTTP method and path,
      whenever you know them. If status is CATALOG_BOUND, recapture with that serviceCallId; do not
      copy catalog UUIDs by hand. If status is APIHUB_CANDIDATES, select one result with
      selectApiHubCandidate using the same serviceCallId. If status is AMBIGUOUS, ask the reader to
      choose; never search API Hub for an ambiguous local catalog match. If status is INCOMPLETE,
      ask the reader for the fields listed in missingFields; do not guess them. On a catalog miss,
      the result is the API Hub search response and may contain multiple candidates.
      """)
  public String resolveApiOperation(
      @P("Stable SERVICE_CALL occurrence id from the draft") String serviceCallId,
      @P("Exact SERVICE_CALL fact text for this outbound call") String serviceCallFact,
      @P("Best known catalog service name, or empty when unknown") String systemHint,
      @P("Required operation name, or empty when only method and path are known")
          String operationHint,
      @P("HTTP method, or empty when unknown") String method,
      @P("HTTP path, or empty when unknown") String path,
      @P("Specification or API name the reader gave, or empty when unknown")
          String specificationHint,
      @P("Transport: http, kafka, or amqp, or empty when unknown") String protocol,
      @P("Optional target release, for example 2024.4") String release) {
    String factText = CatalogStrings.blankToNull(serviceCallFact);
    if (factText == null) {
      return error("serviceCallFact is required");
    }
    String sourceFactId =
        RequirementFact.deriveSourceFactId(RequirementFactPolarity.POSITIVE, factText);
    String conversationId = ChainPlanTool.resolveConversationId();
    String resolvedCallId = resolveServiceCallId(conversationId, serviceCallId, sourceFactId);
    if (resolvedCallId == null) {
      return error(
          "serviceCallId is required when the draft has several service calls. Pass the id of"
              + " the call you are resolving: "
              + listedServiceCallIds(conversationId));
    }
    ServiceCallAssessment.Intent intent =
        new ServiceCallAssessment.Intent(
            factText, systemHint, operationHint, method, path, specificationHint);

    if (!intent.missingFields().isEmpty()) {
      return remember(
          conversationId, ServiceCallAssessment.incomplete(resolvedCallId, sourceFactId, intent));
    }

    CatalogBindingMatcher.MatchResult result =
        catalogBindingMatcher.match(
            intent.systemHint(), intent.operationQuery(), null, release);
    if (result instanceof CatalogBindingMatcher.MatchResult.Exact exact) {
      rememberCatalogEvidence(conversationId, intent.systemHint(), exact.match());
      return remember(
          conversationId,
          ServiceCallAssessment.resolved(resolvedCallId, sourceFactId, intent, exact.match()));
    }
    if (result instanceof CatalogBindingMatcher.MatchResult.Ambiguous ambiguous) {
      return remember(
          conversationId,
          ServiceCallAssessment.ambiguous(
              resolvedCallId, sourceFactId, intent, ambiguous.candidateIds()));
    }
    conversationResolutions.remember(
        conversationId, ServiceCallAssessment.catalogMiss(resolvedCallId, sourceFactId, intent));
    // The miss is what authorizes the search, and it authorizes it for this call alone.
    searchAuthorizations.issue(
        conversationId, resolvedCallId, intent.operationQuery(), "confirmed catalog miss");
    return apiHubMcpTools.searchApiOperations(
        intent.operationQuery(), apiTypeFor(protocol), release, 0, 100, null);
  }

  /**
   * The API Hub API type to search under.
   *
   * <p>A message-broker operation is not in API Hub's REST index, and searching that index for it
   * returns nothing however long the search runs. An unknown transport stays REST: most calls are,
   * and a missing method and path mean the operation was named rather than addressed, not that it
   * is asynchronous.
   */
  static String apiTypeFor(String protocol) {
    String transport = CatalogStrings.blankToNull(protocol);
    if (transport == null) {
      return "rest";
    }
    return switch (transport.toLowerCase(Locale.ROOT)) {
      case "kafka", "amqp", "rabbit", "rabbitmq", "jms" -> "asyncapi";
      default -> "rest";
    };
  }

  private String remember(String conversationId, ServiceCallAssessment assessment) {
    conversationResolutions.remember(conversationId, assessment);
    return encode(assessment);
  }

  private void rememberCatalogEvidence(
      String conversationId, String serviceName, CatalogBindingMatcher.CatalogMatch match) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    String search = serviceName == null ? match.systemName() : serviceName;
    List<CatalogRestClient.SystemDto> systems = catalogReadTool.searchCatalogSystems(search);
    catalogCache.rememberSystems(conversationId, systems);
    catalogCache.rememberActiveSystemId(conversationId, match.systemId());
    List<CatalogRestClient.SpecificationDto> specifications =
        catalogReadTool.getApiSpecifications(match.systemId());
    catalogCache.rememberSpecificationsForSystem(conversationId, match.systemId(), specifications);
    List<CatalogRestClient.OperationDto> operations =
        catalogReadTool.listCatalogOperations(
            match.specificationId(), match.systemId(), null);
    for (CatalogRestClient.OperationDto operation : operations) {
      catalogCache.rememberOperation(conversationId, operation);
    }
  }

  private String encode(ServiceCallAssessment assessment) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("serviceCallId", assessment.serviceCallId());
      root.put("sourceFactId", assessment.sourceFactId());
      switch (assessment.outcome()) {
        case RESOLVED -> {
          root.put("status", "CATALOG_BOUND");
          CatalogBindingMatcher.CatalogMatch match = assessment.binding();
          ObjectNode binding = root.putObject("catalogBinding");
          binding.put("systemId", match.systemId());
          binding.put("specificationId", match.specificationId());
          binding.put("specificationGroupId", match.specificationGroupId());
          binding.put("integrationOperationId", match.integrationOperationId());
          binding.put("systemName", match.systemName());
          binding.put("evidenceRef", match.evidenceRef());
        }
        case AMBIGUOUS -> {
          root.put("status", "AMBIGUOUS");
          root.putPOJO("candidateOperationIds", assessment.candidateOperationIds());
        }
        case INCOMPLETE -> {
          root.put("status", "INCOMPLETE");
          root.putPOJO("missingFields", assessment.missingIntentFields());
        }
        case CATALOG_MISS -> root.put("status", "CATALOG_MISS");
      }
      return objectMapper.writeValueAsString(root);
    } catch (Exception exception) {
      return error("could not encode resolution outcome: " + exception.getMessage());
    }
  }

  private String resolveServiceCallId(
      String conversationId, String serviceCallId, String derivedFactId) {
    String explicit = CatalogStrings.blankToNull(serviceCallId);
    if (explicit != null) {
      return explicit;
    }
    List<String> active = activeServiceCallIds(conversationId);
    if (active.size() > 1) {
      return null;
    }
    if (active.size() == 1) {
      return active.getFirst();
    }
    return derivedFactId;
  }

  private List<String> activeServiceCallIds(String conversationId) {
    if (draftStore == null || conversationId == null || conversationId.isBlank()) {
      return List.of();
    }
    RequirementDraft draft = draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return List.of();
    }
    if (!draft.serviceCalls().isEmpty()) {
      List<String> ids = new ArrayList<>();
      for (RequirementServiceCall call : draft.serviceCalls()) {
        ids.add(call.serviceCallId());
      }
      return ids;
    }
    List<String> ids = new ArrayList<>();
    for (RequirementFact fact : draft.facts()) {
      if (fact != null
          && fact.polarity() == RequirementFactPolarity.POSITIVE
          && fact.kind() == RequirementFactKind.SERVICE_CALL
          && !fact.serviceCallId().isBlank()) {
        ids.add(fact.serviceCallId());
      }
    }
    return ids;
  }

  private String listedServiceCallIds(String conversationId) {
    return String.join(", ", activeServiceCallIds(conversationId));
  }

  private String error(String message) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("status", "ERROR");
      root.put("message", message);
      return objectMapper.writeValueAsString(root);
    } catch (Exception ignored) {
      return "{\"status\":\"ERROR\"}";
    }
  }
}

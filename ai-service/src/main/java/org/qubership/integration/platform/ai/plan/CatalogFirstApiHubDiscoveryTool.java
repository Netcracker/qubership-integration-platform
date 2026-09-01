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
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationDirection;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogQuery;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/**
 * Requirement-stage API resolver that makes a local catalog check a hard prerequisite for API Hub
 * discovery.
 *
 * <p>Every call is about one requirement-flow interaction, named by {@code interactionId}. The
 * answer is stored as that interaction's assessment, so a chain with several catalog-backed
 * interactions ends up with one outcome per interaction rather than one binding for the whole
 * draft.
 */
@ApplicationScoped
public class CatalogFirstApiHubDiscoveryTool {

  private final CatalogOperationLookup catalogOperationLookup;
  private final CatalogSystemReadTool catalogReadTool;
  private final ConversationCatalogCache catalogCache;
  private final ApiHubMcpTools apiHubMcpTools;
  private final ConversationApiResolutions conversationResolutions;
  private final ApiHubSearchAuthorizations searchAuthorizations;
  private final ObjectMapper objectMapper;
  private final RequirementDraftStore draftStore;

  @Inject
  public CatalogFirstApiHubDiscoveryTool(
      CatalogOperationLookup catalogOperationLookup,
      CatalogSystemReadTool catalogReadTool,
      ConversationCatalogCache catalogCache,
      ApiHubMcpTools apiHubMcpTools,
      ConversationApiResolutions conversationResolutions,
      ApiHubSearchAuthorizations searchAuthorizations,
      ObjectMapper objectMapper,
      RequirementDraftStore draftStore) {
    this.catalogOperationLookup = catalogOperationLookup;
    this.catalogReadTool = catalogReadTool;
    this.catalogCache = catalogCache;
    this.apiHubMcpTools = apiHubMcpTools;
    this.conversationResolutions = conversationResolutions;
    this.searchAuthorizations = searchAuthorizations;
    this.objectMapper = objectMapper;
    this.draftStore = draftStore;
  }

  CatalogFirstApiHubDiscoveryTool(
      CatalogOperationLookup catalogOperationLookup,
      CatalogSystemReadTool catalogReadTool,
      ConversationCatalogCache catalogCache,
      ApiHubMcpTools apiHubMcpTools,
      ConversationApiResolutions conversationResolutions,
      ApiHubSearchAuthorizations searchAuthorizations,
      ObjectMapper objectMapper) {
    this(
        catalogOperationLookup,
        catalogReadTool,
        catalogCache,
        apiHubMcpTools,
        conversationResolutions,
        searchAuthorizations,
        objectMapper,
        null);
  }

  @Tool("""
      Resolve one catalog-backed interaction, inbound or outbound. This tool always checks the
      local runtime catalog first. It calls API Hub only when the catalog has no matching service
      and operation. Pass interactionId from the stored RequirementFlow. Do not decide SERVICE_CALL
      first and do not repeat interaction prose. Give HTTP method and path only as optional
      narrowing hints. If status is CATALOG_BOUND, recapture with that interactionId; do not copy
      catalog UUIDs by hand. If status is APIHUB_CANDIDATES, select one result with
      selectApiHubCandidate using the same interactionId. If status is AMBIGUOUS, ask the reader
      to choose; never search API Hub for an ambiguous local catalog match. If status is
      INCOMPLETE, ask the reader for the fields listed in missingFields; do not guess them. On a
      catalog miss, the result is the API Hub search response and may contain multiple candidates.
      """)
  public String resolveApiOperation(
      @P("Stable interactionId from the stored RequirementFlow") String interactionId,
      @P("HTTP method, or empty when unknown") String method,
      @P("HTTP path, or empty when unknown") String path,
      @P(
          "Optional specification or specification-group name when the author already named one."
              + " Empty is normal. Do not ask the user for a specification name when service and"
              + " operation are known.")
          String specificationHint,
      @P("Transport: http, kafka, or amqp, or empty when unknown") String protocol,
      @P("Optional target release, for example 2024.4") String release) {
    String resolvedInteractionId = CatalogStrings.blankToNull(interactionId);
    if (resolvedInteractionId == null) {
      return error("interactionId is required");
    }
    String conversationId = ChainPlanTool.resolveConversationId();
    RequirementFlow.Interaction interaction = storedInteraction(conversationId, resolvedInteractionId);
    if (interaction == null) {
      return error("Capture RequirementFlow before resolving interactionId=" + resolvedInteractionId);
    }
    InteractionAssessment.Intent intent = intentFrom(interaction, method, path, specificationHint);

    if (!intent.missingFields().isEmpty()) {
      return remember(
          conversationId, InteractionAssessment.incomplete(resolvedInteractionId, intent));
    }

    CatalogLookupResult result =
        catalogOperationLookup.resolve(
            new CatalogQuery(
                intent.systemHint(),
                intent.specificationHint(),
                protocol,
                intent.method(),
                intent.path(),
                intent.operationHint(),
                release,
                namedInRequest(conversationId, intent)));
    if (result instanceof CatalogLookupResult.Exact exact) {
      if (CatalogOperationDirection.from(exact.match().protocol(), exact.match().method())
          .isEmpty()) {
        return remember(
            conversationId,
            InteractionAssessment.tooBroad(
                resolvedInteractionId, intent, List.of("catalogOperationDirection")));
      }
      rememberCatalogEvidence(conversationId, exact.match());
      return remember(
          conversationId,
          InteractionAssessment.resolved(resolvedInteractionId, intent, exact.match()));
    }
    if (result instanceof CatalogLookupResult.Ambiguous ambiguous) {
      return remember(
          conversationId,
          InteractionAssessment.ambiguous(
              resolvedInteractionId, intent, ambiguous.candidateIds()));
    }
    if (result instanceof CatalogLookupResult.TooBroad) {
      return remember(
          conversationId,
          InteractionAssessment.tooBroad(
              resolvedInteractionId, intent, List.of("systemHint")));
    }
    conversationResolutions.remember(
        conversationId, InteractionAssessment.catalogMiss(resolvedInteractionId, intent));
    // The miss is what authorizes the search, and it authorizes it for this interaction alone.
    searchAuthorizations.issue(
        conversationId, resolvedInteractionId, intent.operationQuery(), "confirmed catalog miss");
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

  private String remember(String conversationId, InteractionAssessment assessment) {
    conversationResolutions.remember(conversationId, assessment);
    return encode(assessment);
  }

  private void rememberCatalogEvidence(String conversationId, CatalogMatch match) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
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

  private String encode(InteractionAssessment assessment) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("interactionId", assessment.interactionId());
      switch (assessment.outcome()) {
        case RESOLVED -> {
          root.put("status", "CATALOG_BOUND");
          CatalogMatch match = assessment.binding();
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

  private List<String> namedInRequest(
      String conversationId, InteractionAssessment.Intent intent) {
    List<String> named = new ArrayList<>();
    if (intent.capability() != null) {
      named.add(intent.capability());
    }
    if (intent.operationHint() != null) {
      named.add(intent.operationHint());
    }
    if (draftStore == null || conversationId == null || conversationId.isBlank()) {
      return List.copyOf(named);
    }
    RequirementDraft draft = draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return List.copyOf(named);
    }
    if (!draft.assembledText().isBlank()) {
      named.add(draft.assembledText());
    }
    for (RequirementFlow.Interaction interaction : draft.flow().interactions()) {
      if (interaction != null && !interaction.operation().isBlank()) {
        named.add(interaction.operation());
      }
    }
    for (RequirementFact fact : draft.facts()) {
      if (fact != null && !fact.operation().isBlank()) {
        named.add(fact.operation());
      }
    }
    return List.copyOf(named);
  }

  private RequirementFlow.Interaction storedInteraction(
      String conversationId, String interactionId) {
    if (draftStore == null || conversationId == null || conversationId.isBlank()) {
      return null;
    }
    RequirementDraft draft = draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return null;
    }
    return draft.flow().interaction(interactionId).orElse(null);
  }

  private static InteractionAssessment.Intent intentFrom(
      RequirementFlow.Interaction interaction,
      String method,
      String path,
      String specificationHint) {
    String capability =
        interaction.description().isBlank() ? interaction.operation() : interaction.description();
    return new InteractionAssessment.Intent(
        capability,
        interaction.participant(),
        interaction.operation(),
        method,
        path,
        specificationHint);
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

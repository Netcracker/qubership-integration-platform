package org.qubership.integration.platform.ai.compiler.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.ScriptBodyPromptRedaction;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.ElementPatchValidationMessages;

/**
 * Evaluates readiness signals against workspace state.
 *
 * <p>Signals are of two kinds. Graph signals (node types, node properties, structural gaps) are
 * decided here against the {@link ChainPlanGraph}. Intent signals are decided upstream by an LLM
 * classifier and passed in as {@code matchedIntents}; this class only checks membership. The intent
 * vocabulary is {@link #intentConcepts()} with human-readable descriptions in {@link
 * #intentCatalogText()}.
 */
@ApplicationScoped
public final class GeneratorReadinessEvaluator {

  // ponytail: 32 is a defensive cap so targetNodeIds never returns an unbounded list to the caller.
  private static final int MAX_TARGET_NODE_IDS = 32;

  private final DeterministicElementSchemaService schemaService;
  private final ObjectMapper objectMapper;

  /** Intent concept vocabulary for the LLM classifier: concept id -> one-line description. */
  private static final Map<String, String> INTENT_CATALOG = buildIntentCatalog();

  /** Signal names that consult {@code matchedIntents}; used to decide whether to classify at all. */
  private static final Set<String> INTENT_SIGNALS =
      Set.of(
          "explicit_error_handling",
          "backend_integration_intent",
          "branching_intent",
          "branching_without_routing_nodes",
          "incomplete_routing_nodes",
          "rbac",
          "abac",
          "abac_intent",
          "credentials",
          "timeout_intent",
          "retry_intent",
          "composition_intent",
          "loop_intent",
          "parallel_intent",
          "monitoring_intent",
          "mcp_trigger_intent",
          "mcp_service_intent",
          "chain_failure_handler_intent",
          "file_operations_intent",
          "sftp_trigger_intent",
          "sds_trigger_intent",
          "context_storage_intent",
          "messaging_intent",
          "xslt_intent");

  /** Gap signals safe to re-check on the merged graph after a generator patch is captured. */
  private static final Set<String> COMPLETENESS_SIGNALS =
      Set.of(
          "script_nodes_missing_body",
          "incomplete_http_trigger_endpoint",
          "incomplete_try_catch_nodes",
          "incomplete_routing_nodes",
          "rbac_roles_missing",
          "incomplete_service_call_bindings");

  public GeneratorReadinessEvaluator() {
    this(null, new ObjectMapper());
  }

  @Inject
  public GeneratorReadinessEvaluator(
      DeterministicElementSchemaService schemaService, ObjectMapper objectMapper) {
    this.schemaService = schemaService;
    this.objectMapper = objectMapper;
  }

  public EvaluationResult evaluate(
      List<String> signals, ChainPlanGraph graph, Set<String> matchedIntents) {
    if (signals == null || signals.isEmpty()) {
      return EvaluationResult.blocked(List.of());
    }
    Set<String> intents = matchedIntents == null ? Set.of() : matchedIntents;
    List<String> matched = new ArrayList<>();
    for (String signal : signals) {
      if (matches(signal, graph, intents)) {
        matched.add(signal);
      }
    }
    if (matched.contains("always_ready") || !matched.isEmpty()) {
      return EvaluationResult.ready(matched, targetNodeIds(graph, matched));
    }
    return EvaluationResult.skipped(List.of());
  }

  /** Intent concept ids the classifier may return. */
  public Set<String> intentConcepts() {
    return INTENT_CATALOG.keySet();
  }

  /** Renders the intent catalog as prompt text ({@code - concept: description} lines). */
  public String intentCatalogText() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : INTENT_CATALOG.entrySet()) {
      sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
    }
    return sb.toString();
  }

  /** True when any signal needs the LLM intent classifier, so the caller can skip the call otherwise. */
  public static boolean requiresIntentClassification(Collection<String> signals) {
    return signals != null && signals.stream().anyMatch(INTENT_SIGNALS::contains);
  }

  /**
   * Re-checks the merged graph for gap signals this generator declared. Empty list means complete.
   */
  public List<String> unmetCompleteness(List<String> declaredSignals, ChainPlanGraph graph) {
    if (declaredSignals == null) {
      return List.of();
    }
    List<String> unmet = new ArrayList<>();
    for (String signal : declaredSignals) {
      if (COMPLETENESS_SIGNALS.contains(signal) && matches(signal, graph, Set.of())) {
        unmet.add(signal);
      }
    }
    return unmet;
  }

  public List<String> scriptNodesMissingBody(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    return graph.nodes().stream()
        .filter(node -> "script".equals(node.type()) && !hasNonBlankProperty(node, "script"))
        .map(ChainPlanNode::nodeId)
        .filter(nodeId -> nodeId != null && !nodeId.isBlank())
        .limit(MAX_TARGET_NODE_IDS)
        .toList();
  }

  private boolean matches(String signal, ChainPlanGraph graph, Set<String> intents) {
    return switch (signal) {
      case "always_ready" -> true;
      case "explicit_error_handling" -> intents.contains("error_handling") && !hasCompleteTryCatch(graph);
      case "unwanted_error_handling_nodes" ->
          hasNodeType(graph, ChainElementFamilies.TRY_CATCH)
              && !intents.contains("error_handling")
              && !intents.contains("chain_failure_handler");
      case "try_catch_nodes", "incomplete_try_catch_nodes" -> hasIncompleteTryCatch(graph);
      case "service_call_nodes" -> hasNodeType(graph, Set.of("service-call"));
      case "backend_integration_intent" -> intents.contains("backend_integration");
      case "routing_nodes" -> hasNodeType(graph, ChainElementFamilies.ROUTING);
      case "branching_intent" -> intents.contains("branching");
      case "branching_without_routing_nodes" ->
          intents.contains("branching") && !hasNodeType(graph, ChainElementFamilies.ROUTING_MODERN);
      case "incomplete_routing_nodes" -> hasIncompleteRouting(graph, intents);
      case "external_route" -> hasExternalRoute(graph);
      case "rbac" -> intents.contains("rbac") || hasRbacProperty(graph);
      case "abac", "abac_intent" -> intents.contains("abac") || hasAbacProperty(graph);
      case "tls" -> hasTlsProperty(graph);
      case "credentials" -> intents.contains("credentials");
      case "timeout_intent" -> intents.contains("timeout");
      case "retry_intent" -> intents.contains("retry");
      case "composition_intent" -> intents.contains("composition");
      case "chain_call_nodes" -> hasNodeType(graph, ChainElementFamilies.CHAIN_CALL);
      case "loop_intent" -> intents.contains("loop");
      case "loop_nodes" -> hasNodeType(graph, ChainElementFamilies.LOOP);
      case "parallel_intent" -> intents.contains("parallel");
      case "parallel_nodes" -> hasNodeType(graph, ChainElementFamilies.PARALLEL);
      case "monitoring_intent" -> intents.contains("monitoring");
      case "mcp_trigger_intent" -> intents.contains("mcp_trigger");
      case "mcp_service_intent" -> intents.contains("mcp_service");
      case "mcp_trigger_nodes" -> hasNodeType(graph, Set.of("mcp-trigger"));
      case "chain_failure_handler_intent" -> intents.contains("chain_failure_handler");
      case "http_trigger_nodes" -> hasNodeType(graph, Set.of("http-trigger"));
      case "incomplete_http_trigger_endpoint" -> hasIncompleteHttpTriggerEndpoint(graph);
      case "incomplete_service_call_bindings" -> hasIncompleteServiceCallBindings(graph);
      case "file_operations_intent" -> intents.contains("file_operations");
      case "file_operations_nodes" ->
          hasNodeType(graph, Set.of("file-read", "file-write", "sftp-download", "sftp-upload"));
      case "sftp_trigger_intent" -> intents.contains("sftp_trigger");
      case "sftp_trigger_nodes" -> hasNodeType(graph, Set.of("sftp-trigger-2"));
      case "sds_trigger_intent" -> intents.contains("sds_trigger");
      case "sds_trigger_nodes" -> hasNodeType(graph, Set.of("sds-trigger"));
      case "context_storage_intent" -> intents.contains("context_storage");
      case "context_storage_nodes" -> hasNodeType(graph, Set.of("context-storage"));
      case "messaging_intent" -> intents.contains("messaging");
      case "messaging_nodes" ->
          hasNodeType(graph, Set.of("jms-trigger", "jms-sender", "pubsub-trigger", "pubsub-sender"));
      case "xslt_intent" -> intents.contains("xslt");
      case "xslt_nodes" -> hasNodeType(graph, Set.of("xslt"));
      case "script_nodes_missing_body" -> hasScriptNodesMissingBody(graph);
      case "rbac_roles_missing" -> hasRbacRolesMissing(graph);
      default -> false;
    };
  }

  private static Map<String, String> buildIntentCatalog() {
    Map<String, String> catalog = new LinkedHashMap<>();
    catalog.put("error_handling", "Explicit request for error handling — try/catch/finally around the flow.");
    catalog.put("backend_integration", "Calling a downstream or external backend service.");
    catalog.put("branching", "Conditional routing — if/else, choice, or split by a condition.");
    catalog.put("rbac", "Role-based access control — restrict access by role.");
    catalog.put("abac", "Attribute-based access control — restrict access by attributes (ABAC).");
    catalog.put("credentials", "Credentials, passwords, secrets, or secured variables.");
    catalog.put("timeout", "Request or response timeouts, deadlines, or a timeout hierarchy.");
    catalog.put("retry", "Retry, redelivery, or backoff on failure.");
    catalog.put("composition", "Composing or reusing another chain (chain-call).");
    catalog.put("loop", "Iterating over items — a loop or foreach.");
    catalog.put("parallel", "Running branches in parallel — a split.");
    catalog.put("monitoring", "Monitoring, observability, checkpoints, or session logging.");
    catalog.put("mcp_trigger", "Exposing the chain as an MCP trigger or tool (Model Context Protocol).");
    catalog.put("mcp_service", "Calling an MCP service or server (Model Context Protocol).");
    catalog.put("chain_failure_handler", "An inline chain failure handler on the trigger.");
    catalog.put("file_operations", "Reading or writing files, or SFTP download/upload.");
    catalog.put("sftp_trigger", "An SFTP trigger — poll SFTP or react to file arrival.");
    catalog.put("sds_trigger", "A scheduled data sync (SDS) trigger.");
    catalog.put("context_storage", "Context storage — distributed state or a context service.");
    catalog.put("messaging", "Messaging — JMS, Pub/Sub, message queues, or topics.");
    catalog.put("xslt", "XSLT / XML transformation via a stylesheet.");
    return catalog;
  }

  private static List<String> targetNodeIds(ChainPlanGraph graph, List<String> matchedSignals) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    List<String> nodeIds = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node.nodeId() != null && nodeIds.size() < MAX_TARGET_NODE_IDS) {
        nodeIds.add(node.nodeId());
      }
    }
    if (!matchedSignals.isEmpty() && nodeIds.isEmpty()) {
      return List.of();
    }
    return List.copyOf(nodeIds);
  }

  private static boolean hasNodeType(ChainPlanGraph graph, Set<String> types) {
    if (graph == null || graph.nodes() == null || types == null || types.isEmpty()) {
      return false;
    }
    // Set.of(...).contains(null) throws NPE; multi-turn graphs can include nodes with null type.
    return graph.nodes().stream()
        .anyMatch(node -> node != null && node.type() != null && types.contains(node.type()));
  }

  private static boolean hasIncompleteRouting(ChainPlanGraph graph, Set<String> intents) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return false;
    }
    if (hasNodeType(graph, ChainElementFamilies.ROUTING_DEPRECATED)) {
      return true;
    }
    List<ChainPlanNode> nodes = graph.nodes();
    for (ChainPlanNode node : nodes) {
      if ("if".equals(node.type()) && !hasNonBlankProperty(node, "condition")) {
        return true;
      }
    }
    boolean branching = intents.contains("branching");
    for (ChainPlanNode node : nodes) {
      if (!"condition".equals(node.type())) {
        continue;
      }
      String conditionId = node.nodeId();
      if (!hasChildOfType(nodes, conditionId, "if")) {
        return true;
      }
      if (branching && !hasChildOfType(nodes, conditionId, "else")) {
        return true;
      }
    }
    return false;
  }

  public List<String> serviceCallNodesMissingBindings(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    return graph.nodes().stream()
        .filter(this::serviceCallMissingBindings)
        .map(ChainPlanNode::nodeId)
        .filter(nodeId -> nodeId != null && !nodeId.isBlank())
        .limit(MAX_TARGET_NODE_IDS)
        .toList();
  }

  /**
   * Compact schema failure for the first incomplete service-call node, when IDs are present but
   * the operation branch still fails element schema validation.
   */
  public Optional<String> serviceCallBindingSchemaFailure(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || schemaService == null) {
      return Optional.empty();
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (!"service-call".equals(node.type())) {
        continue;
      }
      if (!hasNonBlankProperty(node, "integrationSystemId")
          || !hasNonBlankProperty(node, "integrationOperationId")) {
        if (!hasNonBlankProperty(node, "systemType")) {
          return Optional.of(
              "node '" + node.nodeId() + "': systemType is required (catalog system type)");
        }
        continue;
      }
      Optional<String> failure = serviceCallSchemaFailureDetail(node);
      if (failure.isPresent()) {
        return failure;
      }
    }
    return Optional.empty();
  }

  private boolean hasIncompleteServiceCallBindings(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (serviceCallMissingBindings(node)) {
        return true;
      }
    }
    return false;
  }

  private boolean serviceCallMissingBindings(ChainPlanNode node) {
    if (!"service-call".equals(node.type())) {
      return false;
    }
    if (!hasNonBlankProperty(node, "integrationSystemId")
        || !hasNonBlankProperty(node, "integrationOperationId")) {
      return true;
    }
    if (schemaService == null) {
      return false;
    }
    return serviceCallSchemaFailureDetail(node).isPresent();
  }

  private Optional<String> serviceCallSchemaFailureDetail(ChainPlanNode node) {
    try {
      ObjectNode patch = objectMapper.createObjectNode();
      if (node.label() != null && !node.label().isBlank()) {
        patch.put("name", node.label());
      }
      ObjectNode properties = objectMapper.createObjectNode();
      if (node.properties() != null) {
        for (PlanProperty property : node.properties()) {
          if (property.key() != null && !property.key().isBlank()) {
            properties.set(property.key(), propertyValueAsJson(property.value()));
          }
        }
      }
      patch.set("properties", properties);
      String validationJson =
          schemaService.validateElementPatch(
              "service-call", objectMapper.writeValueAsString(patch));
      JsonNode result = objectMapper.readTree(validationJson);
      if (!result.has("error") && result.path("valid").asBoolean(false)) {
        return Optional.empty();
      }
      String summary = ElementPatchValidationMessages.summarizeFailure(validationJson, objectMapper);
      if (summary == null || summary.isBlank() || "valid".equals(summary)) {
        return Optional.of("node '" + node.nodeId() + "': service-call schema validation failed");
      }
      return Optional.of("node '" + node.nodeId() + "': " + summary);
    } catch (Exception e) {
      return Optional.of(
          "node '"
              + node.nodeId()
              + "': service-call schema validation failed ("
              + e.getClass().getSimpleName()
              + ")");
    }
  }

  private JsonNode propertyValueAsJson(String value) throws JsonProcessingException {
    if (value == null) {
      return objectMapper.nullNode();
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      JsonNode node = objectMapper.readTree(trimmed);
      return objectMapper.valueToTree(objectMapper.convertValue(node, new TypeReference<>() {}));
    }
    if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
      return objectMapper.valueToTree(Boolean.parseBoolean(trimmed));
    }
    return objectMapper.valueToTree(value);
  }

  private static boolean hasIncompleteHttpTriggerEndpoint(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (!"http-trigger".equals(node.type())) {
        continue;
      }
      if (!hasNonBlankProperty(node, "contextPath")
          || !hasNonBlankProperty(node, "httpMethodRestrict")
          || !hasNonBlankProperty(node, "externalRoute")) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasChildOfType(
      List<ChainPlanNode> nodes, String parentNodeId, String childType) {
    return nodes.stream()
        .anyMatch(node -> childType.equals(node.type()) && parentNodeId.equals(node.parentNodeId()));
  }

  private static boolean hasNonBlankProperty(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (!key.equals(property.key())) {
        continue;
      }
      if ("script".equals(key)) {
        return ScriptBodyPromptRedaction.isPresentScriptBody(property.value());
      }
      if (property.value() != null && !property.value().isBlank()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasScriptNodesMissingBody(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if ("script".equals(node.type()) && !hasNonBlankProperty(node, "script")) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRbacRolesMissing(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (nodeHasPropertyValue(node, "accessControlType", "RBAC")
          && !hasNonEmptyListProperty(node, "roles")) {
        return true;
      }
    }
    return false;
  }

  private static boolean nodeHasPropertyValue(ChainPlanNode node, String key, String expected) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key()) && expected.equalsIgnoreCase(property.value())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasNonEmptyListProperty(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (!key.equals(property.key())) {
        continue;
      }
      String value = property.value();
      if (value == null || value.isBlank()) {
        return false;
      }
      String trimmed = value.trim();
      if (!trimmed.startsWith("[")) {
        return true;
      }
      return !"[]".equals(trimmed);
    }
    return false;
  }

  private static boolean hasIncompleteTryCatch(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return false;
    }
    if (hasNodeType(graph, ChainElementFamilies.TRY_CATCH_DEPRECATED)) {
      return true;
    }
    if (!hasNodeType(graph, ChainElementFamilies.TRY_CATCH)) {
      return false;
    }
    return !hasCompleteTryCatch(graph);
  }

  private static boolean hasCompleteTryCatch(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return false;
    }
    List<ChainPlanNode> nodes = graph.nodes();
    boolean hasWrapper = false;
    for (ChainPlanNode node : nodes) {
      if (!"try-catch-finally-2".equals(node.type())) {
        continue;
      }
      hasWrapper = true;
      String wrapperId = node.nodeId();
      if (!hasChildOfType(nodes, wrapperId, "try-2")) {
        return false;
      }
      ChainPlanNode catchNode = findChildOfType(nodes, wrapperId, "catch-2");
      if (catchNode == null || !hasNonBlankProperty(catchNode, "exception")) {
        return false;
      }
    }
    return hasWrapper;
  }

  private static ChainPlanNode findChildOfType(
      List<ChainPlanNode> nodes, String parentNodeId, String childType) {
    return nodes.stream()
        .filter(node -> childType.equals(node.type()) && parentNodeId.equals(node.parentNodeId()))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasExternalRoute(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return false;
    }
    return graph.nodes().stream().anyMatch(GeneratorReadinessEvaluator::nodeHasExternalRoute);
  }

  private static boolean nodeHasExternalRoute(ChainPlanNode node) {
    if (!"http-trigger".equals(node.type()) || node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if ("externalRoute".equals(property.key()) && isTruthy(property.value())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRbacProperty(ChainPlanGraph graph) {
    return hasPropertyValue(graph, "accessControlType", "RBAC");
  }

  private static boolean hasAbacProperty(ChainPlanGraph graph) {
    return hasPropertyValue(graph, "accessControlType", "ABAC");
  }

  private static boolean hasTlsProperty(ChainPlanGraph graph) {
    return hasPropertyValue(graph, "sslProtocol", "TLS");
  }

  private static boolean hasPropertyValue(ChainPlanGraph graph, String key, String expected) {
    if (graph == null || graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node.properties() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if (key.equals(property.key()) && expected.equalsIgnoreCase(property.value())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isTruthy(String value) {
    if (value == null) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("true") || normalized.equals("yes");
  }

  public record EvaluationResult(
      GeneratorPlanStatus status, List<String> matchedSignals, List<String> targetNodeIds) {

    static EvaluationResult ready(List<String> matchedSignals, List<String> targetNodeIds) {
      return new EvaluationResult(GeneratorPlanStatus.READY, matchedSignals, targetNodeIds);
    }

    static EvaluationResult skipped(List<String> matchedSignals) {
      return new EvaluationResult(GeneratorPlanStatus.SKIPPED, matchedSignals, List.of());
    }

    static EvaluationResult blocked(List<String> matchedSignals) {
      return new EvaluationResult(GeneratorPlanStatus.BLOCKED, matchedSignals, List.of());
    }
  }
}

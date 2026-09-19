package org.qubership.integration.platform.ai.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/**
 * Resolves chain-call and reuse-reference identity before generators run.
 *
 * <p>{@code chain-call-2.elementId} is the catalog UUID of the target {@code chain-trigger-2}.
 * Gather stores that UUID on the chain-call-2 fact {@code path}. {@code
 * reuse-reference.reuseElementId} is the plan node id of the same-chain {@code reuse} container;
 * the properties materializer remaps it after skeleton create.
 */
@ApplicationScoped
public class CompositionCatalogBinder {

  static final String ANY_CHAIN_ID = "any-chain";
  static final String CHAIN_TRIGGER_TYPE = "chain-trigger-2";

  static final String EMPTY_CATALOG_QUESTION =
      "No chain with a chain-trigger exists in the catalog. Create a chain that starts with"
          + " chain-trigger-2, then name it for this chain-call.";

  static final String CHAIN_CALL_PICKER_PROMPT = "Choose the catalog chain to call.";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CompositionCatalogBinder(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
  }

  public record ChainCallGatherResult(
      List<RequirementFact> facts, Optional<String> openQuestion, String catalogListing) {

    public ChainCallGatherResult(List<RequirementFact> facts, Optional<String> openQuestion) {
      this(facts, openQuestion, "");
    }

    public ChainCallGatherResult {
      facts = facts == null ? List.of() : List.copyOf(facts);
      catalogListing = catalogListing == null ? "" : catalogListing;
    }
  }

  public ChainPlanGraph bind(ChainPlanGraph graph, RequirementBrief brief) {
    Objects.requireNonNull(graph, "graph");
    ChainPlanGraph bound = bindReuse(graph);
    return bindChainCalls(bound, brief);
  }

  public List<CatalogElementResponseDto> listChainTriggers() {
    List<CatalogElementResponseDto> triggers =
        catalogRestClient.getElementsByType(ANY_CHAIN_ID, CHAIN_TRIGGER_TYPE);
    return triggers == null ? List.of() : List.copyOf(triggers);
  }

  public static ChainCallGatherResult gatherChainCalls(
      RequirementFlow flow,
      List<RequirementFact> facts,
      String assembledText,
      List<CatalogElementResponseDto> triggers) {
    List<RequirementFact> input = facts == null ? List.of() : facts;
    List<CatalogElementResponseDto> catalog = usableTriggers(triggers);
    List<RequirementFact> explicit = chainCallFacts(input);
    if (!explicit.isEmpty()) {
      if (catalog.isEmpty()) {
        return new ChainCallGatherResult(input, Optional.of(EMPTY_CATALOG_QUESTION));
      }
      List<RequirementFact> rewritten = new ArrayList<>(input.size());
      for (RequirementFact fact : input) {
        if (!isChainCallFact(fact)) {
          rewritten.add(fact);
          continue;
        }
        if (triggerIdExists(fact.path(), catalog)) {
          rewritten.add(fact);
          continue;
        }
        List<CatalogElementResponseDto> matches = matchesByChainName(fact.participant(), catalog);
        if (matches.size() == 1) {
          rewritten.add(withPath(fact, matches.getFirst().id));
          continue;
        }
        return new ChainCallGatherResult(
            input,
            Optional.of(pickerQuestion(fact.sourceFactId())),
            triggerList(catalog));
      }
      return new ChainCallGatherResult(List.copyOf(rewritten), Optional.empty());
    }
    return guessChainCall(flow, input, assembledText, catalog);
  }

  private ChainPlanGraph bindReuse(ChainPlanGraph graph) {
    List<ChainPlanNode> references = nodesOfType(graph, "reuse-reference");
    if (references.isEmpty()) {
      return graph;
    }
    List<ChainPlanNode> reuseNodes = nodesOfType(graph, "reuse");
    if (reuseNodes.isEmpty()) {
      throw new IllegalArgumentException(
          "reuse-reference node "
              + references.getFirst().nodeId()
              + " has no reuse container in this chain");
    }
    ChainPlanGraph result = graph;
    for (ChainPlanNode reference : references) {
      String reuseNodeId = resolveReuseNodeId(reference, reuseNodes);
      result =
          CompositionCatalogIdentity.upsertReuseReference(result, reference.nodeId(), reuseNodeId);
    }
    return result;
  }

  private ChainPlanGraph bindChainCalls(ChainPlanGraph graph, RequirementBrief brief) {
    List<ChainPlanNode> calls = nodesOfType(graph, "chain-call-2");
    if (calls.isEmpty()) {
      return graph;
    }
    List<CatalogElementResponseDto> triggers = listChainTriggers();
    ChainPlanGraph result = graph;
    for (ChainPlanNode call : calls) {
      String triggerId = requireTriggerId(call, brief, calls.size(), triggers);
      result = CompositionCatalogIdentity.upsertChainCall(result, call.nodeId(), triggerId);
    }
    return result;
  }

  private static String resolveReuseNodeId(
      ChainPlanNode reference, List<ChainPlanNode> reuseNodes) {
    if (reuseNodes.size() == 1) {
      return reuseNodes.getFirst().nodeId();
    }
    String label = reference.label() == null ? "" : reference.label().trim();
    List<ChainPlanNode> labeled =
        reuseNodes.stream()
            .filter(
                node ->
                    node.label() != null
                        && !label.isBlank()
                        && label.equalsIgnoreCase(node.label().trim()))
            .toList();
    if (labeled.size() == 1) {
      return labeled.getFirst().nodeId();
    }
    throw new IllegalArgumentException(
        "reuse-reference node "
            + reference.nodeId()
            + " has no unique reuse container in this chain");
  }

  private static String requireTriggerId(
      ChainPlanNode call,
      RequirementBrief brief,
      int chainCallCount,
      List<CatalogElementResponseDto> triggers) {
    RequirementFact matched = matchingChainCallFact(call, brief, chainCallCount);
    String path = matched == null ? "" : matched.path();
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException(
          "Chain-call "
              + call.nodeId()
              + " has no chain-trigger id. Set path on the chain-call-2 capability fact to the"
              + " catalog trigger UUID.");
    }
    String triggerId = path.trim();
    if (!triggerIdExists(triggerId, triggers)) {
      throw new IllegalArgumentException(
          "Chain-call "
              + call.nodeId()
              + " path '"
              + triggerId
              + "' is not a catalog chain-trigger-2. Recapture with a trigger id from the catalog.");
    }
    return triggerId;
  }

  private static RequirementFact matchingChainCallFact(
      ChainPlanNode call, RequirementBrief brief, int chainCallCount) {
    List<RequirementFact> facts =
        brief == null || brief.facts() == null ? List.of() : brief.facts();
    List<RequirementFact> chainCallFacts = chainCallFacts(facts);
    for (RequirementFact fact : chainCallFacts) {
      if (call.nodeId().equals(fact.sourceFactId())) {
        return fact;
      }
    }
    if (chainCallCount == 1 && chainCallFacts.size() == 1) {
      return chainCallFacts.getFirst();
    }
    return null;
  }

  private static ChainCallGatherResult guessChainCall(
      RequirementFlow flow,
      List<RequirementFact> facts,
      String assembledText,
      List<CatalogElementResponseDto> catalog) {
    if (flow == null || catalog.isEmpty()) {
      return new ChainCallGatherResult(facts, Optional.empty());
    }
    StringBuilder haystack = new StringBuilder(assembledText == null ? "" : assembledText);
    boolean askOutbound = false;
    for (Interaction interaction : flow.interactions()) {
      if (interaction.direction() != Direction.OUTBOUND) {
        continue;
      }
      if (!isUnclassifiedOutbound(interaction, facts)) {
        continue;
      }
      askOutbound = true;
      haystack.append(' ').append(interaction.participant());
      haystack.append(' ').append(interaction.operation());
    }
    if (!askOutbound) {
      return new ChainCallGatherResult(facts, Optional.empty());
    }
    String mentioned = mentionedChainName(haystack.toString(), catalog);
    if (mentioned == null) {
      return new ChainCallGatherResult(facts, Optional.empty());
    }
    return new ChainCallGatherResult(
        facts, Optional.of(guessPickerQuestion(mentioned)), triggerList(catalog));
  }

  private static boolean isUnclassifiedOutbound(
      Interaction interaction, List<RequirementFact> facts) {
    for (RequirementFact fact : facts) {
      if (fact == null
          || fact.polarity() != RequirementFactPolarity.POSITIVE
          || fact.kind() != RequirementFactKind.CAPABILITY
          || !interaction.interactionId().equals(fact.sourceFactId())) {
        continue;
      }
      if (ChainElementFamilies.isSender(fact.capabilityKey())
          || "chain-call-2".equals(fact.capabilityKey())) {
        return false;
      }
    }
    return true;
  }

  private static List<RequirementFact> chainCallFacts(List<RequirementFact> facts) {
    List<RequirementFact> chainCallFacts = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (isChainCallFact(fact)) {
        chainCallFacts.add(fact);
      }
    }
    return chainCallFacts;
  }

  private static boolean isChainCallFact(RequirementFact fact) {
    return fact != null
        && fact.polarity() == RequirementFactPolarity.POSITIVE
        && fact.kind() == RequirementFactKind.CAPABILITY
        && "chain-call-2".equals(fact.capabilityKey());
  }

  private static List<CatalogElementResponseDto> usableTriggers(
      List<CatalogElementResponseDto> triggers) {
    if (triggers == null || triggers.isEmpty()) {
      return List.of();
    }
    List<CatalogElementResponseDto> usable = new ArrayList<>();
    for (CatalogElementResponseDto trigger : triggers) {
      if (trigger == null || trigger.id == null || trigger.id.isBlank()) {
        continue;
      }
      usable.add(trigger);
    }
    return List.copyOf(usable);
  }

  private static boolean triggerIdExists(String triggerId, List<CatalogElementResponseDto> triggers) {
    if (triggerId == null || triggerId.isBlank()) {
      return false;
    }
    String expected = triggerId.trim();
    for (CatalogElementResponseDto trigger : triggers) {
      if (expected.equals(trigger.id)) {
        return true;
      }
    }
    return false;
  }

  private static List<CatalogElementResponseDto> matchesByChainName(
      String chainName, List<CatalogElementResponseDto> triggers) {
    if (chainName == null || chainName.isBlank()) {
      return List.of();
    }
    String expected = chainName.trim().toLowerCase(Locale.ROOT);
    List<CatalogElementResponseDto> matches = new ArrayList<>();
    for (CatalogElementResponseDto trigger : triggers) {
      String actual = trigger.chainName == null ? "" : trigger.chainName.trim();
      if (expected.equals(actual.toLowerCase(Locale.ROOT))) {
        matches.add(trigger);
      }
    }
    return matches;
  }

  private static String mentionedChainName(
      String haystack, List<CatalogElementResponseDto> triggers) {
    if (haystack == null || haystack.isBlank()) {
      return null;
    }
    String lowered = haystack.toLowerCase(Locale.ROOT);
    for (CatalogElementResponseDto trigger : triggers) {
      String chainName = trigger.chainName == null ? "" : trigger.chainName.trim();
      if (chainName.isBlank()) {
        continue;
      }
      if (lowered.contains(chainName.toLowerCase(Locale.ROOT))) {
        return chainName;
      }
    }
    return null;
  }

  private static RequirementFact withPath(RequirementFact fact, String path) {
    return new RequirementFact(
        fact.sourceFactId(),
        fact.polarity(),
        fact.kind(),
        fact.capabilityKey(),
        fact.text(),
        fact.participant(),
        fact.operation(),
        fact.topic(),
        fact.httpMethod(),
        path,
        fact.serviceCallId());
  }

  private static String pickerQuestion(String interactionId) {
    return "Chain-call "
        + interactionId
        + " has no unique chain-trigger yet. "
        + CHAIN_CALL_PICKER_PROMPT;
  }

  private static String guessPickerQuestion(String chainName) {
    return "The requirements mention catalog chain '"
        + chainName
        + "'. "
        + CHAIN_CALL_PICKER_PROMPT;
  }

  private static String triggerList(List<CatalogElementResponseDto> triggers) {
    List<String> labels = new ArrayList<>();
    for (CatalogElementResponseDto trigger : triggers) {
      String chainName =
          trigger.chainName == null || trigger.chainName.isBlank()
              ? "(unnamed chain)"
              : trigger.chainName.trim();
      labels.add(chainName + " (id=" + trigger.id + ")");
    }
    return String.join("; ", labels);
  }

  private static List<ChainPlanNode> nodesOfType(ChainPlanGraph graph, String type) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (type.equals(node.type())) {
        nodes.add(node);
      }
    }
    return nodes;
  }
}

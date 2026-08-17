package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.llm.agent.ChainEditIntentAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Turns a change request into a typed {@link ChainEditIntent} against the imported chain.
 *
 * <p>The model sees element ids, types and labels — enough to say which element a description
 * means, and not enough to write a patch with. An id it returns that the chain does not have is
 * dropped rather than passed on, because a target the compiler cannot find would surface later as
 * an unexplained failure instead of a question the reader can answer.
 */
@ApplicationScoped
public class ChainEditIntentResolver {

  private final ChainEditIntentAgent agent;

  @Inject
  public ChainEditIntentResolver(ChainEditIntentAgent agent) {
    this.agent = Objects.requireNonNull(agent, "agent");
  }

  public ChainEditIntent resolve(ChainPlanGraph graph, String userRequest) {
    Objects.requireNonNull(graph, "graph");
    String reply = agent.resolve(renderElements(graph), userRequest == null ? "" : userRequest);
    return parse(reply, knownNodeIds(graph));
  }

  static String renderElements(ChainPlanGraph graph) {
    StringBuilder text = new StringBuilder();
    for (ChainPlanNode node : graph.nodes() == null ? List.<ChainPlanNode>of() : graph.nodes()) {
      if (node == null || node.nodeId() == null) {
        continue;
      }
      text.append(node.nodeId())
          .append(" | ")
          .append(node.type() == null ? "" : node.type())
          .append(" | ")
          .append(node.label() == null ? "" : node.label())
          .append('\n');
    }
    return text.toString();
  }

  static ChainEditIntent parse(String reply, Set<String> knownNodeIds) {
    String action = "";
    String targets = "";
    String change = "";
    String lookup = "";
    String elementType = "";
    String ambiguous = "";
    for (String line : (reply == null ? "" : reply).split("\\R")) {
      String trimmed = line.trim();
      int colon = trimmed.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = trimmed.substring(colon + 1).trim();
      switch (key) {
        case "action" -> action = value;
        case "targets" -> targets = value;
        case "change" -> change = value;
        case "lookup" -> lookup = value;
        case "elementtype" -> elementType = value;
        case "ambiguous" -> ambiguous = value;
        default -> {
          // Any other line is prose the format did not ask for; the five keys carry the answer.
        }
      }
    }

    List<String> unresolved = new ArrayList<>(splitOn(ambiguous, ";"));
    List<String> resolvedTargets = new ArrayList<>();
    for (String candidate : splitOn(targets, ",")) {
      if (knownNodeIds.contains(candidate)) {
        resolvedTargets.add(candidate);
      } else {
        unresolved.add("The chain has no element '" + candidate + "'.");
      }
    }
    ChainEditAction parsedAction = toAction(action);
    if (parsedAction == null || parsedAction == ChainEditAction.UNRESOLVED) {
      return new ChainEditIntent(
          ChainEditAction.UNRESOLVED,
          List.of(),
          change,
          blankToNull(lookup),
          blankToNull(elementType),
          unresolved.isEmpty()
              ? List.of("Say what should change and on which element.")
              : List.copyOf(unresolved));
    }
    if (resolvedTargets.isEmpty() && unresolved.isEmpty()) {
      unresolved.add("Say which element to change.");
    }
    return new ChainEditIntent(
        parsedAction,
        resolvedTargets,
        change,
        blankToNull(lookup),
        blankToNull(elementType),
        unresolved);
  }

  private static ChainEditAction toAction(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    for (ChainEditAction action : ChainEditAction.values()) {
      if (action.name().equals(normalized)) {
        return action;
      }
    }
    return null;
  }

  private static List<String> splitOn(String value, String separator) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<String> parts = new ArrayList<>();
    for (String part : value.split(java.util.regex.Pattern.quote(separator))) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        parts.add(trimmed);
      }
    }
    return parts;
  }

  private static Set<String> knownNodeIds(ChainPlanGraph graph) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (ChainPlanNode node : graph.nodes() == null ? List.<ChainPlanNode>of() : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        ids.add(node.nodeId());
      }
    }
    return ids;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}

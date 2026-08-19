package org.qubership.integration.platform.ai.chain.edit;

import dev.langchain4j.service.output.OutputParsingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.ChainEditIntentAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Turns a change request into a typed {@link ChainEditIntent} against the imported chain.
 *
 * <p>The model returns a {@link ChainEditCapture}. This class checks that every named id exists on
 * the chain and that required fields of the capture are filled. It does not read the user's
 * wording to choose an action, a type, a target, or a placement.
 */
@ApplicationScoped
public class ChainEditIntentResolver {

  private static final Logger LOG = Logger.getLogger(ChainEditIntentResolver.class);

  private final ChainEditIntentAgent agent;

  @Inject
  public ChainEditIntentResolver(ChainEditIntentAgent agent) {
    this.agent = Objects.requireNonNull(agent, "agent");
  }

  public ChainEditIntent resolve(ChainPlanGraph graph, String userRequest) {
    Objects.requireNonNull(graph, "graph");
    ChainEditCapture capture;
    try {
      capture = agent.resolve(renderElements(graph), userRequest == null ? "" : userRequest);
    } catch (OutputParsingException e) {
      LOG.warnf(e, "Chain edit capture could not be parsed; treating as no change");
      return noChange();
    }
    return fromCapture(capture, knownNodeIds(graph));
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

  static ChainEditIntent fromCapture(ChainEditCapture capture, Set<String> knownNodeIds) {
    if (capture == null) {
      return noChange();
    }
    ChainEditAction action =
        capture.action() == null ? ChainEditAction.NO_CHANGE : capture.action();
    if (action == ChainEditAction.UNRESOLVED) {
      return unresolved(capture.ambiguities());
    }
    if (action == ChainEditAction.NO_CHANGE) {
      return capture.ambiguities().isEmpty() ? noChange() : unresolved(capture.ambiguities());
    }

    List<String> unresolved = new ArrayList<>(capture.ambiguities());
    List<String> resolvedTargets = new ArrayList<>();
    for (String candidate : capture.targetNodeIds()) {
      if (knownNodeIds.contains(candidate)) {
        resolvedTargets.add(candidate);
      } else {
        unresolved.add("The chain has no element '" + candidate + "'.");
      }
    }
    if (unresolved.isEmpty()) {
      unresolved.addAll(
          missingFields(capture.action(), resolvedTargets, capture.elementType(), capture.placement()));
    }
    return new ChainEditIntent(
        action,
        resolvedTargets,
        capture.requestedChange(),
        capture.lookup(),
        capture.elementType(),
        capture.cronExpression(),
        capture.placement(),
        unresolved);
  }

  private static ChainEditIntent noChange() {
    return new ChainEditIntent(
        ChainEditAction.NO_CHANGE,
        List.of(),
        "No change was requested.",
        null,
        null,
        null,
        ChainEditPlacement.UNSET,
        List.of());
  }

  private static ChainEditIntent unresolved(List<String> ambiguities) {
    return new ChainEditIntent(
        ChainEditAction.UNRESOLVED,
        List.of(),
        "",
        null,
        null,
        null,
        ChainEditPlacement.UNSET,
        ambiguities.isEmpty()
            ? List.of("Say what should change and on which element.")
            : List.copyOf(ambiguities));
  }

  private static List<String> missingFields(
      ChainEditAction action,
      List<String> resolvedTargets,
      String elementType,
      ChainEditPlacement placement) {
    if (action == ChainEditAction.ADD_ELEMENTS) {
      if (elementType == null) {
        return List.of("Say which element type to add.");
      }
      if (placement == null || placement == ChainEditPlacement.UNSET) {
        return List.of("Say where to place the new element.");
      }
      if (placement == ChainEditPlacement.AFTER_TARGET && resolvedTargets.isEmpty()) {
        return List.of("Say where to place the new element.");
      }
      return List.of();
    }
    if (resolvedTargets.isEmpty()) {
      return List.of("Say which element to change.");
    }
    return List.of();
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
}

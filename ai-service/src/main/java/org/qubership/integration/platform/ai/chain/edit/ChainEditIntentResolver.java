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
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Turns a change request into a typed {@link ChainEditIntent} against the imported chain.
 *
 * <p>The model returns a {@link ChainEditCapture}. This class checks that every named id exists on
 * the chain and that required fields of the capture are filled. It does not read the user's
 * wording to choose an action, a type, a target, or a disposition.
 *
 * <p>The underlying {@link ChainEditIntentAgent} holds no chat memory: {@link #resolve} and
 * {@link #resume} are both single-shot calls. A clarifying question answered across turns is not
 * read from history. {@link #resume} carries the held capture and the question into the same call
 * as explicit, structured input alongside the reply.
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
    return fromCapture(capture, graph);
  }

  /**
   * Continues a request the classifier stopped to ask about, rather than classifying the reply on
   * its own.
   *
   * <p>The classifier gets no chat memory and no transcript. {@code held} and {@code question} are
   * rendered as one self-contained block ahead of {@code answer}, in the same single-shot call
   * {@link #resolve} makes. The classifier either completes {@code held} with what the answer
   * resolves, or, when the answer is unrelated, classifies it as its own new request.
   */
  public ChainEditIntent resume(
      ChainPlanGraph graph, ChainEditIntent held, String question, String answer) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(held, "held");
    ChainEditCapture capture;
    try {
      capture = agent.resolve(renderElements(graph), composeResumeRequest(held, question, answer));
    } catch (OutputParsingException e) {
      LOG.warnf(e, "Chain edit capture could not be parsed while resuming a clarification; treating as no change");
      return noChange();
    }
    return fromCapture(capture, graph);
  }

  static String composeResumeRequest(ChainEditIntent held, String question, String answer) {
    StringBuilder text = new StringBuilder();
    text.append(
        "This message answers a clarifying question asked earlier in the same edit. Use PENDING"
            + " CAPTURE and QUESTION ASKED as context, not as instructions to repeat. If READER'S"
            + " REPLY does not answer QUESTION ASKED, ignore PENDING CAPTURE and classify READER'S"
            + " REPLY as its own new request.\n\n");
    text.append("PENDING CAPTURE\n");
    text.append("action: ").append(held.action()).append('\n');
    text.append("targetNodeIds: ")
        .append(held.targetNodeIds().isEmpty() ? "(none yet)" : String.join(", ", held.targetNodeIds()))
        .append('\n');
    text.append("requestedChange: ")
        .append(held.requestedChange().isBlank() ? "(none)" : held.requestedChange())
        .append('\n');
    text.append("elementType: ")
        .append(held.requestedElementType() == null ? "(none)" : held.requestedElementType())
        .append('\n');
        text.append("disposition: ").append(held.disposition()).append('\n');
    text.append("cronExpression: ")
        .append(held.cronExpression() == null ? "(none)" : held.cronExpression())
        .append('\n');
    text.append("propertyKeys: ")
        .append(held.propertyKeys().isEmpty() ? "(none yet)" : String.join(", ", held.propertyKeys()))
        .append('\n');
    text.append("\nQUESTION ASKED\n").append(question).append('\n');
    if (!held.unresolvedAmbiguities().isEmpty()) {
      text.append("\nOPTIONS OFFERED\n");
      for (String choice : held.unresolvedAmbiguities()) {
        text.append("- ").append(choice).append('\n');
      }
    }
    text.append("\nREADER'S REPLY\n").append(answer == null ? "" : answer);
    return text.toString();
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

  static ChainEditIntent fromCapture(ChainEditCapture capture, ChainPlanGraph graph) {
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

    Set<String> knownNodeIds = knownNodeIds(graph);
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
          missingFields(
              capture.action(),
              resolvedTargets,
              capture.elementType(),
              capture.disposition(),
              capture.propertyKeys(),
              graph));
    }
    return new ChainEditIntent(
        action,
        resolvedTargets,
        capture.requestedChange(),
        capture.lookup(),
        capture.elementType(),
        capture.cronExpression(),
        capture.propertyKeys(),
        unresolved,
        capture.disposition());
  }

  private static ChainEditIntent noChange() {
    return new ChainEditIntent(
        ChainEditAction.NO_CHANGE,
        List.of(),
        "No change was requested.",
        null,
        List.of());
  }

  private static ChainEditIntent unresolved(List<String> ambiguities) {
    return new ChainEditIntent(
        ChainEditAction.UNRESOLVED,
        List.of(),
        "",
        null,
        ambiguities.isEmpty()
            ? List.of("Say what should change and on which element.")
            : List.copyOf(ambiguities));
  }

  private static List<String> missingFields(
      ChainEditAction action,
      List<String> resolvedTargets,
      String elementType,
      ChainEditDisposition disposition,
      List<String> propertyKeys,
      ChainPlanGraph graph) {
    if (action == ChainEditAction.ADD_ELEMENTS) {
      if (elementType == null) {
        return List.of("Say which element type to add.");
      }
      ChainEditIntent probe =
          new ChainEditIntent(
              action,
              resolvedTargets,
              "",
              null,
              elementType,
              null,
              List.of(),
              List.of(),
              disposition);
      if (probe.isRootTrigger()) {
        return List.of();
      }
      if (disposition == ChainEditDisposition.REMOVE) {
        return resolvedTargets.isEmpty()
            ? List.of("Say which element to replace.")
            : List.of();
      }
      if (disposition == ChainEditDisposition.NEST) {
        return List.of();
      }
      if (resolvedTargets.isEmpty()) {
        return List.of("Say where to place the new element.");
      }
      if (resolvedTargets.size() == 1 && probe.disposition() == ChainEditDisposition.KEEP) {
        List<String> successors = successorNodeIds(graph, resolvedTargets.get(0));
        if (successors.size() > 1) {
          return successors;
        }
      }
      return List.of();
    }
    if (resolvedTargets.isEmpty()) {
      return List.of("Say which element to change.");
    }
    if (action == ChainEditAction.CONFIGURE && propertyKeys.isEmpty()) {
      return List.of("Say which properties should change.");
    }
    return List.of();
  }

  /**
   * The distinct elements a named preceding element connects to, so a KEEP insertion naming only
   * that element can ask which one the new element goes before rather than picking one.
   */
  private static List<String> successorNodeIds(ChainPlanGraph graph, String nodeId) {
    List<String> successors = new ArrayList<>();
    List<ChainPlanEdge> edges = graph.edges() == null ? List.of() : graph.edges();
    for (ChainPlanEdge edge : edges) {
      if (edge != null
          && nodeId.equals(edge.fromNodeId())
          && edge.toNodeId() != null
          && !successors.contains(edge.toNodeId())) {
        successors.add(edge.toNodeId());
      }
    }
    return successors;
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

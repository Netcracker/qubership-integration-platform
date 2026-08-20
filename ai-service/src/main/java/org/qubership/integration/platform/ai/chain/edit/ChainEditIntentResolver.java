package org.qubership.integration.platform.ai.chain.edit;

import dev.langchain4j.service.output.OutputParsingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.ChainEditIntentAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.ChainElementPropertyKeys;

/**
 * Turns a change request into a typed {@link ChainEditIntent} against the imported chain.
 *
 * <p>The model returns a {@link ChainEditCapture}. This class checks that every named id exists on
 * the chain and that required fields of the capture are filled. It does not read the user's
 * wording to choose an action, a type, a target, or a disposition.
 *
 * <p>A wrap gets one check more: its named elements must form a connected run. An element the chain
 * puts between two of them would move into the container along with them, so the reader is asked
 * about it by name rather than having it wrapped or the edit refused. See
 * {@link #gapInTheWrappedRun}.
 *
 * <p>The underlying {@link ChainEditIntentAgent} holds no chat memory: {@link #resolve} and
 * {@link #resume} are both single-shot calls. A clarifying question answered across turns is not
 * read from history. {@link #resume} carries the held capture and the question into the same call
 * as explicit, structured input alongside the reply.
 */
@ApplicationScoped
public class ChainEditIntentResolver {

  private static final Logger LOG = Logger.getLogger(ChainEditIntentResolver.class);

  /** Longest property value the listing shows before it is cut short. */
  static final int MAX_VALUE_CHARS = 80;

  private final ChainEditIntentAgent agent;
  private final ChainElementPropertyKeys propertyKeys;

  @Inject
  public ChainEditIntentResolver(
      ChainEditIntentAgent agent, ChainElementPropertyKeys propertyKeys) {
    this.agent = Objects.requireNonNull(agent, "agent");
    this.propertyKeys = Objects.requireNonNull(propertyKeys, "propertyKeys");
  }

  /** Test helper that reads property keys from the packaged schemas. */
  ChainEditIntentResolver(ChainEditIntentAgent agent) {
    this(agent, new ChainElementPropertyKeys());
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

  /**
   * The chain as the classifier sees it: one element per line, with the property keys that element
   * answers to.
   *
   * <p>A {@code CONFIGURE} capture must name the catalog's own key, and the classifier is told not
   * to guess one. Listing id, type, and label alone left it nothing to name, so a request to change
   * a timeout or a priority came back unresolved however plainly it was written. The keys already
   * carrying a value come first, with the value, because a request usually changes something the
   * element already has; the rest of the schema's keys follow as names the element also accepts.
   *
   * <p>Values are cut at {@link #MAX_VALUE_CHARS}. A script body is a property like any other, and
   * whole bodies would bury the chain in the listing meant to describe it.
   */
  String renderElements(ChainPlanGraph graph) {
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
      appendProperties(text, node);
    }
    return text.toString();
  }

  private void appendProperties(StringBuilder text, ChainPlanNode node) {
    Set<String> shown = new LinkedHashSet<>();
    List<String> set = new ArrayList<>();
    for (PlanProperty property : node.properties() == null ? List.<PlanProperty>of() : node.properties()) {
      if (property == null || property.key() == null || property.key().isBlank()) {
        continue;
      }
      if (shown.add(property.key())) {
        set.add(property.key() + "=" + shortened(property.value()));
      }
    }
    if (!set.isEmpty()) {
      text.append("    set: ").append(String.join(", ", set)).append('\n');
    }
    List<String> unset = new ArrayList<>();
    for (String key : propertyKeys.forType(node.type())) {
      if (!shown.contains(key)) {
        unset.add(key);
      }
    }
    if (!unset.isEmpty()) {
      text.append("    other keys: ").append(String.join(", ", unset)).append('\n');
    }
  }

  private static String shortened(String value) {
    if (value == null) {
      return "";
    }
    String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
    return oneLine.length() <= MAX_VALUE_CHARS
        ? oneLine
        : oneLine.substring(0, MAX_VALUE_CHARS) + "…";
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
        return resolvedTargets.isEmpty()
            ? List.of("Say which element the new one should wrap.")
            : gapInTheWrappedRun(graph, resolvedTargets);
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

  /**
   * The question a wrap gets when the elements it names leave one out of the run they enclose.
   *
   * <p>A wrap moves its elements into one branch, so an element sitting on a path between two of
   * them ends up inside the container as well, whether the reader meant it or not. The alternatives
   * are both worse than asking: wrapping it silently gives the reader a container they never
   * reviewed, and refusing spends a repair turn on a rule no capture can meet, since a capture may
   * move only the elements this intent names. The element in the gap is known here by name, so the
   * reader gets to decide.
   *
   * <p>Closure lives with the target set rather than with the capture. The assembly already
   * requires the elements named across the branches to be exactly these targets, so a capture is
   * closed when the targets are, and asking here costs no generator turn.
   */
  private static List<String> gapInTheWrappedRun(ChainPlanGraph graph, List<String> targets) {
    List<String> gap = elementsBetweenTargets(graph, targets);
    if (gap.isEmpty()) {
      return List.of();
    }
    List<String> named = gap.stream().map(nodeId -> describe(graph, nodeId)).toList();
    return List.of(
        named.size() == 1
            ? named.get(0)
                + " sits between the elements you asked me to wrap. Say whether to wrap it too,"
                + " or which elements to wrap instead."
            : join(named)
                + " sit between the elements you asked me to wrap. Say whether to wrap them too,"
                + " or which elements to wrap instead.");
  }

  /**
   * Elements the chain puts on a path between two named ones, in chain order.
   *
   * <p>An element qualifies when the named set both reaches it and is reachable from it: it follows
   * one named element and leads to another. Elements nested inside a named container move with
   * their parent, so they are not gaps. Elements on sibling branches of a split are not gaps
   * either, because no path runs through them from one named element to another.
   */
  private static List<String> elementsBetweenTargets(ChainPlanGraph graph, List<String> targets) {
    if (targets.size() < 2) {
      return List.of();
    }
    Set<String> named = new LinkedHashSet<>(targets);
    Set<String> moving = withNestedElements(graph, named);
    Set<String> downstream = connected(graph, named, true);
    Set<String> upstream = connected(graph, named, false);
    List<String> between = new ArrayList<>();
    for (ChainPlanNode node : nodesOf(graph)) {
      String nodeId = node == null ? null : node.nodeId();
      if (nodeId == null || moving.contains(nodeId) || between.contains(nodeId)) {
        continue;
      }
      if (downstream.contains(nodeId) && upstream.contains(nodeId)) {
        between.add(nodeId);
      }
    }
    return between;
  }

  /** {@code from} plus everything it reaches by following connections forward or backward. */
  private static Set<String> connected(ChainPlanGraph graph, Set<String> from, boolean forward) {
    Set<String> reached = new LinkedHashSet<>(from);
    Deque<String> pending = new ArrayDeque<>(from);
    while (!pending.isEmpty()) {
      String nodeId = pending.removeFirst();
      for (ChainPlanEdge edge : edgesOf(graph)) {
        if (edge == null) {
          continue;
        }
        String tail = forward ? edge.fromNodeId() : edge.toNodeId();
        String head = forward ? edge.toNodeId() : edge.fromNodeId();
        if (nodeId.equals(tail) && head != null && reached.add(head)) {
          pending.addLast(head);
        }
      }
    }
    return reached;
  }

  /** {@code named} plus every element nested inside one of them, at any depth. */
  private static Set<String> withNestedElements(ChainPlanGraph graph, Set<String> named) {
    Set<String> nested = new LinkedHashSet<>(named);
    boolean grew = true;
    while (grew) {
      grew = false;
      for (ChainPlanNode node : nodesOf(graph)) {
        if (node != null
            && node.nodeId() != null
            && node.parentNodeId() != null
            && nested.contains(node.parentNodeId())
            && nested.add(node.nodeId())) {
          grew = true;
        }
      }
    }
    return nested;
  }

  /** An element as the reader knows it: the label they see, with the id they can point at. */
  private static String describe(ChainPlanGraph graph, String nodeId) {
    for (ChainPlanNode node : nodesOf(graph)) {
      if (node != null
          && nodeId.equals(node.nodeId())
          && node.label() != null
          && !node.label().isBlank()) {
        return node.label() + " (" + nodeId + ")";
      }
    }
    return nodeId;
  }

  private static String join(List<String> parts) {
    if (parts.size() == 2) {
      return parts.get(0) + " and " + parts.get(1);
    }
    return String.join(", ", parts.subList(0, parts.size() - 1))
        + ", and "
        + parts.get(parts.size() - 1);
  }

  private static List<ChainPlanNode> nodesOf(ChainPlanGraph graph) {
    return graph.nodes() == null ? List.of() : graph.nodes();
  }

  private static List<ChainPlanEdge> edgesOf(ChainPlanGraph graph) {
    return graph.edges() == null ? List.of() : graph.edges();
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

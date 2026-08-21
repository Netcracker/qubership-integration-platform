package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.ChildlessOptionalContainerPruner;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflight;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflightException;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBody;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBranch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphConnection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphElement;

/**
 * Builds the graph a structural edit proposes from a capture of what it adds.
 *
 * <p>The imported chain arrives whole and leaves whole. An existing element reaches this assembly
 * as an identifier — in the branch it moves into, for a wrap, or not at all, for an insertion,
 * which moves nothing. Its type, label, order, and properties are never read from the capture.
 *
 * <p>A wrap names a new container; {@link #assemble} places it beside the elements that move into
 * it and creates each branch under it, as described on {@link #assembleContainer}. An attach names
 * a container the chain already has and adds one branch to it, as described on
 * {@link #assembleAttachBranch}; the container itself is untouched, so it is the sole target rather
 * than a fresh node this method mints. An insertion or a replacement names neither and carries its
 * new elements in a single body instead: an insertion splices the body at the address the intent
 * already resolved and keeps every address element, as described on {@link #assembleInsertion}; a
 * replacement splices the same body where the intent's targets used to be and removes them, as
 * described on {@link #assembleReplacement}. {@link ChainEditIntent#disposition()} tells the four
 * captures apart; the capture's own shape says the same thing a second way, so a capture that
 * mismatches its disposition is refused for that before any path runs.
 *
 * <p>Identifiers of new container, branch, and body nodes are minted here rather than captured. A
 * capture that named them could collide with an element the chain already has, and nothing
 * downstream needs a container or a branch node to be anything in particular: the catalog creates a
 * container together with its children and Java binds those to the planned nodes by type and order.
 *
 * <p>Connections to the chain around the edit come from {@link ChainEditBoundaryWiring}, so the
 * capture never states where the new subgraph attaches. Incoming hops of a moved or spliced element
 * arrive at the subgraph's entry, outgoing hops leave from its exit, and a connection whose two ends
 * both moved is kept as it was.
 *
 * <p>Branch shape — which child types a container allows, how many of each, and whether a repeated
 * one needs a distinguishing property and an order — comes from the live catalog descriptor, not
 * from anything written about a specific container here. See {@link #assembleContainer} for where
 * that check runs. An insertion has no container, so no descriptor governs its shape beyond the
 * requirement that its body forms one connected run.
 */
public final class ChainEditSubgraphAssembly {

  private ChainEditSubgraphAssembly() {}

  /**
   * The graph this edit proposes: the imported chain, plus the captured container and its
   * branches, the captured insertion body, or the captured replacement body with its targets
   * removed.
   *
   * @throws ChainEditScopeException when the capture describes something the intent did not
   *     approve, or something the container's catalog descriptor does not allow
   */
  public static ChainPlanGraph assemble(
      ChainPlanGraph base,
      ChainEditSubgraph capture,
      ChainEditIntent intent,
      CatalogElementDescriptorCache descriptors) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(capture, "capture");
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(descriptors, "descriptors");

    Map<String, ChainPlanNode> baseById = baseNodesById(base);
    Set<String> targets = new LinkedHashSet<>(intent.targetNodeIds());
    if (!baseById.keySet().containsAll(targets)) {
      Set<String> missing = new LinkedHashSet<>(targets);
      missing.removeAll(baseById.keySet());
      throw unsatisfiable("unknown structural target ids " + missing);
    }
    if (intent.disposition() == ChainEditDisposition.KEEP) {
      return assembleInsertion(base, baseById, capture, intent, descriptors);
    }
    if (intent.disposition() == ChainEditDisposition.REMOVE) {
      return assembleReplacement(base, baseById, capture, intent, descriptors);
    }
    if (intent.disposition() == ChainEditDisposition.ATTACH) {
      return assembleAttachBranch(base, baseById, capture, targets, descriptors);
    }
    return assembleContainer(base, baseById, capture, targets, descriptors);
  }

  /**
   * The graph a wrap or a branch proposes: the imported chain, plus the captured container and its
   * branches.
   *
   * <p>Two checks run against the live catalog before a proposal is returned. Branch shape is
   * checked against the container's own descriptor first, so a capture that names a child type the
   * container does not allow, gets a branch count wrong, or leaves a repeated role undistinguished
   * is refused for that rule rather than for whatever shape the defect takes once assembled. The
   * assembled graph then runs through {@link DesiredGraphDescriptorPreflight}, the same check a
   * catalog write would fail on, so a defect outside branch shape — a nested container still
   * missing its mandatory content, for one — is caught here instead of after the reader approves a
   * card.
   */
  private static ChainPlanGraph assembleContainer(
      ChainPlanGraph base,
      Map<String, ChainPlanNode> baseById,
      ChainEditSubgraph capture,
      Set<String> targets,
      CatalogElementDescriptorCache descriptors) {
    String containerType = required(capture.containerType(), "capture names no container type");
    if (capture.branches().isEmpty()) {
      throw correctable("capture names container '" + containerType + "' without a branch");
    }
    CatalogElementDescriptor container = elementDescriptor(containerType, descriptors);
    validateBranches(containerType, container, capture.branches(), descriptors);
    List<ChainEditSubgraphBranch> orderedBranches =
        orderBranches(descriptors, capture.branches());

    Map<String, ChainEditSubgraphBranch> branchOfMovedId =
        movedElements(capture, baseById, targets);
    Set<String> reserved = new LinkedHashSet<>(baseById.keySet());
    Map<String, ChainEditSubgraphElement> newElements = newElements(capture, reserved);
    String containerNodeId = reserveId(containerType, reserved);
    Map<ChainEditSubgraphBranch, String> branchNodeIds = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : orderedBranches) {
      String childType = required(branch.childType(), "capture names a branch without a type");
      branchNodeIds.put(branch, reserveId(childType, reserved));
    }

    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode existing : base.nodes()) {
      ChainEditSubgraphBranch branch = branchOfMovedId.get(existing.nodeId());
      nodes.add(branch == null ? existing : reparented(existing, branchNodeIds.get(branch)));
    }
    nodes.add(
        new ChainPlanNode(
            containerNodeId,
            containerType,
            capture.containerLabel(),
            commonParent(branchOfMovedId.keySet(), baseById),
            null,
            List.of()));
    Map<String, Integer> takenByChildType = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : orderedBranches) {
      String branchNodeId = branchNodeIds.get(branch);
      int siblingPosition =
          takenByChildType.merge(branch.childType(), 1, Integer::sum);
      Integer order = branch.order() == null ? siblingPosition : branch.order();
      nodes.add(
          new ChainPlanNode(
              branchNodeId,
              branch.childType(),
              branch.label(),
              containerNodeId,
              order,
              branchProperties(branch, descriptors, order)));
      for (ChainEditSubgraphElement element : branch.body().elements()) {
        nodes.add(
            new ChainPlanNode(
                element.nodeId(), element.type(), element.label(), branchNodeId, null, List.of()));
      }
    }

    Map<String, ChainPlanNode> assembledById = new LinkedHashMap<>();
    nodes.forEach(node -> assembledById.put(node.nodeId(), node));
    Set<String> addedNodeIds = new LinkedHashSet<>();
    addedNodeIds.add(containerNodeId);
    addedNodeIds.addAll(branchNodeIds.values());
    addedNodeIds.addAll(newElements.keySet());
    List<ChainPlanEdge> bodyEdges = bodyEdges(capture, branchNodeIds, newElements, base);
    ChainEditBoundaryWiring.SubgraphEnds ends =
        ChainEditBoundaryWiring.deriveSubgraphEnds(addedNodeIds, bodyEdges, assembledById);

    List<ChainPlanEdge> edges = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      ChainPlanEdge rewired =
          ChainEditBoundaryWiring.rewireMovedEndpoint(
              existing, assembledById, baseById.keySet(), ends);
      ChainPlanEdge kept = rewired == null ? existing : rewired;
      if (connections.add(connectionKey(kept))) {
        edges.add(kept);
      }
    }
    edges.addAll(bodyEdges);
    ChainPlanGraph assembled =
        new ChainPlanGraph(
            base.schemaVersion(), base.chain(), List.copyOf(nodes), List.copyOf(edges));
    runDescriptorPreflight(assembled, base, descriptors);
    return assembled;
  }

  /**
   * The graph an attach proposes: the imported chain, unchanged, plus one new branch added to a
   * container the chain already has.
   *
   * <p>Nothing moves and nothing is replaced, so the only address is the container itself, named
   * once in {@code targetNodeIds}. The branch's cardinality is checked against the container's
   * existing children read off the base graph, not against anything in the capture: an attach
   * names one branch, never the siblings it joins, so there is nothing in the capture for a
   * cardinality count to double as. For the same reason a repeated role that the catalog orders
   * must carry an explicit {@code order} -- {@link #assembleContainer} can number a brand-new
   * container's branches by the position they arrive in because the whole set is in one capture;
   * here the existing siblings are not, so there is no position to count from.
   */
  private static ChainPlanGraph assembleAttachBranch(
      ChainPlanGraph base,
      Map<String, ChainPlanNode> baseById,
      ChainEditSubgraph capture,
      Set<String> targets,
      CatalogElementDescriptorCache descriptors) {
    if (capture.containerType() != null && !capture.containerType().isBlank()) {
      throw correctable(
          "an attach capture joins a container the chain already has, and names no new one, but"
              + " this one names '"
              + capture.containerType()
              + "'");
    }
    if (capture.branches().size() != 1) {
      throw correctable("an attach capture carries exactly one branch");
    }
    if (targets.size() != 1) {
      throw unsatisfiable("attach address names more than one container");
    }
    String containerNodeId = targets.iterator().next();
    String containerType = baseById.get(containerNodeId).type();
    CatalogElementDescriptor container = elementDescriptor(containerType, descriptors);
    if (!container.container()) {
      throw correctable(
          "'" + containerNodeId + "' (type '" + containerType + "') is not a container");
    }

    ChainEditSubgraphBranch branch = capture.branches().get(0);
    String childType = required(branch.childType(), "capture names a branch without a type");
    if (!container.allowedChildren().isEmpty()) {
      CatalogChildQuantity quantity = container.allowedChildren().get(childType);
      if (quantity == null) {
        throw correctable(
            "container '"
                + containerType
                + "' does not allow a branch of type '"
                + childType
                + "'");
      }
      int existingCount = existingChildrenOfType(base, containerNodeId, childType);
      requireWithinBounds(containerType, childType, existingCount + 1, quantity);
      if (existingCount > 0
          && elementDescriptor(childType, descriptors).ordered()
          && branch.order() == null) {
        throw correctable(
            "'"
                + childType
                + "' already has a sibling under '"
                + containerNodeId
                + "'; set order to place this branch among them");
      }
    }

    Set<String> reserved = new LinkedHashSet<>(baseById.keySet());
    Map<String, ChainEditSubgraphElement> newElements = newElements(capture, reserved);
    String branchNodeId = reserveId(childType, reserved);
    Map<ChainEditSubgraphBranch, String> branchNodeIds = Map.of(branch, branchNodeId);

    List<ChainPlanNode> nodes = new ArrayList<>(base.nodes());
    nodes.add(
        new ChainPlanNode(
            branchNodeId,
            childType,
            branch.label(),
            containerNodeId,
            branch.order(),
            branchProperties(branch, descriptors, branch.order())));
    for (ChainEditSubgraphElement element : branch.body().elements()) {
      nodes.add(
          new ChainPlanNode(
              element.nodeId(), element.type(), element.label(), branchNodeId, null, List.of()));
    }

    List<ChainPlanEdge> edges = new ArrayList<>(baseEdges(base));
    edges.addAll(bodyEdges(capture, branchNodeIds, newElements, base));

    ChainPlanGraph assembled =
        new ChainPlanGraph(
            base.schemaVersion(), base.chain(), List.copyOf(nodes), List.copyOf(edges));
    runDescriptorPreflight(assembled, base, descriptors);
    return assembled;
  }

  private static int existingChildrenOfType(ChainPlanGraph base, String parentId, String childType) {
    int count = 0;
    for (ChainPlanNode node : base.nodes()) {
      if (node != null && parentId.equals(node.parentNodeId()) && childType.equals(node.type())) {
        count++;
      }
    }
    return count;
  }

  /**
   * The graph an insertion proposes: the imported chain, unchanged, plus the captured body spliced
   * into the address the intent already resolved.
   *
   * <p>Nothing moves. The preceding and, when named, the following address element keep their type,
   * label, order, properties, and parent exactly as imported; only the edge between them is
   * replaced, by an edge into the body's entry and one out of its exit. A single named address
   * element is followed by whichever element the base graph gives it as its one successor —
   * {@link ChainEditIntentResolver} already turned a choice among several into a question before
   * this capture was requested, so more than one here means that step was skipped, not that this
   * capture can fix it.
   */
  private static ChainPlanGraph assembleInsertion(
      ChainPlanGraph base,
      Map<String, ChainPlanNode> baseById,
      ChainEditSubgraph capture,
      ChainEditIntent intent,
      CatalogElementDescriptorCache descriptors) {
    if (capture.containerType() != null && !capture.containerType().isBlank()) {
      throw correctable(
          "an insertion capture names no container, and this one names '"
              + capture.containerType()
              + "'");
    }
    if (!capture.branches().isEmpty()) {
      throw correctable("an insertion capture carries a single body, not branches");
    }
    ChainEditSubgraphBody body = capture.body();
    if (body == null || body.isEmpty()) {
      throw correctable("capture creates no elements to insert");
    }

    List<String> targetIds = intent.targetNodeIds();
    if (targetIds.isEmpty()) {
      throw unsatisfiable("insertion address names no element");
    }
    String preceding = targetIds.get(0);
    String following = targetIds.size() > 1 ? targetIds.get(1) : soleSuccessorOrNull(base, preceding);
    List<String> addressIds = following == null ? List.of(preceding) : List.of(preceding, following);
    String scope = commonParent(addressIds, baseById);

    Set<String> reserved = new LinkedHashSet<>(baseById.keySet());
    Map<String, ChainEditSubgraphElement> newElements = new LinkedHashMap<>();
    for (ChainEditSubgraphElement element : body.elements()) {
      registerElement(element, newElements, reserved);
    }

    List<ChainPlanNode> nodes = new ArrayList<>(base.nodes());
    for (ChainEditSubgraphElement element : body.elements()) {
      nodes.add(
          new ChainPlanNode(element.nodeId(), element.type(), element.label(), scope, null, List.of()));
    }
    Map<String, ChainPlanNode> assembledById = new LinkedHashMap<>();
    nodes.forEach(node -> assembledById.put(node.nodeId(), node));

    Set<String> edgeIds = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      edgeIds.add(existing.edgeId());
    }
    List<ChainPlanEdge> bodyEdges = insertionBodyEdges(body, scope, edgeIds);
    ChainEditBoundaryWiring.SubgraphEnds ends =
        ChainEditBoundaryWiring.deriveSubgraphEnds(newElements.keySet(), bodyEdges, assembledById);
    if (ends.entry() == null || ends.exit() == null) {
      throw correctable("capture body does not connect its elements into a single linked run");
    }

    List<ChainPlanEdge> edges = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      boolean isAddressEdge =
          preceding.equals(existing.fromNodeId()) && Objects.equals(following, existing.toNodeId());
      if (isAddressEdge) {
        continue;
      }
      if (connections.add(connectionKey(existing.fromNodeId(), existing.toNodeId()))) {
        edges.add(existing);
      }
    }
    edges.addAll(bodyEdges);
    edges.add(
        new ChainPlanEdge(
            reserveId(preceding + "-to-" + ends.entry(), edgeIds), preceding, ends.entry(), scope));
    if (following != null) {
      edges.add(
          new ChainPlanEdge(
              reserveId(ends.exit() + "-to-" + following, edgeIds), ends.exit(), following, scope));
    }

    ChainPlanGraph assembled =
        new ChainPlanGraph(
            base.schemaVersion(), base.chain(), List.copyOf(nodes), List.copyOf(edges));
    runDescriptorPreflight(assembled, base, descriptors);
    return assembled;
  }

  /**
   * The graph a replacement proposes: the imported chain with {@link ChainEditIntent#targetNodeIds()}
   * removed and the captured body spliced into their place.
   *
   * <p>A replacement is an insertion whose address is deleted rather than kept, so it shares the
   * body-only capture shape {@link #assembleInsertion} checks and reuses
   * {@link ChainEditBoundaryWiring#rewireReplacedEndpoint} for the connections the removed elements
   * leave behind: incoming hops attach to the body's entry, outgoing hops leave from its exit, and a
   * hop whose both ends were removed is dropped because it lived inside the replaced elements, not
   * among their neighbours. The removed elements are never named in the capture -- the intent
   * already names them, and a capture that restated them would have nowhere to put a value the
   * intent has not asked for.
   */
  private static ChainPlanGraph assembleReplacement(
      ChainPlanGraph base,
      Map<String, ChainPlanNode> baseById,
      ChainEditSubgraph capture,
      ChainEditIntent intent,
      CatalogElementDescriptorCache descriptors) {
    if (capture.containerType() != null && !capture.containerType().isBlank()) {
      throw correctable(
          "a replacement capture names no container, and this one names '"
              + capture.containerType()
              + "'");
    }
    if (!capture.branches().isEmpty()) {
      throw correctable("a replacement capture carries a single body, not branches");
    }
    ChainEditSubgraphBody body = capture.body();
    if (body == null || body.isEmpty()) {
      throw correctable("capture creates no elements to put in the replaced element's place");
    }

    List<String> replacedIds = intent.targetNodeIds();
    if (replacedIds.isEmpty()) {
      throw unsatisfiable("replacement names no element to replace");
    }
    Set<String> replaced = new LinkedHashSet<>(replacedIds);
    String scope = commonParent(replaced, baseById);

    Set<String> reserved = new LinkedHashSet<>(baseById.keySet());
    Map<String, ChainEditSubgraphElement> newElements = new LinkedHashMap<>();
    for (ChainEditSubgraphElement element : body.elements()) {
      registerElement(element, newElements, reserved);
    }

    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode existing : base.nodes()) {
      if (!replaced.contains(existing.nodeId())) {
        nodes.add(existing);
      }
    }
    for (ChainEditSubgraphElement element : body.elements()) {
      nodes.add(
          new ChainPlanNode(element.nodeId(), element.type(), element.label(), scope, null, List.of()));
    }
    Map<String, ChainPlanNode> assembledById = new LinkedHashMap<>();
    nodes.forEach(node -> assembledById.put(node.nodeId(), node));

    Set<String> edgeIds = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      edgeIds.add(existing.edgeId());
    }
    List<ChainPlanEdge> bodyEdges = insertionBodyEdges(body, scope, edgeIds);
    ChainEditBoundaryWiring.SubgraphEnds ends =
        ChainEditBoundaryWiring.deriveSubgraphEnds(newElements.keySet(), bodyEdges, assembledById);
    if (ends.entry() == null || ends.exit() == null) {
      throw correctable("capture body does not connect its elements into a single linked run");
    }

    List<ChainPlanEdge> edges = new ArrayList<>();
    Set<String> connections = new LinkedHashSet<>();
    Set<String> availableNodeIds = assembledById.keySet();
    for (ChainPlanEdge existing : baseEdges(base)) {
      boolean touchesReplaced =
          replaced.contains(existing.fromNodeId()) || replaced.contains(existing.toNodeId());
      ChainPlanEdge kept =
          touchesReplaced
              ? ChainEditBoundaryWiring.rewireReplacedEndpoint(existing, replaced, ends, availableNodeIds)
              : existing;
      if (kept != null && connections.add(connectionKey(kept))) {
        edges.add(kept);
      }
    }
    edges.addAll(bodyEdges);

    ChainPlanGraph assembled =
        new ChainPlanGraph(
            base.schemaVersion(), base.chain(), List.copyOf(nodes), List.copyOf(edges));
    runDescriptorPreflight(assembled, base, descriptors);
    return assembled;
  }

  /**
   * The element base graph names as the sole successor of a preceding address element named alone,
   * or {@code null} when that element has none.
   *
   * <p>More than one successor is a defect the reader was supposed to be asked about before the
   * capture was requested, so it is reported {@link #unsatisfiable unsatisfiable}: naming a
   * different element in the capture cannot fix an address the intent itself left ambiguous.
   */
  private static String soleSuccessorOrNull(ChainPlanGraph base, String precedingId) {
    List<String> successors = new ArrayList<>();
    for (ChainPlanEdge edge : baseEdges(base)) {
      if (precedingId.equals(edge.fromNodeId())
          && edge.toNodeId() != null
          && !successors.contains(edge.toNodeId())) {
        successors.add(edge.toNodeId());
      }
    }
    if (successors.size() > 1) {
      throw unsatisfiable(
          "'"
              + precedingId
              + "' has more than one successor; the insertion address must name which one the"
              + " new elements precede");
    }
    return successors.isEmpty() ? null : successors.get(0);
  }

  /**
   * Connections inside an insertion's single body, scoped to the address's own parent.
   *
   * <p>A connection reaching outside the body is refused rather than dropped, the same rule a
   * branch's own connections follow: an insertion body may only wire the elements it creates to
   * each other, never to the address it splices into.
   */
  private static List<ChainPlanEdge> insertionBodyEdges(
      ChainEditSubgraphBody body, String scope, Set<String> edgeIds) {
    Set<String> withinBody = new LinkedHashSet<>();
    for (ChainEditSubgraphElement element : body.elements()) {
      withinBody.add(element.nodeId());
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainEditSubgraphConnection connection : body.connections()) {
      String from = requireWithinInsertionBody(connection.fromNodeId(), withinBody);
      String to = requireWithinInsertionBody(connection.toNodeId(), withinBody);
      edges.add(new ChainPlanEdge(reserveId(from + "-to-" + to, edgeIds), from, to, scope));
    }
    return List.copyOf(edges);
  }

  private static String requireWithinInsertionBody(String nodeId, Set<String> withinBody) {
    if (nodeId == null || nodeId.isBlank()) {
      throw correctable("capture connects an inserted element to nothing");
    }
    if (!withinBody.contains(nodeId)) {
      throw correctable("capture connects '" + nodeId + "', which this insertion does not create");
    }
    return nodeId;
  }

  /**
   * The branch each named element moves into.
   *
   * <p>Checked against the intent rather than trusted: the edit already knows which elements it
   * wraps, and a capture that moves one more encloses an element nobody approved. A capture that
   * moves one fewer leaves the reader with a wrapper around less than they asked for, so both
   * directions are refused while the generator can still correct them.
   *
   * <p>Because the union across the branches has to be exactly the intent's targets, whether the
   * moved elements form a connected run is a property of those targets. That question belongs to
   * the reader, not to the generator, and {@link ChainEditIntentResolver} asks it before any
   * capture is requested. Repeating the check here would only refuse a capture that cannot be
   * corrected: the missing element is one the intent does not name, so no capture may move it.
   */
  private static Map<String, ChainEditSubgraphBranch> movedElements(
      ChainEditSubgraph capture, Map<String, ChainPlanNode> baseById, Set<String> targets) {
    Map<String, ChainEditSubgraphBranch> branchOfMovedId = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      for (String nodeId : branch.moveExisting()) {
        if (nodeId == null || nodeId.isBlank()) {
          throw correctable("capture moves an element without naming it");
        }
        if (!baseById.containsKey(nodeId)) {
          throw correctable("capture moves '" + nodeId + "', which the chain does not have");
        }
        if (!targets.contains(nodeId)) {
          throw correctable("capture moves '" + nodeId + "', which this edit does not name");
        }
        if (branchOfMovedId.put(nodeId, branch) != null) {
          throw correctable("capture moves '" + nodeId + "' into more than one branch");
        }
      }
    }
    if (!branchOfMovedId.keySet().containsAll(targets)) {
      Set<String> left = new LinkedHashSet<>(targets);
      left.removeAll(branchOfMovedId.keySet());
      throw correctable("capture leaves out the elements this edit names: " + left);
    }
    return branchOfMovedId;
  }

  /**
   * Checks branch shape against the container's own catalog descriptor before a single node is
   * minted, so a capture that misdescribes a container is refused for the catalog rule it broke,
   * not for whatever shape the defect happens to take once the graph is built.
   *
   * <p>Every bound comes from {@code descriptors}: a child type absent from {@link
   * CatalogElementDescriptor#allowedChildren()} is unknown to the container, a branch count is
   * checked against the matching {@link CatalogChildQuantity}, and a repeated role — more than one
   * branch of the same child type — must carry the property that tells it from its sibling, plus an
   * order when that child type is itself {@link CatalogElementDescriptor#ordered()}. Ordering is a
   * property of the role, not the container: {@code catch-2} is ordered and {@code try-catch-finally-2}
   * is not, the same way {@code if} is ordered under an unordered {@code condition}. No container
   * type is named in this method beyond that: two try branches fail and two catch branches do not
   * because {@code try-2} and {@code catch-2} carry different quantities in the catalog, not
   * because of anything written here about either type.
   */
  private static void validateBranches(
      String containerType,
      CatalogElementDescriptor container,
      List<ChainEditSubgraphBranch> branches,
      CatalogElementDescriptorCache descriptors) {
    Map<String, List<ChainEditSubgraphBranch>> byChildType = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : branches) {
      String childType = required(branch.childType(), "capture names a branch without a type");
      byChildType.computeIfAbsent(childType, key -> new ArrayList<>()).add(branch);
    }
    if (!container.allowedChildren().isEmpty()) {
      for (String childType : byChildType.keySet()) {
        if (!container.allowedChildren().containsKey(childType)) {
          throw correctable(
              "container '"
                  + containerType
                  + "' does not allow a branch of type '"
                  + childType
                  + "'");
        }
      }
      for (Map.Entry<String, CatalogChildQuantity> allowed :
          container.allowedChildren().entrySet()) {
        int count = byChildType.getOrDefault(allowed.getKey(), List.of()).size();
        requireWithinBounds(containerType, allowed.getKey(), count, allowed.getValue());
      }
    }
    for (Map.Entry<String, List<ChainEditSubgraphBranch>> repeated : byChildType.entrySet()) {
      if (repeated.getValue().size() < 2) {
        continue;
      }
      requireDistinguished(containerType, repeated.getKey(), repeated.getValue());
      if (elementDescriptor(repeated.getKey(), descriptors).ordered()) {
        requireOrdered(repeated.getKey(), repeated.getValue());
      }
    }
  }

  /**
   * Branches in priority order rather than in the order the capture listed them.
   *
   * <p>Which role comes before which — try before catch before finally — is the container's shape
   * and keeps the order its child type first appears in. Only siblings of one repeated role are
   * reordered, by {@link ChainEditSubgraphBranch#order()}, and only when that role is itself {@link
   * CatalogElementDescriptor#ordered()}: a role the catalog does not order is left exactly as
   * captured, since nothing requires an order to be set on it.
   */
  private static List<ChainEditSubgraphBranch> orderBranches(
      CatalogElementDescriptorCache descriptors, List<ChainEditSubgraphBranch> branches) {
    Map<String, Integer> roleAppearanceOrder = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : branches) {
      roleAppearanceOrder.putIfAbsent(branch.childType(), roleAppearanceOrder.size());
    }
    List<ChainEditSubgraphBranch> ordered = new ArrayList<>(branches);
    ordered.sort(
        Comparator.<ChainEditSubgraphBranch>comparingInt(
                branch -> roleAppearanceOrder.get(branch.childType()))
            .thenComparingInt(
                branch ->
                    elementDescriptor(branch.childType(), descriptors).ordered()
                            && branch.order() != null
                        ? branch.order()
                        : 0));
    return ordered;
  }

  /**
   * The branch's distinguishing property, plus the priority the catalog orders siblings by.
   *
   * <p>{@link ChainPlanNode#order()} is Java's own bookkeeping and never reaches the catalog; the
   * property named by {@link CatalogElementDescriptor#priorityProperty()} is what the catalog's
   * ordering service renumbers siblings from, so the capture's order is translated into that
   * property here rather than left for a materializer that does not read {@code order()}. Only a
   * branch whose own child type is {@link CatalogElementDescriptor#ordered()} gets the property —
   * {@code catch-2} is, {@code try-2} is not — since an order set on a role the catalog does not
   * order names nothing the catalog would read.
   *
   * <p>{@code order} is the capture's own value when it set one, and the branch's position among
   * its same-type siblings otherwise. A capture that names one catch has nothing to order it
   * against, so requiring the number from the generator would fail the edit over a value the
   * position already gives. Where several branches share a role the capture must still say which
   * comes first, and validation refuses one that does not.
   */
  private static List<PlanProperty> branchProperties(
      ChainEditSubgraphBranch branch, CatalogElementDescriptorCache descriptors, Integer order) {
    if (order == null || !elementDescriptor(branch.childType(), descriptors).ordered()) {
      return branch.properties();
    }
    String priorityProperty = elementDescriptor(branch.childType(), descriptors).priorityProperty();
    List<PlanProperty> properties = new ArrayList<>(branch.properties());
    properties.removeIf(property -> priorityProperty.equals(property.key()));
    properties.add(new PlanProperty(priorityProperty, String.valueOf(order)));
    return List.copyOf(properties);
  }

  /** The live catalog descriptor for a container or a branch's child type, by its type name. */
  private static CatalogElementDescriptor elementDescriptor(
      String type, CatalogElementDescriptorCache descriptors) {
    try {
      return descriptors.require(type);
    } catch (CatalogElementDescriptorException e) {
      throw correctable(e.getMessage());
    }
  }

  private static void requireWithinBounds(
      String containerType, String childType, int count, CatalogChildQuantity quantity) {
    int minimum = quantity.minimum();
    Integer maximum = quantity.maximum();
    if (count < minimum) {
      throw correctable(
          "container '"
              + containerType
              + "' has "
              + count
              + branchWord(count)
              + " of type '"
              + childType
              + "'; the catalog requires at least "
              + minimum);
    }
    if (maximum != null && count > maximum) {
      throw correctable(
          "container '"
              + containerType
              + "' has "
              + count
              + branchWord(count)
              + " of type '"
              + childType
              + "'; the catalog allows at most "
              + maximum);
    }
  }

  /** A repeated role needs its own value for whichever property tells it from its sibling. */
  private static void requireDistinguished(
      String containerType, String childType, List<ChainEditSubgraphBranch> repeated) {
    for (ChainEditSubgraphBranch branch : repeated) {
      if (branch.properties().isEmpty()) {
        throw correctable(
            "container '"
                + containerType
                + "' has more than one branch of type '"
                + childType
                + "', and one of them carries no property to tell it from its sibling");
      }
    }
  }

  /** A repeated, ordered role needs its own position among its siblings. */
  private static void requireOrdered(String childType, List<ChainEditSubgraphBranch> repeated) {
    for (ChainEditSubgraphBranch branch : repeated) {
      if (branch.order() == null) {
        throw correctable(
            "'"
                + childType
                + "' is ordered and there is more than one branch of that type, and one of them"
                + " carries no order");
      }
    }
  }

  private static String branchWord(int count) {
    return count == 1 ? " branch" : " branches";
  }

  /**
   * Runs the assembled graph through the same descriptor check a catalog write would fail on, so a
   * defect branch validation does not reach — a nested container still missing its mandatory
   * content, for one — is reported in this turn instead of after the reader approves a card.
   *
   * <p>{@link ChildlessOptionalContainerPruner} runs first, exactly as it would before the eventual
   * catalog write, so a branch the capture left with neither a moved nor a new element is dropped
   * before the check rather than failed by it. Only a branch this edit would create is ever eligible:
   * pruning is scoped to nodes absent from {@code base}, so an existing empty container is untouched.
   */
  private static void runDescriptorPreflight(
      ChainPlanGraph assembled, ChainPlanGraph base, CatalogElementDescriptorCache descriptors) {
    try {
      ChainPlanGraph pruned = ChildlessOptionalContainerPruner.prune(assembled, base, descriptors);
      new DesiredGraphDescriptorPreflight().validate(pruned, base, descriptors);
    } catch (DesiredGraphDescriptorPreflightException e) {
      throw correctable(e.getMessage());
    }
  }

  private static Map<String, ChainEditSubgraphElement> newElements(
      ChainEditSubgraph capture, Set<String> reserved) {
    Map<String, ChainEditSubgraphElement> byId = new LinkedHashMap<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      for (ChainEditSubgraphElement element : branch.body().elements()) {
        registerElement(element, byId, reserved);
      }
    }
    reserved.addAll(byId.keySet());
    return byId;
  }

  /**
   * Records one new element under its id, refusing a collision with the chain, a sibling body, or
   * an id this capture already used elsewhere. Shared by a wrap's per-branch bodies and an
   * insertion's single body, which differ in how many bodies they have but not in this rule.
   */
  private static void registerElement(
      ChainEditSubgraphElement element,
      Map<String, ChainEditSubgraphElement> byId,
      Set<String> reserved) {
    String nodeId = required(element.nodeId(), "capture creates an element without an id");
    if (reserved.contains(nodeId)) {
      throw correctable("capture creates '" + nodeId + "', an id the chain already uses");
    }
    if (byId.put(nodeId, element) != null) {
      throw correctable("capture creates '" + nodeId + "' twice");
    }
    required(element.type(), "capture creates '" + nodeId + "' without a type");
  }

  /**
   * Connections inside the branches, scoped to the branch that declared them.
   *
   * <p>A connection reaching outside its own body is refused rather than dropped. Branches do not
   * connect to each other, and a capture that wires one to another has described a flow the reader
   * would not recognize from their request.
   */
  private static List<ChainPlanEdge> bodyEdges(
      ChainEditSubgraph capture,
      Map<ChainEditSubgraphBranch, String> branchNodeIds,
      Map<String, ChainEditSubgraphElement> newElements,
      ChainPlanGraph base) {
    Set<String> edgeIds = new LinkedHashSet<>();
    for (ChainPlanEdge existing : baseEdges(base)) {
      edgeIds.add(existing.edgeId());
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainEditSubgraphBranch branch : capture.branches()) {
      Set<String> withinBranch = new LinkedHashSet<>();
      for (ChainEditSubgraphElement element : branch.body().elements()) {
        withinBranch.add(element.nodeId());
      }
      for (ChainEditSubgraphConnection connection : branch.body().connections()) {
        String from = requireWithinBranch(connection.fromNodeId(), withinBranch, newElements);
        String to = requireWithinBranch(connection.toNodeId(), withinBranch, newElements);
        edges.add(
            new ChainPlanEdge(
                reserveId(from + "-to-" + to, edgeIds), from, to, branchNodeIds.get(branch)));
      }
    }
    return List.copyOf(edges);
  }

  private static String requireWithinBranch(
      String nodeId, Set<String> withinBranch, Map<String, ChainEditSubgraphElement> newElements) {
    if (nodeId == null || nodeId.isBlank()) {
      throw correctable("capture connects a branch element to nothing");
    }
    if (!withinBranch.contains(nodeId)) {
      String reason =
          newElements.containsKey(nodeId)
              ? "which another branch creates"
              : "which this branch does not create";
      throw correctable("capture connects '" + nodeId + "', " + reason);
    }
    return nodeId;
  }

  /**
   * The container the wrapper itself lands in, taken from the elements moving into it.
   *
   * <p>Wrapping an element that already sits inside a container leaves the wrapper in that
   * container. Elements from different containers share none, so the wrapper goes to chain root and
   * the reader sees where it landed before approving.
   */
  private static String commonParent(
      Collection<String> movedIds, Map<String, ChainPlanNode> baseById) {
    String shared = null;
    for (String nodeId : movedIds) {
      String parent = baseById.get(nodeId).parentNodeId();
      if (parent == null || parent.isBlank()) {
        return null;
      }
      if (shared != null && !shared.equals(parent)) {
        return null;
      }
      shared = parent;
    }
    return shared;
  }

  private static ChainPlanNode reparented(ChainPlanNode existing, String parentNodeId) {
    return new ChainPlanNode(
        existing.nodeId(),
        existing.type(),
        existing.label(),
        parentNodeId,
        existing.order(),
        existing.properties());
  }

  /** An id no element of the chain and no other part of this capture holds. */
  private static String reserveId(String stem, Set<String> reserved) {
    int index = 1;
    String candidate = stem + "-" + index;
    while (!reserved.add(candidate)) {
      index++;
      candidate = stem + "-" + index;
    }
    return candidate;
  }

  private static Map<String, ChainPlanNode> baseNodesById(ChainPlanGraph base) {
    if (base.nodes() == null || base.nodes().isEmpty()) {
      throw unsatisfiable("the edited chain has no elements");
    }
    Map<String, ChainPlanNode> byId = new LinkedHashMap<>();
    for (ChainPlanNode node : base.nodes()) {
      byId.put(node.nodeId(), node);
    }
    return byId;
  }

  private static List<ChainPlanEdge> baseEdges(ChainPlanGraph base) {
    return base.edges() == null ? List.of() : base.edges();
  }

  private static String connectionKey(ChainPlanEdge edge) {
    return connectionKey(edge.fromNodeId(), edge.toNodeId());
  }

  private static String connectionKey(String fromNodeId, String toNodeId) {
    return fromNodeId + " " + toNodeId;
  }

  private static String required(String value, String message) {
    if (value == null || value.isBlank()) {
      throw correctable(message);
    }
    return value;
  }

  private static ChainEditScopeException correctable(String message) {
    return new ChainEditScopeException(captureMessage(message), false);
  }

  /**
   * A refusal the generator cannot answer, because the intent names something the edited chain does
   * not hold. Asking for the capture again cannot change that.
   */
  private static ChainEditScopeException unsatisfiable(String message) {
    return new ChainEditScopeException(captureMessage(message), true);
  }

  private static String captureMessage(String message) {
    return "edit structure does not describe the approved change: " + message;
  }
}

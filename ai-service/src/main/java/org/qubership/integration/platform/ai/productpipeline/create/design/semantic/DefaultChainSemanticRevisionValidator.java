package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ContainmentRole;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.TopologyContract;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/**
 * One validation pass over a semantic revision. Wrong input fails closed; values are not
 * rewritten.
 */
@ApplicationScoped
public class DefaultChainSemanticRevisionValidator implements ChainSemanticRevisionValidator {

  private enum Color {
    WHITE,
    GRAY,
    BLACK
  }

  @Override
  public void validate(ChainSemanticRevision revision, CompilerContract contract) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(contract, "contract");
    List<String> errors = new ArrayList<>();
    validateVersions(revision, contract, errors);
    Index index = Index.build(revision, errors);
    validateEntries(revision, index, errors);
    validateNodes(revision, contract, errors);
    validateEdges(revision, index, errors);
    validateExecutionDag(revision, index, errors);
    validateReachability(revision, index, errors);
    validateContainment(revision, contract, index, errors);
    validateRegions(revision, contract, index, errors);
    validateHiddenJoins(revision, index, errors);
    validateMappings(revision, index, errors);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid chain semantic revision:\n- " + String.join("\n- ", errors));
    }
  }

  private static void validateVersions(
      ChainSemanticRevision revision, CompilerContract contract, List<String> errors) {
    if (!ChainSemanticRevision.CURRENT_SCHEMA_VERSION.equals(revision.schemaVersion())) {
      errors.add("Unsupported semantic schema version: " + revision.schemaVersion());
    }
    if (!revision.schemaVersion().equals(contract.semanticSchemaVersion())) {
      errors.add(
          "semantic schema version '"
              + revision.schemaVersion()
              + "' does not match compiler contract '"
              + contract.semanticSchemaVersion()
              + "'");
    }
    if (!revision.compilerContractVersion().equals(contract.contractVersion())) {
      errors.add(
          "compiler contract version '"
              + revision.compilerContractVersion()
              + "' does not match contract '"
              + contract.contractVersion()
              + "'");
    }
  }

  private static void validateEntries(
      ChainSemanticRevision revision, Index index, List<String> errors) {
    if (revision.entryPoints().isEmpty()) {
      errors.add("entryPoints must contain at least one entry");
    }
    for (SemanticEntryPoint entry : revision.entryPoints()) {
      SemanticNode trigger = index.nodes.get(entry.triggerNodeId());
      if (trigger == null) {
        errors.add(
            "Entry point '"
                + entry.entryPointId()
                + "' triggerNodeId '"
                + entry.triggerNodeId()
                + "' is missing");
      } else if (!(trigger instanceof SemanticNode.Trigger)) {
        errors.add(
            "Entry point '"
                + entry.entryPointId()
                + "' triggerNodeId '"
                + entry.triggerNodeId()
                + "' is not a TRIGGER");
      }
      if (!index.nodes.containsKey(entry.initialTargetNodeId())) {
        errors.add(
            "Entry point '"
                + entry.entryPointId()
                + "' initialTargetNodeId '"
                + entry.initialTargetNodeId()
                + "' is missing");
      }
    }
  }

  private static void validateNodes(
      ChainSemanticRevision revision, CompilerContract contract, List<String> errors) {
    Set<String> serviceCallIds = new HashSet<>();
    for (SemanticNode node : revision.nodes()) {
      String elementType = elementType(node);
      if (!contract.elements().containsKey(elementType)) {
        errors.add("Unknown contract element: " + elementType);
      }
      TopologyContract topology = contract.topology().get(elementType);
      if (topology != null && !topology.supported()) {
        errors.add("Unsupported topology: " + elementType);
      }
      if (node instanceof SemanticNode.ServiceCall serviceCall
          && !serviceCallIds.add(serviceCall.serviceCallId())) {
        errors.add("Duplicate serviceCallId: " + serviceCall.serviceCallId());
      }
    }
  }

  private static void validateEdges(ChainSemanticRevision revision, Index index, List<String> errors) {
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      if (!index.nodes.containsKey(edge.sourceNodeId())) {
        errors.add(
            "Execution edge '"
                + edge.edgeId()
                + "' sourceNodeId '"
                + edge.sourceNodeId()
                + "' is missing");
      }
      if (!index.nodes.containsKey(edge.targetNodeId())) {
        errors.add(
            "Execution edge '"
                + edge.edgeId()
                + "' targetNodeId '"
                + edge.targetNodeId()
                + "' is missing");
      }
      if (edge.regionId() != null && !index.regions.containsKey(edge.regionId())) {
        errors.add(
            "Execution edge '"
                + edge.edgeId()
                + "' regionId '"
                + edge.regionId()
                + "' is missing");
      }
      if (edge.route() == null) {
        errors.add("Execution edge '" + edge.edgeId() + "' is missing a route");
      } else {
        validateRouteRefs(edge, index, errors);
      }
    }
  }

  private static void validateRouteRefs(
      SemanticExecutionEdge edge, Index index, List<String> errors) {
    SemanticRoute route = edge.route();
    List<ScopedRef> refs = scopedRefs(route);
    if (refs.isEmpty()) {
      return;
    }
    if (edge.regionId() == null) {
      errors.add("Execution edge '" + edge.edgeId() + "' is missing a region");
      return;
    }
    SemanticRegion region = index.regions.get(edge.regionId());
    if (region == null) {
      return;
    }
    for (ScopedRef ref : refs) {
      if (!regionOwns(region, route, ref.id())) {
        errors.add(
            "Execution edge '"
                + edge.edgeId()
                + "' "
                + ref.field()
                + " '"
                + ref.id()
                + "' is missing from region '"
                + edge.regionId()
                + "'");
      }
    }
  }

  private static List<ScopedRef> scopedRefs(SemanticRoute route) {
    return switch (route) {
      case SemanticRoute.ConditionBranch branch ->
          List.of(new ScopedRef("branchId", branch.branchId()));
      case SemanticRoute.SplitBranch branch -> List.of(new ScopedRef("branchId", branch.branchId()));
      case SemanticRoute.CatchPath catchPath ->
          List.of(new ScopedRef("handlerId", catchPath.handlerId()));
      case SemanticRoute.Reconverge reconverge -> {
        List<ScopedRef> refs = new ArrayList<>();
        for (String branchId : reconverge.branchIds()) {
          refs.add(new ScopedRef("branchId", branchId));
        }
        yield refs;
      }
      default -> List.of();
    };
  }

  private static boolean regionOwns(SemanticRegion region, SemanticRoute route, String id) {
    return switch (route) {
      case SemanticRoute.ConditionBranch ignored ->
          region instanceof SemanticRegion.Condition condition
              && ownsConditionBranch(condition, id);
      case SemanticRoute.SplitBranch ignored ->
          region instanceof SemanticRegion.Split split && ownsSplitBranch(split, id);
      case SemanticRoute.CatchPath ignored ->
          region instanceof SemanticRegion.ErrorScope scope && ownsHandler(scope, id);
      case SemanticRoute.Reconverge ignored -> ownsReconvergeBranch(region, id);
      default -> true;
    };
  }

  private static boolean ownsConditionBranch(SemanticRegion.Condition condition, String branchId) {
    for (SemanticBranch.Condition branch : condition.branches()) {
      if (branch.branchId().equals(branchId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean ownsSplitBranch(SemanticRegion.Split split, String branchId) {
    for (SemanticBranch.Split branch : split.branches()) {
      if (branch.branchId().equals(branchId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean ownsHandler(SemanticRegion.ErrorScope scope, String handlerId) {
    for (ErrorHandler handler : scope.handlers()) {
      if (handler.handlerId().equals(handlerId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean ownsReconvergeBranch(SemanticRegion region, String branchId) {
    return switch (region) {
      case SemanticRegion.Condition condition -> ownsConditionBranch(condition, branchId);
      case SemanticRegion.Split split -> ownsSplitBranch(split, branchId);
      default -> false;
    };
  }

  private static void validateExecutionDag(
      ChainSemanticRevision revision, Index index, List<String> errors) {
    Map<String, Color> colors = new HashMap<>();
    for (SemanticNode node : revision.nodes()) {
      colors.put(node.nodeId(), Color.WHITE);
    }
    boolean cycle = false;
    for (SemanticNode node : revision.nodes()) {
      if (colors.get(node.nodeId()) == Color.WHITE
          && dfsExecution(node.nodeId(), index, colors)) {
        cycle = true;
      }
    }
    if (cycle) {
      errors.add("execution edges must form a DAG");
    }
  }

  private static boolean dfsExecution(String nodeId, Index index, Map<String, Color> colors) {
    colors.put(nodeId, Color.GRAY);
    boolean cycle = false;
    for (SemanticExecutionEdge edge : index.outgoing.getOrDefault(nodeId, List.of())) {
      String target = edge.targetNodeId();
      if (!colors.containsKey(target)) {
        continue;
      }
      Color color = colors.get(target);
      if (color == Color.WHITE) {
        cycle |= dfsExecution(target, index, colors);
      } else if (color == Color.GRAY) {
        cycle = true;
      }
    }
    colors.put(nodeId, Color.BLACK);
    return cycle;
  }

  private static void validateReachability(
      ChainSemanticRevision revision, Index index, List<String> errors) {
    Set<String> reachable = new HashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    for (SemanticEntryPoint entry : revision.entryPoints()) {
      if (index.nodes.containsKey(entry.triggerNodeId()) && reachable.add(entry.triggerNodeId())) {
        queue.add(entry.triggerNodeId());
      }
    }
    while (!queue.isEmpty()) {
      String nodeId = queue.removeFirst();
      for (SemanticExecutionEdge edge : index.outgoing.getOrDefault(nodeId, List.of())) {
        if (index.nodes.containsKey(edge.targetNodeId()) && reachable.add(edge.targetNodeId())) {
          queue.add(edge.targetNodeId());
        }
      }
    }
    for (SemanticNode node : revision.nodes()) {
      if (!reachable.contains(node.nodeId())) {
        errors.add("Node '" + node.nodeId() + "' is not reachable from any entry point");
      }
    }
  }

  private static void validateContainment(
      ChainSemanticRevision revision,
      CompilerContract contract,
      Index index,
      List<String> errors) {
    Map<String, String> parentByChild = new LinkedHashMap<>();
    Map<String, List<String>> children = new LinkedHashMap<>();
    for (SemanticContainment relation : revision.containment()) {
      if (!index.nodes.containsKey(relation.parentNodeId())) {
        errors.add("Containment parentNodeId '" + relation.parentNodeId() + "' is missing");
      }
      if (!index.nodes.containsKey(relation.childNodeId())) {
        errors.add("Containment childNodeId '" + relation.childNodeId() + "' is missing");
      }
      validateContainmentRole(relation, contract, index, errors);
      String previous = parentByChild.put(relation.childNodeId(), relation.parentNodeId());
      if (previous != null) {
        errors.add(
            "Node '" + relation.childNodeId() + "' has more than one containment parent");
      }
      children
          .computeIfAbsent(relation.parentNodeId(), ignored -> new ArrayList<>())
          .add(relation.childNodeId());
    }
    Map<String, Color> colors = new HashMap<>();
    for (SemanticNode node : revision.nodes()) {
      colors.put(node.nodeId(), Color.WHITE);
    }
    boolean cycle = false;
    for (SemanticNode node : revision.nodes()) {
      if (colors.get(node.nodeId()) == Color.WHITE
          && dfsContainment(node.nodeId(), children, colors)) {
        cycle = true;
      }
    }
    if (cycle) {
      errors.add("containment relations must form a DAG");
    }
  }

  private static void validateContainmentRole(
      SemanticContainment relation,
      CompilerContract contract,
      Index index,
      List<String> errors) {
    SemanticNode parent = index.nodes.get(relation.parentNodeId());
    if (parent == null) {
      return;
    }
    ElementContract element = contract.elements().get(elementType(parent));
    if (element == null || !element.containmentRoles().containsKey(relation.role())) {
      errors.add(
          "Containment role '"
              + relation.role()
              + "' is not allowed on parent '"
              + relation.parentNodeId()
              + "'");
    }
  }

  private static boolean dfsContainment(
      String nodeId, Map<String, List<String>> children, Map<String, Color> colors) {
    colors.put(nodeId, Color.GRAY);
    boolean cycle = false;
    for (String child : children.getOrDefault(nodeId, List.of())) {
      if (!colors.containsKey(child)) {
        continue;
      }
      Color color = colors.get(child);
      if (color == Color.WHITE) {
        cycle |= dfsContainment(child, children, colors);
      } else if (color == Color.GRAY) {
        cycle = true;
      }
    }
    colors.put(nodeId, Color.BLACK);
    return cycle;
  }

  private static void validateRegions(
      ChainSemanticRevision revision, CompilerContract contract, Index index, List<String> errors) {
    Map<String, String> ownerToRegion = new LinkedHashMap<>();
    for (SemanticRegion region : revision.regions()) {
      switch (region) {
        case SemanticRegion.Sequence sequence -> validateSequence(sequence, index, errors);
        case SemanticRegion.Condition condition ->
            validateCondition(condition, contract, index, ownerToRegion, errors);
        case SemanticRegion.Split split ->
            validateSplit(split, contract, index, ownerToRegion, errors);
        case SemanticRegion.Loop loop -> validateLoop(loop, index, ownerToRegion, errors);
        case SemanticRegion.Retry retry -> validateRetry(retry, index, ownerToRegion, errors);
        case SemanticRegion.ErrorScope scope ->
            validateErrorScope(scope, contract, index, ownerToRegion, errors);
      }
    }
  }

  private static void validateSequence(
      SemanticRegion.Sequence sequence, Index index, List<String> errors) {
    for (String memberNodeId : sequence.memberNodeIds()) {
      requireNode(memberNodeId, "Sequence '" + sequence.regionId() + "' member", index, errors);
    }
  }

  private static void validateCondition(
      SemanticRegion.Condition condition,
      CompilerContract contract,
      Index index,
      Map<String, String> ownerToRegion,
      List<String> errors) {
    claimOwner(condition.regionId(), condition.ownerNodeId(), index, ownerToRegion, errors);
    requireNode(condition.reconvergenceNodeId(), "Condition reconvergence", index, errors);
    ElementContract element = contract.elements().get("condition");
    BranchCounts counts = validateConditionBranches(condition, index, errors);
    int minIf = minRole(element, "if", 1);
    int maxElse = maxRole(element, "else", 1);
    if (counts.ifCount < minIf) {
      errors.add("condition requires at least " + minIf + " IF branch");
    }
    if (maxElse != Integer.MAX_VALUE && counts.elseCount > maxElse) {
      errors.add("condition allows at most " + maxElse + " ELSE branch");
    }
  }

  private static BranchCounts validateConditionBranches(
      SemanticRegion.Condition condition, Index index, List<String> errors) {
    int ifCount = 0;
    int elseCount = 0;
    Set<Integer> ifPriorities = new HashSet<>();
    Set<String> branchIds = new HashSet<>();
    for (SemanticBranch.Condition branch : condition.branches()) {
      if (!branchIds.add(branch.branchId())) {
        errors.add(
            "Condition '"
                + condition.regionId()
                + "' has duplicate branch id '"
                + branch.branchId()
                + "'");
      }
      requireNode(branch.entryNodeId(), "Condition branch entry", index, errors);
      for (String exitNodeId : branch.exitNodeIds()) {
        requireNode(exitNodeId, "Condition branch exit", index, errors);
      }
      if (branch.role() != ConditionBranchRole.IF) {
        elseCount++;
        continue;
      }
      ifCount++;
      if (branch.predicate() == null || branch.predicate().isBlank()) {
        errors.add(
            "Condition IF branch '" + branch.branchId() + "' requires a non-empty predicate");
      }
      if (!ifPriorities.add(branch.priority())) {
        errors.add("Condition '" + condition.regionId() + "' requires unique IF priorities");
      }
    }
    return new BranchCounts(ifCount, elseCount);
  }

  private static void validateSplit(
      SemanticRegion.Split split,
      CompilerContract contract,
      Index index,
      Map<String, String> ownerToRegion,
      List<String> errors) {
    claimOwner(split.regionId(), split.ownerNodeId(), index, ownerToRegion, errors);
    requireNode(split.reconvergenceNodeId(), "Split reconvergence", index, errors);
    String topologyKey = split.mode() == SplitMode.ASYNC ? "split-async-2" : "split-2";
    TopologyContract topology = contract.topology().get(topologyKey);
    if (topology != null && !topology.supported()) {
      errors.add("Unsupported topology: " + topologyKey);
    }
    int minimumBranches =
        topology != null && topology.minimumBranches() != null
            ? topology.minimumBranches()
            : minRole(contract.elements().get(topologyKey), splitRole(split.mode()), 1);
    if (split.branches().size() < minimumBranches) {
      errors.add(topologyKey + " requires at least " + minimumBranches + " branch");
    }
    Set<String> branchIds = new HashSet<>();
    for (SemanticBranch.Split branch : split.branches()) {
      if (!branchIds.add(branch.branchId())) {
        errors.add(
            "Split '"
                + split.regionId()
                + "' has duplicate branch id '"
                + branch.branchId()
                + "'");
      }
      requireNode(branch.entryNodeId(), "Split branch entry", index, errors);
      for (String exitNodeId : branch.exitNodeIds()) {
        requireNode(exitNodeId, "Split branch exit", index, errors);
      }
    }
  }

  private static String splitRole(SplitMode mode) {
    return mode == SplitMode.ASYNC ? "async-split-element-2" : "split-element-2";
  }

  private static void validateLoop(
      SemanticRegion.Loop loop,
      Index index,
      Map<String, String> ownerToRegion,
      List<String> errors) {
    claimOwner(loop.regionId(), loop.ownerNodeId(), index, ownerToRegion, errors);
    requireNode(loop.bodyEntryNodeId(), "Loop body entry", index, errors);
    requireNode(loop.exitNodeId(), "Loop exit", index, errors);
    for (String exitNodeId : loop.bodyExitNodeIds()) {
      requireNode(exitNodeId, "Loop body exit", index, errors);
    }
  }

  private static void validateRetry(
      SemanticRegion.Retry retry,
      Index index,
      Map<String, String> ownerToRegion,
      List<String> errors) {
    claimOwner(retry.regionId(), retry.ownerNodeId(), index, ownerToRegion, errors);
    requireNode(retry.bodyEntryNodeId(), "Retry body entry", index, errors);
    requireNode(retry.exhaustedNodeId(), "Retry exhausted", index, errors);
    for (String exitNodeId : retry.bodyExitNodeIds()) {
      requireNode(exitNodeId, "Retry body exit", index, errors);
    }
  }

  private static void validateErrorScope(
      SemanticRegion.ErrorScope scope,
      CompilerContract contract,
      Index index,
      Map<String, String> ownerToRegion,
      List<String> errors) {
    claimOwner(scope.regionId(), scope.ownerNodeId(), index, ownerToRegion, errors);
    requireNode(scope.tryEntryNodeId(), "Error-scope try entry", index, errors);
    requireNode(scope.finallyEntryNodeId(), "Error-scope finally entry", index, errors);
    for (String exitNodeId : scope.exitNodeIds()) {
      requireNode(exitNodeId, "Error-scope exit", index, errors);
    }
    int minCatch = minRole(contract.elements().get("try-catch-finally-2"), "catch-2", 1);
    if (scope.handlers().size() < minCatch) {
      errors.add("try-catch-finally-2 requires at least " + minCatch + " catch handler");
    }
    Set<String> handlerIds = new HashSet<>();
    for (ErrorHandler handler : scope.handlers()) {
      if (!handlerIds.add(handler.handlerId())) {
        errors.add(
            "Error scope '"
                + scope.regionId()
                + "' has duplicate handler id '"
                + handler.handlerId()
                + "'");
      }
      requireNode(handler.entryNodeId(), "Error handler entry", index, errors);
      for (String exitNodeId : handler.exitNodeIds()) {
        requireNode(exitNodeId, "Error handler exit", index, errors);
      }
    }
  }

  private static void validateHiddenJoins(
      ChainSemanticRevision revision, Index index, List<String> errors) {
    for (SemanticNode node : revision.nodes()) {
      List<SemanticExecutionEdge> incoming =
          index.incoming.getOrDefault(node.nodeId(), List.of());
      if (incoming.size() <= 1) {
        continue;
      }
      boolean allTriggers = true;
      boolean allReconverge = true;
      for (SemanticExecutionEdge edge : incoming) {
        SemanticNode source = index.nodes.get(edge.sourceNodeId());
        if (!(source instanceof SemanticNode.Trigger)) {
          allTriggers = false;
        }
        if (!(edge.route() instanceof SemanticRoute.Reconverge)) {
          allReconverge = false;
        }
      }
      if (!allTriggers && !allReconverge) {
        errors.add("Unsupported topology: generic-barrier at node '" + node.nodeId() + "'");
      }
    }
  }

  private static void validateMappings(
      ChainSemanticRevision revision, Index index, List<String> errors) {
    Map<String, List<SemanticExecutionEdge>> sitesByIntent = new LinkedHashMap<>();
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      if (edge.mappingId() == null || edge.mappingId().isBlank()) {
        continue;
      }
      sitesByIntent.computeIfAbsent(edge.mappingId(), ignored -> new ArrayList<>()).add(edge);
    }
    Set<String> intentIds = new HashSet<>();
    for (MappingIntent intent : revision.mappingIntents()) {
      if (!intentIds.add(intent.mappingIntentId())) {
        errors.add("Duplicate mapping intent: " + intent.mappingIntentId());
      }
      validateMappingRefs(intent, index, errors);
      validateMappingSite(
          intent.mappingIntentId(),
          sitesByIntent.getOrDefault(intent.mappingIntentId(), List.of()),
          index,
          errors);
    }
    for (Map.Entry<String, List<SemanticExecutionEdge>> entry : sitesByIntent.entrySet()) {
      if (!intentIds.contains(entry.getKey())) {
        errors.add("Unknown mapping id '" + entry.getKey() + "'");
      }
    }
  }

  private static void validateMappingRefs(
      MappingIntent intent, Index index, List<String> errors) {
    if (!index.nodes.containsKey(intent.sourceRef())
        && !index.edges.containsKey(intent.sourceRef())) {
      errors.add(
          "Mapping intent '"
              + intent.mappingIntentId()
              + "' sourceRef '"
              + intent.sourceRef()
              + "' is missing");
    }
    if (!index.nodes.containsKey(intent.targetRef())
        && !index.edges.containsKey(intent.targetRef())) {
      errors.add(
          "Mapping intent '"
              + intent.mappingIntentId()
              + "' targetRef '"
              + intent.targetRef()
              + "' is missing");
    }
  }

  private static void validateMappingSite(
      String mappingIntentId,
      List<SemanticExecutionEdge> sites,
      Index index,
      List<String> errors) {
    if (sites.isEmpty()) {
      errors.add("orphan mapping intent: " + mappingIntentId);
      return;
    }
    if (sites.size() != 1) {
      errors.add(
          "Mapping intent '" + mappingIntentId + "' does not resolve to a single-incoming site");
      return;
    }
    SemanticExecutionEdge site = sites.getFirst();
    int incoming = index.incoming.getOrDefault(site.targetNodeId(), List.of()).size();
    boolean reconverge = site.route() instanceof SemanticRoute.Reconverge;
    if (!BriefMappingValidator.isMappingEndpoint(incoming, reconverge)) {
      errors.add("Unsupported topology: generic-aggregate");
      errors.add(
          "Mapping intent '" + mappingIntentId + "' does not resolve to a single-incoming site");
    }
  }

  private static void claimOwner(
      String regionId,
      String ownerNodeId,
      Index index,
      Map<String, String> ownerToRegion,
      List<String> errors) {
    requireNode(ownerNodeId, "Region '" + regionId + "' owner", index, errors);
    String previous = ownerToRegion.put(ownerNodeId, regionId);
    if (previous != null) {
      errors.add(
          "Region owner '" + ownerNodeId + "' is already owned by region '" + previous + "'");
    }
  }

  private static void requireNode(String nodeId, String role, Index index, List<String> errors) {
    if (nodeId != null && !index.nodes.containsKey(nodeId)) {
      errors.add(role + " '" + nodeId + "' is missing");
    }
  }

  private static int minRole(ElementContract element, String role, int defaultMin) {
    if (element == null) {
      return defaultMin;
    }
    ContainmentRole containmentRole = element.containmentRoles().get(role);
    return containmentRole == null ? defaultMin : containmentRole.min();
  }

  private static int maxRole(ElementContract element, String role, int defaultMax) {
    if (element == null) {
      return defaultMax;
    }
    ContainmentRole containmentRole = element.containmentRoles().get(role);
    if (containmentRole == null) {
      return defaultMax;
    }
    return containmentRole.max() == null ? Integer.MAX_VALUE : containmentRole.max();
  }

  private static String elementType(SemanticNode node) {
    return switch (node) {
      case SemanticNode.Trigger trigger -> trigger.capabilityKey();
      case SemanticNode.ServiceCall ignored -> "service-call";
      case SemanticNode.Operation operation -> operation.elementType();
    };
  }

  private record BranchCounts(int ifCount, int elseCount) {}

  private record ScopedRef(String field, String id) {}

  private static final class Index {
    private final Map<String, SemanticNode> nodes;
    private final Map<String, SemanticExecutionEdge> edges;
    private final Map<String, SemanticRegion> regions;
    private final Map<String, List<SemanticExecutionEdge>> outgoing;
    private final Map<String, List<SemanticExecutionEdge>> incoming;

    private Index(
        Map<String, SemanticNode> nodes,
        Map<String, SemanticExecutionEdge> edges,
        Map<String, SemanticRegion> regions,
        Map<String, List<SemanticExecutionEdge>> outgoing,
        Map<String, List<SemanticExecutionEdge>> incoming) {
      this.nodes = nodes;
      this.edges = edges;
      this.regions = regions;
      this.outgoing = outgoing;
      this.incoming = incoming;
    }

    private static Index build(ChainSemanticRevision revision, List<String> errors) {
      Map<String, SemanticNode> nodes = new LinkedHashMap<>();
      for (SemanticNode node : revision.nodes()) {
        if (nodes.put(node.nodeId(), node) != null) {
          errors.add("Duplicate node id: " + node.nodeId());
        }
      }
      Map<String, SemanticEntryPoint> entries = new LinkedHashMap<>();
      for (SemanticEntryPoint entry : revision.entryPoints()) {
        if (entries.put(entry.entryPointId(), entry) != null) {
          errors.add("Duplicate entry point id: " + entry.entryPointId());
        }
      }
      Map<String, SemanticRegion> regions = new LinkedHashMap<>();
      for (SemanticRegion region : revision.regions()) {
        if (regions.put(region.regionId(), region) != null) {
          errors.add("Duplicate region id: " + region.regionId());
        }
      }
      Map<String, SemanticExecutionEdge> edges = new LinkedHashMap<>();
      Map<String, List<SemanticExecutionEdge>> outgoing = new LinkedHashMap<>();
      Map<String, List<SemanticExecutionEdge>> incoming = new LinkedHashMap<>();
      for (SemanticExecutionEdge edge : revision.executionEdges()) {
        if (edges.put(edge.edgeId(), edge) != null) {
          errors.add("Duplicate edge id: " + edge.edgeId());
        }
        outgoing.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>()).add(edge);
        incoming.computeIfAbsent(edge.targetNodeId(), ignored -> new ArrayList<>()).add(edge);
      }
      return new Index(nodes, edges, regions, outgoing, incoming);
    }
  }
}

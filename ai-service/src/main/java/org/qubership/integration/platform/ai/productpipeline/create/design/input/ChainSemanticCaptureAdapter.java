package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.mapping.LegacyStageMappingAdapter;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.mapping.MappingMechanismSelector;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticContainment;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRouteKind;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Projects a model-owned {@link ChainSemanticCapture} onto the canonical {@link
 * ChainSemanticRevision}. This is the only place where server-owned state enters a design: schema
 * and contract versions come from the {@link CompilerContract}, catalog values and mapping bodies
 * come from the approved {@link RequirementBrief}, and identifiers are derived from content.
 *
 * <p>Unknown or ambiguous references fail closed. The adapter never guesses a value and never falls
 * back to an alias. The server materializes one trigger and one service-call node per approved
 * interaction and names it after the interaction id. Captured operations must not reuse those
 * ids.
 *
 * <p>Identifiers are stable by construction. {@code edgeId} is a hash of the edge's semantic key,
 * so reordering the JSON arrays does not change it, and {@code revisionId} is a hash of the whole
 * canonical aggregate seeded with the run id, so the same run plus the same capture plus the same
 * compiler contract reproduces the same id after a retry or a restart. A changed brief, topology,
 * mapping, or contract yields a different id, which keeps a stale approval from becoming valid.
 */
@ApplicationScoped
public class ChainSemanticCaptureAdapter {

  private static final String REVISION_ID_PREFIX = "semantic-";
  private static final String EDGE_ID_PREFIX = "edge-";
  /** Unit separator between semantic-key fields. It cannot appear inside an id. */
  private static final String KEY_SEPARATOR = "\u001f";

  private final ChainSemanticCanonicalizer canonicalizer;

  @Inject
  public ChainSemanticCaptureAdapter(ChainSemanticCanonicalizer canonicalizer) {
    this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
  }

  /**
   * Builds the canonical revision for one design-input turn.
   *
   * @throws IllegalArgumentException when the capture references something the approved brief or
   *     the compiler contract does not own. The message is returned to the model verbatim.
   */
  public ChainSemanticRevision adapt(
      ChainSemanticCapture capture,
      String runId,
      RequirementBrief brief,
      CompilerContract contract) {
    Objects.requireNonNull(capture, "capture");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(contract, "contract");

    RequirementBrief authoritative = LegacyStageMappingAdapter.ensureIntents(brief);
    Set<String> factIds = factIds(authoritative);
    Map<String, RequirementEntryPoint> briefEntryPoints = briefEntryPoints(authoritative);
    Map<String, RequirementServiceCall> briefServiceCalls = briefServiceCalls(authoritative);
    List<TriggerBinding> triggerBindings = triggerBindings(briefEntryPoints);

    List<SemanticNode> nodes =
        nodes(capture, triggerBindings, briefServiceCalls, briefEntryPoints, contract, factIds);
    Set<String> nodeIds = new LinkedHashSet<>();
    for (SemanticNode node : nodes) {
      if (!nodeIds.add(node.nodeId())) {
        throw new IllegalArgumentException("Duplicate nodeId: " + node.nodeId());
      }
    }

    List<SemanticRegion> regions = regions(capture);
    Set<String> regionIds = new LinkedHashSet<>();
    for (SemanticRegion region : regions) {
      if (!regionIds.add(region.regionId())) {
        throw new IllegalArgumentException("Duplicate regionId: " + region.regionId());
      }
    }

    List<SemanticEntryPoint> entryPoints =
        entryPoints(triggerBindings, capture, nodeIds, factIds);
    List<SemanticExecutionEdge> edges = edges(capture, nodeIds, regionIds);
    requireApprovedAnchorGraph(authoritative, triggerBindings, briefServiceCalls, edges);
    List<MappingIntent> mappingIntents = mappingIntents(authoritative, edges, nodes);
    List<SemanticContainment> containment = containment(capture, nodeIds);

    ChainSemanticRevision provisional =
        new ChainSemanticRevision(
            contract.semanticSchemaVersion(),
            seedRevisionId(runId, contract),
            chainIdentity(capture, authoritative),
            contract.contractVersion(),
            entryPoints,
            nodes,
            regions,
            edges,
            containment,
            mappingIntents,
            authoritative.constraints(),
            authoritative.assumptions(),
            authoritative.citations());
    return withRevisionId(
        provisional, REVISION_ID_PREFIX + canonicalizer.sha256(provisional).substring(0, 32));
  }

  // Nodes

  /**
   * Materializes one trigger binding per approved brief entry point. {@code nodeId} equals the
   * interaction id ({@code entryPointId}).
   */
  private static List<TriggerBinding> triggerBindings(
      Map<String, RequirementEntryPoint> briefEntryPoints) {
    List<RequirementEntryPoint> approved = new ArrayList<>(briefEntryPoints.values());
    if (approved.isEmpty()) {
      throw new IllegalArgumentException("entryPoints must contain at least one entry");
    }
    List<TriggerBinding> bindings = new ArrayList<>();
    int order = 0;
    for (RequirementEntryPoint entry : approved) {
      requireText(entry.entryPointId(), "entryPointId");
      if (entry.capabilityKey().isBlank()) {
        throw new IllegalArgumentException(
            "Entry point '"
                + entry.entryPointId()
                + "' has no catalog capability in the approved requirement brief");
      }
      bindings.add(new TriggerBinding(entry, order++));
    }
    return List.copyOf(bindings);
  }

  /**
   * Trigger and service-call nodes come from the approved brief. The server names each node after
   * its interaction id. A captured operation that reuses an interaction id is rejected.
   */
  private static List<SemanticNode> nodes(
      ChainSemanticCapture capture,
      List<TriggerBinding> triggerBindings,
      Map<String, RequirementServiceCall> briefServiceCalls,
      Map<String, RequirementEntryPoint> briefEntryPoints,
      CompilerContract contract,
      Set<String> factIds) {
    List<SemanticNode> nodes = new ArrayList<>();
    Set<String> interactionIds = new LinkedHashSet<>();
    for (TriggerBinding binding : triggerBindings) {
      RequirementEntryPoint approved = binding.approved();
      String nodeId = binding.triggerNodeId();
      if (!interactionIds.add(nodeId)) {
        throw new IllegalArgumentException("Duplicate nodeId: " + nodeId);
      }
      List<String> provenance = entryPointProvenance(approved, factIds);
      nodes.add(
          new SemanticNode.Trigger(
              nodeId,
              approved.entryPointId(),
              approved.capabilityKey(),
              new SemanticProvenance(provenance)));
    }
    Set<String> triggerFactIds = triggerFactIds(briefEntryPoints.values());
    for (RequirementServiceCall approved : briefServiceCalls.values()) {
      if (!materializesServiceCallNode(approved, triggerFactIds)) {
        if (approved.catalogBinding() == null) {
          throw new IllegalArgumentException(
              "serviceCallId '"
                  + approved.serviceCallId()
                  + "' has no resolved catalog binding in the approved requirement brief."
                  + " Requirement gathering owns the binding; a design cannot supply it.");
        }
        continue;
      }
      String serviceCallId = approved.serviceCallId();
      if (approved.operation().isBlank()) {
        throw new IllegalArgumentException(
            "serviceCallId '"
                + serviceCallId
                + "' has no catalog operation in the approved requirement brief");
      }
      if (approved.catalogBinding() == null) {
        throw new IllegalArgumentException(
            "serviceCallId '"
                + serviceCallId
                + "' has no resolved catalog binding in the approved requirement brief."
                + " Requirement gathering owns the binding; a design cannot supply it.");
      }
      if (!interactionIds.add(serviceCallId)) {
        throw new IllegalArgumentException(
            "serviceCallId '" + serviceCallId + "' reuses an inbound interaction id");
      }
      List<String> provenance =
          factIds.contains(approved.sourceFactId())
              ? List.of(approved.sourceFactId())
              : List.of();
      nodes.add(
          new SemanticNode.ServiceCall(
              serviceCallId,
              serviceCallId,
              approved.operation(),
              new SemanticProvenance(provenance)));
    }
    for (ChainSemanticCapture.CapturedOperation operation : capture.operations()) {
      String nodeId = requireText(operation.nodeId(), "operation nodeId");
      if (interactionIds.contains(nodeId)) {
        throw new IllegalArgumentException(
            "Operation node '"
                + nodeId
                + "' reuses an interaction id. Do not list server-owned anchors under operations.");
      }
      String elementType =
          MappingMechanismSelector.canonicalTransformElementType(
              requireText(operation.elementType(), "elementType"));
      if (!contract.elements().containsKey(elementType)) {
        throw new IllegalArgumentException(
            "Operation node '"
                + nodeId
                + "' uses elementType '"
                + elementType
                + "', which the compiler contract does not declare");
      }
      requireFacts(operation.sourceFactIds(), factIds, "operation node '" + nodeId + "'");
      nodes.add(
          new SemanticNode.Operation(
              nodeId, elementType, new SemanticProvenance(operation.sourceFactIds())));
    }
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("The capture has no nodes");
    }
    return List.copyOf(nodes);
  }

  // Entry points

  private static List<SemanticEntryPoint> entryPoints(
      List<TriggerBinding> bindings,
      ChainSemanticCapture capture,
      Set<String> nodeIds,
      Set<String> factIds) {
    List<SemanticEntryPoint> entryPoints = new ArrayList<>();
    for (TriggerBinding binding : bindings) {
      RequirementEntryPoint approved = binding.approved();
      String triggerNodeId = binding.triggerNodeId();
      requireNode(triggerNodeId, nodeIds, "triggerNodeId");
      String initialTargetNodeId = initialTargetNodeId(triggerNodeId, capture);
      requireNode(initialTargetNodeId, nodeIds, "initialTargetNodeId");
      List<String> provenance = entryPointProvenance(approved, factIds);
      entryPoints.add(
          new SemanticEntryPoint(
              approved.entryPointId(),
              triggerNodeId,
              initialTargetNodeId,
              binding.order(),
              new SemanticProvenance(provenance),
              new SemanticEntryPoint.Presentation(null, null)));
    }
    if (entryPoints.isEmpty()) {
      throw new IllegalArgumentException("entryPoints must contain at least one entry");
    }
    return List.copyOf(entryPoints);
  }

  private static String initialTargetNodeId(String triggerNodeId, ChainSemanticCapture capture) {
    List<String> targets = new ArrayList<>();
    for (ChainSemanticCapture.CapturedEdge edge : capture.edges()) {
      if (!triggerNodeId.equals(trimmed(edge.sourceNodeId()))) {
        continue;
      }
      targets.add(requireText(edge.targetNodeId(), "initialTargetNodeId"));
    }
    if (targets.size() != 1) {
      throw new IllegalArgumentException(
          "Trigger node '"
              + triggerNodeId
              + "' must have exactly one outgoing edge so the server can derive"
              + " initialTargetNodeId");
    }
    return targets.getFirst();
  }

  private static List<String> entryPointProvenance(
      RequirementEntryPoint approved, Set<String> factIds) {
    if (!approved.sourceFactId().isBlank() && factIds.contains(approved.sourceFactId())) {
      return List.of(approved.sourceFactId());
    }
    return List.of();
  }

  // Regions

  private static List<SemanticRegion> regions(ChainSemanticCapture capture) {
    List<SemanticRegion> regions = new ArrayList<>();
    for (ChainSemanticCapture.CapturedSequenceRegion region : capture.sequenceRegions()) {
      regions.add(
          new SemanticRegion.Sequence(
              requireText(region.regionId(), "regionId"), region.memberNodeIds()));
    }
    for (ChainSemanticCapture.CapturedConditionRegion region : capture.conditionRegions()) {
      List<SemanticBranch.Condition> branches = new ArrayList<>();
      int position = 0;
      for (ChainSemanticCapture.CapturedConditionBranch branch : region.branches()) {
        branches.add(
            new SemanticBranch.Condition(
                requireText(branch.branchId(), "branchId"),
                requireValue(branch.role(), "condition branch role"),
                branch.predicate(),
                branch.priority() == null ? position : branch.priority(),
                requireText(branch.entryNodeId(), "branch entryNodeId"),
                branch.exitNodeIds()));
        position++;
      }
      regions.add(
          new SemanticRegion.Condition(
              requireText(region.regionId(), "regionId"),
              requireText(region.ownerNodeId(), "ownerNodeId"),
              branches,
              region.reconvergenceNodeId()));
    }
    for (ChainSemanticCapture.CapturedSplitRegion region : capture.splitRegions()) {
      List<SemanticBranch.Split> branches = new ArrayList<>();
      int position = 0;
      for (ChainSemanticCapture.CapturedSplitBranch branch : region.branches()) {
        branches.add(
            new SemanticBranch.Split(
                requireText(branch.branchId(), "branchId"),
                branch.order() == null ? position : branch.order(),
                requireText(branch.entryNodeId(), "branch entryNodeId"),
                branch.exitNodeIds()));
        position++;
      }
      regions.add(
          new SemanticRegion.Split(
              requireText(region.regionId(), "regionId"),
              requireText(region.ownerNodeId(), "ownerNodeId"),
              requireValue(region.mode(), "split mode"),
              branches,
              region.reconvergenceNodeId()));
    }
    for (ChainSemanticCapture.CapturedLoopRegion region : capture.loopRegions()) {
      regions.add(
          new SemanticRegion.Loop(
              requireText(region.regionId(), "regionId"),
              requireText(region.ownerNodeId(), "ownerNodeId"),
              requireText(region.bodyEntryNodeId(), "bodyEntryNodeId"),
              region.bodyExitNodeIds(),
              requireText(region.exitNodeId(), "exitNodeId"),
              new LoopPolicy(
                  requireValue(region.loopMode(), "loopMode"),
                  requireText(region.loopExpression(), "loopExpression"),
                  region.loopSafetyBound() == null ? 0 : region.loopSafetyBound())));
    }
    for (ChainSemanticCapture.CapturedRetryRegion region : capture.retryRegions()) {
      regions.add(
          new SemanticRegion.Retry(
              requireText(region.regionId(), "regionId"),
              requireText(region.ownerNodeId(), "ownerNodeId"),
              requireText(region.bodyEntryNodeId(), "bodyEntryNodeId"),
              region.bodyExitNodeIds(),
              requireText(region.exhaustedNodeId(), "exhaustedNodeId"),
              new RetryPolicy(
                  region.retryCount() == null ? 0 : region.retryCount(),
                  region.retryDelayMillis() == null ? 0 : region.retryDelayMillis())));
    }
    for (ChainSemanticCapture.CapturedErrorScopeRegion region : capture.errorScopeRegions()) {
      List<ErrorHandler> handlers = new ArrayList<>();
      for (ChainSemanticCapture.CapturedErrorHandler handler : region.handlers()) {
        handlers.add(
            new ErrorHandler(
                requireText(handler.handlerId(), "handlerId"),
                requireText(handler.exceptionClass(), "exceptionClass"),
                requireText(handler.entryNodeId(), "handler entryNodeId"),
                handler.exitNodeIds()));
      }
      regions.add(
          new SemanticRegion.ErrorScope(
              requireText(region.regionId(), "regionId"),
              requireText(region.ownerNodeId(), "ownerNodeId"),
              requireText(region.tryEntryNodeId(), "tryEntryNodeId"),
              handlers,
              region.finallyEntryNodeId(),
              region.exitNodeIds()));
    }
    return List.copyOf(regions);
  }

  // Edges

  private static List<SemanticExecutionEdge> edges(
      ChainSemanticCapture capture, Set<String> nodeIds, Set<String> regionIds) {
    record Keyed(ChainSemanticCapture.CapturedEdge edge, SemanticRoute route, String key) {}

    List<Keyed> keyed = new ArrayList<>();
    for (ChainSemanticCapture.CapturedEdge edge : capture.edges()) {
      requireNode(edge.sourceNodeId(), nodeIds, "edge sourceNodeId");
      requireNode(edge.targetNodeId(), nodeIds, "edge targetNodeId");
      if (edge.regionId() != null
          && !edge.regionId().isBlank()
          && !regionIds.contains(edge.regionId().trim())) {
        throw new IllegalArgumentException("Edge regionId '" + edge.regionId() + "' is missing");
      }
      SemanticRoute route = route(edge);
      keyed.add(new Keyed(edge, route, edgeKey(edge, route)));
    }
    keyed.sort(Comparator.comparing(Keyed::key));

    List<SemanticExecutionEdge> edges = new ArrayList<>(keyed.size());
    Map<String, Integer> ordinals = new HashMap<>();
    for (Keyed entry : keyed) {
      int ordinal = ordinals.merge(entry.key(), 1, Integer::sum) - 1;
      String edgeId =
          EDGE_ID_PREFIX
              + sha256Hex(entry.key()).substring(0, 16)
              + (ordinal == 0 ? "" : "-" + ordinal);
      edges.add(
          new SemanticExecutionEdge(
              edgeId,
              entry.edge().sourceNodeId().trim(),
              entry.edge().targetNodeId().trim(),
              blankToNull(entry.edge().regionId()),
              entry.route(),
              blankToNull(entry.edge().mappingIntentId())));
    }
    return List.copyOf(edges);
  }

  private static SemanticRoute route(ChainSemanticCapture.CapturedEdge edge) {
    SemanticRouteKind kind =
        edge.routeKind() == null ? SemanticRouteKind.SEQUENCE : edge.routeKind();
    return switch (kind) {
      case SEQUENCE -> new SemanticRoute.Sequence();
      case CONDITION_BRANCH ->
          new SemanticRoute.ConditionBranch(
              requireText(edge.branchId(), "CONDITION_BRANCH branchId"));
      case SPLIT_BRANCH ->
          new SemanticRoute.SplitBranch(requireText(edge.branchId(), "SPLIT_BRANCH branchId"));
      case RECONVERGE -> new SemanticRoute.Reconverge(edge.branchIds());
      case LOOP_BODY -> new SemanticRoute.LoopBody();
      case LOOP_EXIT -> new SemanticRoute.LoopExit();
      case RETRY_ATTEMPT -> new SemanticRoute.RetryAttempt();
      case RETRY_EXHAUSTED -> new SemanticRoute.RetryExhausted();
      case TRY_PATH -> new SemanticRoute.TryPath();
      case CATCH_PATH ->
          new SemanticRoute.CatchPath(requireText(edge.handlerId(), "CATCH_PATH handlerId"));
      case FINALLY_PATH -> new SemanticRoute.FinallyPath();
    };
  }

  /**
   * Semantic key of one edge. It holds only what makes the edge that edge, so array order never
   * reaches the derived {@code edgeId}.
   */
  private static String edgeKey(ChainSemanticCapture.CapturedEdge edge, SemanticRoute route) {
    List<String> branchIds = new ArrayList<>(sortedBranchIds(route));
    return String.join(
        KEY_SEPARATOR,
        trimmed(edge.sourceNodeId()),
        trimmed(edge.targetNodeId()),
        trimmed(edge.regionId()),
        route.kind().name(),
        trimmed(edge.branchId()),
        String.join(",", branchIds),
        trimmed(edge.handlerId()),
        trimmed(edge.mappingIntentId()));
  }

  private static List<String> sortedBranchIds(SemanticRoute route) {
    if (!(route instanceof SemanticRoute.Reconverge reconverge)) {
      return List.of();
    }
    List<String> ids = new ArrayList<>(reconverge.branchIds());
    ids.sort(Comparator.naturalOrder());
    return ids;
  }

  // Mappings

  private static List<MappingIntent> mappingIntents(
      RequirementBrief brief, List<SemanticExecutionEdge> edges, List<SemanticNode> nodes) {
    Map<String, MappingIntent> approved = new LinkedHashMap<>();
    for (MappingIntent intent : RequirementBriefProjector.collapseMappingIntents(brief)) {
      if (intent != null && !intent.mappingIntentId().isBlank()) {
        approved.put(intent.mappingIntentId(), intent);
      }
    }
    Map<String, SemanticExecutionEdge> siteByIntent = new LinkedHashMap<>();
    for (SemanticExecutionEdge edge : edges) {
      String intentId = edge.mappingId();
      if (intentId == null || intentId.isBlank()) {
        continue;
      }
      if (!approved.containsKey(intentId)) {
        throw new IllegalArgumentException(
            "mappingIntentId '" + intentId + "' is not in the approved requirement brief");
      }
      SemanticExecutionEdge previous = siteByIntent.put(intentId, edge);
      if (previous != null) {
        throw new IllegalArgumentException(
            "mappingIntentId '"
                + intentId
                + "' is placed on more than one edge. Place each mapping on exactly one edge.");
      }
    }
    Map<String, SemanticNode> nodesById = new LinkedHashMap<>();
    for (SemanticNode node : nodes) {
      nodesById.put(node.nodeId(), node);
    }
    Map<String, Integer> incoming = new HashMap<>();
    for (SemanticExecutionEdge edge : edges) {
      incoming.merge(edge.targetNodeId(), 1, Integer::sum);
    }
    List<MappingIntent> projected = new ArrayList<>();
    for (MappingIntent intent : approved.values()) {
      SemanticExecutionEdge site = siteByIntent.get(intent.mappingIntentId());
      if (site == null) {
        throw new IllegalArgumentException(
            "Mapping '"
                + intent.mappingIntentId()
                + "' from the approved brief is not placed on any edge. Set mappingIntentId on the"
                + " edge that carries it.");
      }
      requireTransformSite(intent.mappingIntentId(), site, nodesById);
      if (!BriefMappingValidator.isMappingEndpoint(
          incoming.getOrDefault(site.targetNodeId(), 0),
          site.route() instanceof SemanticRoute.Reconverge)) {
        throw new IllegalArgumentException(
            "Mapping '"
                + intent.mappingIntentId()
                + "' sits on an edge whose target node '"
                + site.targetNodeId()
                + "' has more than one incoming edge. Mapping needs a single-incoming site.");
      }
      projected.add(
          new MappingIntent(
              intent.mappingIntentId(),
              site.edgeId(),
              intent.sourcePort(),
              site.edgeId(),
              intent.targetPort(),
              intent.rules(),
              intent.implementationPreference()));
    }
    return List.copyOf(projected);
  }

  private static void requireTransformSite(
      String mappingIntentId, SemanticExecutionEdge site, Map<String, SemanticNode> nodesById) {
    if (isTransform(nodesById.get(site.sourceNodeId()))
        || isTransform(nodesById.get(site.targetNodeId()))) {
      return;
    }
    throw new IllegalArgumentException(
        "Mapping '"
            + mappingIntentId
            + "' has no adjacent "
            + MappingExecutionSite.ELEMENT_TYPE
            + " or "
            + MappingExecutionSite.SCRIPT_ELEMENT_TYPE
            + " node. Add one next to the mapped edge.");
  }

  private static boolean isTransform(SemanticNode node) {
    return node instanceof SemanticNode.Operation operation
        && (MappingExecutionSite.ELEMENT_TYPE.equals(operation.elementType())
            || MappingExecutionSite.SCRIPT_ELEMENT_TYPE.equals(operation.elementType()));
  }

  // Containment

  private static List<SemanticContainment> containment(
      ChainSemanticCapture capture, Set<String> nodeIds) {
    List<SemanticContainment> containment = new ArrayList<>();
    for (ChainSemanticCapture.CapturedContainment relation : capture.containment()) {
      requireNode(relation.parentNodeId(), nodeIds, "containment parentNodeId");
      requireNode(relation.childNodeId(), nodeIds, "containment childNodeId");
      containment.add(
          new SemanticContainment(
              relation.parentNodeId().trim(),
              relation.childNodeId().trim(),
              requireText(relation.role(), "containment role")));
    }
    return List.copyOf(containment);
  }

  // Identity

  private static String seedRevisionId(String runId, CompilerContract contract) {
    return REVISION_ID_PREFIX
        + sha256Hex(runId + KEY_SEPARATOR + contract.sha256()).substring(0, 32);
  }

  private static ChainSemanticRevision withRevisionId(
      ChainSemanticRevision revision, String revisionId) {
    return new ChainSemanticRevision(
        revision.schemaVersion(),
        revisionId,
        revision.chainIdentity(),
        revision.compilerContractVersion(),
        revision.entryPoints(),
        revision.nodes(),
        revision.regions(),
        revision.executionEdges(),
        revision.containment(),
        revision.mappingIntents(),
        revision.constraints(),
        revision.assumptions(),
        revision.citations());
  }

  private static String chainIdentity(ChainSemanticCapture capture, RequirementBrief brief) {
    String captured = trimmed(capture.chainIdentity());
    if (!captured.isEmpty()) {
      return captured;
    }
    String slug = slug(brief.goal());
    return slug.isEmpty() ? "chain" : slug;
  }

  private static String slug(String text) {
    if (text == null) {
      return "";
    }
    String slug =
        text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.length() > 48 ? slug.substring(0, 48) : slug;
  }

  // Shared checks

  private static Set<String> factIds(RequirementBrief brief) {
    Set<String> ids = new LinkedHashSet<>();
    for (RequirementFact fact : brief.facts()) {
      if (fact != null && fact.sourceFactId() != null && !fact.sourceFactId().isBlank()) {
        ids.add(fact.sourceFactId());
      }
    }
    return ids;
  }

  private static Map<String, RequirementEntryPoint> briefEntryPoints(RequirementBrief brief) {
    Map<String, RequirementEntryPoint> byId = new LinkedHashMap<>();
    for (RequirementEntryPoint entryPoint : brief.entryPoints()) {
      if (entryPoint != null && !entryPoint.entryPointId().isBlank()) {
        byId.put(entryPoint.entryPointId(), entryPoint);
      }
    }
    return byId;
  }

  private static Map<String, RequirementServiceCall> briefServiceCalls(RequirementBrief brief) {
    Map<String, RequirementServiceCall> byId = new LinkedHashMap<>();
    for (RequirementServiceCall call : brief.serviceCalls()) {
      if (call != null && !call.serviceCallId().isBlank()) {
        byId.put(call.serviceCallId(), call);
      }
    }
    return byId;
  }

  private static void requireApprovedAnchorGraph(
      RequirementBrief brief,
      List<TriggerBinding> triggerBindings,
      Map<String, RequirementServiceCall> briefServiceCalls,
      List<SemanticExecutionEdge> edges) {
    RequirementFlow flow = brief.flow();
    if (flow.transitions().isEmpty()) {
      return;
    }
    Set<String> interactionIds = new LinkedHashSet<>();
    Set<String> triggerFactIds = triggerFactIds(brief.entryPoints());
    for (TriggerBinding binding : triggerBindings) {
      interactionIds.add(binding.triggerNodeId());
    }
    for (RequirementServiceCall call : briefServiceCalls.values()) {
      if (materializesServiceCallNode(call, triggerFactIds)) {
        interactionIds.add(call.serviceCallId());
      }
    }
    Set<AnchorEdge> contracted = contractedAnchorEdges(interactionIds, edges);
    Set<AnchorEdge> approved = new LinkedHashSet<>();
    for (RequirementFlow.Transition transition : flow.transitions()) {
      approved.add(new AnchorEdge(transition.sourceInteractionId(), transition.targetInteractionId()));
    }
    if (!contracted.equals(approved)) {
      throw new IllegalArgumentException(
          "Captured edges do not preserve approved business transitions. Approved: "
              + approved
              + ". Contracted: "
              + contracted
              + ". You may insert internal processing nodes between an approved source and target,"
              + " but you may not reverse, omit, or add an external interaction transition.");
    }
  }

  private static Set<AnchorEdge> contractedAnchorEdges(
      Set<String> interactionIds, List<SemanticExecutionEdge> edges) {
    Map<String, List<String>> outgoing = new LinkedHashMap<>();
    for (SemanticExecutionEdge edge : edges) {
      outgoing
          .computeIfAbsent(edge.sourceNodeId(), unused -> new ArrayList<>())
          .add(edge.targetNodeId());
    }
    Set<AnchorEdge> contracted = new LinkedHashSet<>();
    for (String source : interactionIds) {
      ArrayDeque<String> pending = new ArrayDeque<>();
      Set<String> seen = new LinkedHashSet<>();
      pending.add(source);
      seen.add(source);
      while (!pending.isEmpty()) {
        String current = pending.removeFirst();
        for (String next : outgoing.getOrDefault(current, List.of())) {
          if (interactionIds.contains(next) && !next.equals(source)) {
            contracted.add(new AnchorEdge(source, next));
            continue;
          }
          if (seen.add(next)) {
            pending.add(next);
          }
        }
      }
    }
    return contracted;
  }

  private record AnchorEdge(String sourceInteractionId, String targetInteractionId) {
    @Override
    public String toString() {
      return sourceInteractionId + " -> " + targetInteractionId;
    }
  }

  static Set<String> triggerFactIds(Iterable<RequirementEntryPoint> entryPoints) {
    Set<String> ids = new LinkedHashSet<>();
    for (RequirementEntryPoint entry : entryPoints) {
      if (entry == null) {
        continue;
      }
      if (!entry.sourceFactId().isBlank()) {
        ids.add(entry.sourceFactId());
      }
      if (!entry.entryPointId().isBlank()) {
        ids.add(entry.entryPointId());
      }
    }
    return ids;
  }

  static boolean materializesServiceCallNode(
      RequirementServiceCall call, Set<String> triggerFactIds) {
    return !triggerFactIds.contains(call.sourceFactId());
  }

  private record TriggerBinding(RequirementEntryPoint approved, int order) {
    String triggerNodeId() {
      return approved.entryPointId();
    }
  }

  private static void requireFacts(List<String> sourceFactIds, Set<String> factIds, String owner) {
    for (String sourceFactId : sourceFactIds) {
      if (!factIds.contains(sourceFactId)) {
        throw new IllegalArgumentException(
            "Provenance sourceFactId '"
                + sourceFactId
                + "' on "
                + owner
                + " is not in the approved requirement brief. Use one of the sourceFactId values:"
                + " "
                + String.join(", ", factIds));
      }
    }
  }

  private static void requireNode(String nodeId, Set<String> nodeIds, String field) {
    if (nodeId == null || !nodeIds.contains(nodeId.trim())) {
      throw new IllegalArgumentException(field + " '" + nodeId + "' is missing from nodes");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }

  private static <T> T requireValue(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}

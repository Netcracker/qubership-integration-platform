package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.mapping.LegacyStageMappingAdapter;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Projects a model-owned {@link ChainSemanticCapture} onto the canonical {@link
 * ChainSemanticRevision}. This is the only place where server-owned state enters a design: schema
 * and contract versions come from the {@link CompilerContract}, catalog values and mapping bodies
 * come from the approved {@link RequirementBrief}, and identifiers are derived from content.
 *
 * <p>Unknown or ambiguous references fail closed. The adapter never guesses a value and never falls
 * back to an alias.
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
    Map<String, String> capabilityKeyByTrigger =
        capabilityKeyByTrigger(capture, briefEntryPoints, factIds);

    List<SemanticNode> nodes =
        nodes(capture, capabilityKeyByTrigger, briefServiceCalls, contract, factIds);
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

    List<SemanticEntryPoint> entryPoints = entryPoints(capture, briefEntryPoints, nodeIds, factIds);
    List<SemanticExecutionEdge> edges = edges(capture, nodeIds, regionIds);
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

  private static Map<String, String> capabilityKeyByTrigger(
      ChainSemanticCapture capture,
      Map<String, RequirementEntryPoint> briefEntryPoints,
      Set<String> factIds) {
    Map<String, String> byTrigger = new LinkedHashMap<>();
    for (ChainSemanticCapture.CapturedEntryPoint entry : capture.entryPoints()) {
      RequirementEntryPoint approved = requireEntryPoint(entry.entryPointId(), briefEntryPoints);
      requireFacts(entry.sourceFactIds(), factIds, "entry point '" + entry.entryPointId() + "'");
      String triggerNodeId = requireText(entry.triggerNodeId(), "triggerNodeId");
      String previous = byTrigger.put(triggerNodeId, approved.capabilityKey());
      if (previous != null && !previous.equals(approved.capabilityKey())) {
        throw new IllegalArgumentException(
            "Trigger node '"
                + triggerNodeId
                + "' serves entry points with different capability keys. Give each capability its"
                + " own trigger node.");
      }
    }
    return byTrigger;
  }

  /**
   * Trigger and operation nodes come from the capture; service-call nodes come from the approved
   * brief. The server names each service-call node after its {@code serviceCallId}, which is what
   * later stages join the catalog binding on, so the model never carries that key.
   */
  private static List<SemanticNode> nodes(
      ChainSemanticCapture capture,
      Map<String, String> capabilityKeyByTrigger,
      Map<String, RequirementServiceCall> briefServiceCalls,
      CompilerContract contract,
      Set<String> factIds) {
    List<SemanticNode> nodes = new ArrayList<>();
    for (ChainSemanticCapture.CapturedTrigger trigger : capture.triggers()) {
      String nodeId = requireText(trigger.nodeId(), "trigger nodeId");
      String capabilityKey = capabilityKeyByTrigger.get(nodeId);
      if (capabilityKey == null || capabilityKey.isBlank()) {
        throw new IllegalArgumentException(
            "Trigger node '"
                + nodeId
                + "' is not referenced by any entry point, so the server cannot resolve its catalog"
                + " capability. Add an entry point whose triggerNodeId is '"
                + nodeId
                + "'.");
      }
      requireFacts(trigger.sourceFactIds(), factIds, "trigger node '" + nodeId + "'");
      nodes.add(
          new SemanticNode.Trigger(
              nodeId, capabilityKey, new SemanticProvenance(trigger.sourceFactIds())));
    }
    for (RequirementServiceCall approved : briefServiceCalls.values()) {
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
      // Provenance is server-side, so an unknown fact id is a brief defect, not a capture error.
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
      String elementType = requireText(operation.elementType(), "elementType");
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
      ChainSemanticCapture capture,
      Map<String, RequirementEntryPoint> briefEntryPoints,
      Set<String> nodeIds,
      Set<String> factIds) {
    List<SemanticEntryPoint> entryPoints = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int position = 0;
    for (ChainSemanticCapture.CapturedEntryPoint entry : capture.entryPoints()) {
      RequirementEntryPoint approved = requireEntryPoint(entry.entryPointId(), briefEntryPoints);
      if (!seen.add(approved.entryPointId())) {
        throw new IllegalArgumentException(
            "Duplicate entryPointId: " + approved.entryPointId());
      }
      requireNode(entry.triggerNodeId(), nodeIds, "triggerNodeId");
      requireNode(entry.initialTargetNodeId(), nodeIds, "initialTargetNodeId");
      requireFacts(entry.sourceFactIds(), factIds, "entry point '" + entry.entryPointId() + "'");
      entryPoints.add(
          new SemanticEntryPoint(
              approved.entryPointId(),
              entry.triggerNodeId().trim(),
              entry.initialTargetNodeId().trim(),
              entry.order() == null ? position : entry.order(),
              new SemanticProvenance(entry.sourceFactIds()),
              new SemanticEntryPoint.Presentation(entry.label(), entry.description())));
      position++;
    }
    if (entryPoints.isEmpty()) {
      throw new IllegalArgumentException("entryPoints must contain at least one entry");
    }
    return List.copyOf(entryPoints);
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
    for (MappingIntent intent : brief.mappingIntents()) {
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

  private static RequirementEntryPoint requireEntryPoint(
      String entryPointId, Map<String, RequirementEntryPoint> briefEntryPoints) {
    RequirementEntryPoint approved =
        entryPointId == null ? null : briefEntryPoints.get(entryPointId.trim());
    if (approved == null) {
      // Name the accepted ids: the model tends to send the capability key instead of the id.
      throw new IllegalArgumentException(
          "Entry point '"
              + entryPointId
              + "' is not in the approved requirement brief. Use one of the entryPointId values:"
              + " "
              + String.join(", ", briefEntryPoints.keySet()));
    }
    if (approved.capabilityKey().isBlank()) {
      throw new IllegalArgumentException(
          "Entry point '"
              + approved.entryPointId()
              + "' has no catalog capability in the approved requirement brief");
    }
    return approved;
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

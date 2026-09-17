package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.catalog.binding.ServiceCallCatalogIdentity;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.RequirementFlowValidator;
import org.qubership.integration.platform.ai.plan.RequirementFlowValidator.LookupAction;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticContainment;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Indexed projection of a validated semantic revision. Containment becomes {@code parentNodeId};
 * execution edges stay distinct and keep the region owner as {@code scopeNodeId}.
 */
@ApplicationScoped
public class DefaultChainSemanticGraphCompiler implements ChainSemanticGraphCompiler {

  private final ChainSemanticRevisionValidator validator;
  private final DeterministicElementSchemaService schemaService;

  @Inject
  public DefaultChainSemanticGraphCompiler(
      ChainSemanticRevisionValidator validator,
      DeterministicElementSchemaService schemaService) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
  }

  @Override
  public ChainPlanGraph compile(
      ChainSemanticRevision revision,
      CompilerContract contract,
      List<ResolvedServiceCallBinding> bindings) {
    return compile(revision, contract, bindings, null);
  }

  @Override
  public ChainPlanGraph compile(
      ChainSemanticRevision revision,
      CompilerContract contract,
      List<ResolvedServiceCallBinding> bindings,
      RequirementBrief brief) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(bindings, "bindings");
    validator.validate(revision, contract, brief);

    Map<String, SemanticNode> nodesById = new LinkedHashMap<>();
    List<SemanticNode.ServiceCall> calls = new ArrayList<>();
    for (SemanticNode node : revision.nodes()) {
      nodesById.put(node.nodeId(), node);
      if (node instanceof SemanticNode.ServiceCall call) {
        calls.add(call);
      }
    }
    List<String> callIds =
        calls.stream().map(SemanticNode.ServiceCall::serviceCallId).toList();
    validateCatalogBindingOwnership(revision.nodes(), callIds, bindings);

    Map<String, String> parentByChild = new LinkedHashMap<>();
    for (SemanticContainment containment : revision.containment()) {
      parentByChild.put(containment.childNodeId(), containment.parentNodeId());
    }

    Map<String, String> ownerByRegionId = new LinkedHashMap<>();
    Map<String, List<PlanProperty>> extraByNode = new LinkedHashMap<>();
    Map<String, Integer> orderByNode = new LinkedHashMap<>();
    for (SemanticRegion region : revision.regions()) {
      String ownerId = ownerNodeId(region);
      if (ownerId != null) {
        ownerByRegionId.put(region.regionId(), ownerId);
      }
      applyRegion(region, nodesById, extraByNode, orderByNode);
    }
    StructuralRegionProjection structuralRegions =
        projectStructuralRegions(revision, nodesById, parentByChild, extraByNode, orderByNode);
    applyHttpTriggerProperties(revision, brief, nodesById, extraByNode);
    applyMappingSites(revision, nodesById, extraByNode);
    applyServiceCallProperties(revision.revisionId(), calls, extraByNode);

    List<ChainPlanNode> planNodes = new ArrayList<>();
    for (SemanticNode node : revision.nodes()) {
      planNodes.add(toPlanNode(node, contract, parentByChild, orderByNode, extraByNode));
    }
    planNodes.addAll(structuralRegions.shellNodes());
    Map<String, String> projectedParents = new LinkedHashMap<>(parentByChild);
    for (ChainPlanNode shell : structuralRegions.shellNodes()) {
      projectedParents.put(shell.nodeId(), shell.parentNodeId());
    }
    List<ChainPlanEdge> planEdges = new ArrayList<>(structuralRegions.shellEntryEdges());
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      planEdges.add(
          toPlanEdge(
              edge,
              ownerByRegionId,
              structuralRegions.shellByEdgeId(),
              projectedParents));
    }
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection(
                revision.chainIdentity(),
                null,
                null,
                null,
                revision.revisionId(),
                revision.compilerContractVersion()),
            List.copyOf(planNodes),
            List.copyOf(planEdges));
    for (ResolvedServiceCallBinding binding : bindings) {
      graph = ServiceCallCatalogIdentity.upsert(graph, binding);
    }
    return graph;
  }

  private ChainPlanNode toPlanNode(
      SemanticNode node,
      CompilerContract contract,
      Map<String, String> parentByChild,
      Map<String, Integer> orderByNode,
      Map<String, List<PlanProperty>> extraByNode) {
    String type = contractType(node);
    if (!contract.elements().containsKey(type)) {
      throw new IllegalArgumentException(
          "Unknown contract element type: "
              + type
              + ". Use a type declared in the compiler contract.");
    }
    List<PlanProperty> extras =
        schemaService.withUnconditionalSchemaDefaults(
            type, extraByNode.getOrDefault(node.nodeId(), List.of()));
    return new ChainPlanNode(
        node.nodeId(),
        type,
        node.nodeId(),
        parentByChild.get(node.nodeId()),
        orderByNode.get(node.nodeId()),
        List.copyOf(extras));
  }

  private static ChainPlanEdge toPlanEdge(
      SemanticExecutionEdge edge,
      Map<String, String> ownerByRegionId,
      Map<String, String> shellByEdgeId,
      Map<String, String> parentByChild) {
    String scopeNodeId =
        edge.regionId() == null ? null : ownerByRegionId.get(edge.regionId());
    String shellNodeId = shellByEdgeId.get(edge.edgeId());
    String sourceNodeId =
        shellNodeId != null && !shellNodeId.equals(edge.targetNodeId())
            ? shellNodeId
            : edge.sourceNodeId();
    String sourceRoot = outermostAncestor(sourceNodeId, parentByChild);
    String targetRoot = outermostAncestor(edge.targetNodeId(), parentByChild);
    if (!sourceNodeId.equals(sourceRoot) && !sourceRoot.equals(targetRoot)) {
      sourceNodeId = sourceRoot;
    }
    return new ChainPlanEdge(edge.edgeId(), sourceNodeId, edge.targetNodeId(), scopeNodeId);
  }

  private static String outermostAncestor(
      String nodeId, Map<String, String> parentByChild) {
    String current = nodeId;
    Set<String> visited = new LinkedHashSet<>();
    while (visited.add(current)) {
      String parent = parentByChild.get(current);
      if (parent == null || parent.isBlank()) {
        return current;
      }
      current = parent;
    }
    throw new IllegalArgumentException("Structural containment cycle at node: " + nodeId);
  }

  private static String contractType(SemanticNode node) {
    return switch (node) {
      case SemanticNode.Trigger trigger -> trigger.capabilityKey();
      case SemanticNode.ServiceCall ignored -> "service-call";
      case SemanticNode.Operation operation -> operation.elementType();
    };
  }

  private static String ownerNodeId(SemanticRegion region) {
    return switch (region) {
      case SemanticRegion.Sequence ignored -> null;
      case SemanticRegion.Condition condition -> condition.ownerNodeId();
      case SemanticRegion.Split split -> split.ownerNodeId();
      case SemanticRegion.Loop loop -> loop.ownerNodeId();
      case SemanticRegion.Retry retry -> retry.ownerNodeId();
      case SemanticRegion.ErrorScope scope -> scope.ownerNodeId();
      default -> throw new IllegalStateException("Unexpected semantic region: " + region);
    };
  }

  private static void applyRegion(
      SemanticRegion region,
      Map<String, SemanticNode> nodesById,
      Map<String, List<PlanProperty>> extraByNode,
      Map<String, Integer> orderByNode) {
    switch (region) {
      case SemanticRegion.Sequence ignored -> {}
      case SemanticRegion.Condition ignored -> {}
      case SemanticRegion.Split ignored -> {}
      case SemanticRegion.Loop loop -> {
        addProperty(extraByNode, loop.ownerNodeId(), "expression", loop.policy().expression());
        addProperty(
            extraByNode,
            loop.ownerNodeId(),
            "maxLoopIteration",
            Integer.toString(loop.policy().safetyBound()));
        switch (loop.policy().mode()) {
          case LoopMode.COPY ->
              addProperty(extraByNode, loop.ownerNodeId(), "copy", "true");
          case LoopMode.DO_WHILE ->
              addProperty(extraByNode, loop.ownerNodeId(), "doWhile", "true");
        }
      }
      case SemanticRegion.Retry retry -> {
        addProperty(
            extraByNode,
            retry.ownerNodeId(),
            "retryCount",
            Integer.toString(retry.policy().retryCount()));
        addProperty(
            extraByNode,
            retry.ownerNodeId(),
            "retryDelay",
            Integer.toString(retry.policy().retryDelayMillis()));
      }
      case SemanticRegion.ErrorScope ignored -> {}
      default -> throw new IllegalStateException("Unexpected semantic region: " + region);
    }
  }

  private static void applyMappingSites(
      ChainSemanticRevision revision,
      Map<String, SemanticNode> nodesById,
      Map<String, List<PlanProperty>> extraByNode) {
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      if (edge.mappingId() == null) {
        continue;
      }
      String siteId = transformSiteId(edge, nodesById);
      addProperty(
          extraByNode, siteId, MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, edge.mappingId());
      addProperty(
          extraByNode, siteId, MappingExecutionSite.SEMANTIC_EDGE_ID_PROPERTY, edge.edgeId());
      addProperty(extraByNode, siteId, MappingExecutionSite.MAPPING_ID_PROPERTY, edge.mappingId());
    }
  }

  private StructuralRegionProjection projectStructuralRegions(
      ChainSemanticRevision revision,
      Map<String, SemanticNode> nodesById,
      Map<String, String> parentByChild,
      Map<String, List<PlanProperty>> extraByNode,
      Map<String, Integer> orderByNode) {
    List<ChainPlanNode> shells = new ArrayList<>();
    List<ChainPlanEdge> shellEntryEdges = new ArrayList<>();
    Map<String, String> shellByEdgeId = new LinkedHashMap<>();
    Set<String> reservedIds = new LinkedHashSet<>(nodesById.keySet());
    Set<String> regionOwnerIds = new LinkedHashSet<>();
    for (SemanticRegion region : revision.regions()) {
      String ownerNodeId = ownerNodeId(region);
      if (ownerNodeId != null) {
        regionOwnerIds.add(ownerNodeId);
      }
    }
    for (SemanticRegion region : revision.regions()) {
      switch (region) {
        case SemanticRegion.Condition condition -> {
          for (SemanticBranch.Condition branch : condition.branches()) {
            String role = branch.role() == ConditionBranchRole.IF ? "if" : "else";
            String shellNodeId =
                hasType(branch.entryNodeId(), role, nodesById)
                    ? branch.entryNodeId()
                    : condition.ownerNodeId() + "-" + role + "-" + branch.branchId();
            List<PlanProperty> properties =
                branch.role() == ConditionBranchRole.IF
                    ? List.of(
                        new PlanProperty("condition", branch.predicate()),
                        new PlanProperty("priority", Integer.toString(branch.priority())))
                    : List.of();
            if (shellNodeId.equals(branch.entryNodeId())) {
              configureExistingShell(
                  shellNodeId,
                  condition.ownerNodeId(),
                  branch.priority(),
                  properties,
                  parentByChild,
                  extraByNode,
                  orderByNode);
            } else {
              addShell(
                  shells,
                  reservedIds,
                  shellNodeId,
                  role,
                  condition.ownerNodeId(),
                  branch.priority(),
                  properties);
              addShellEntryEdge(shellEntryEdges, condition.ownerNodeId(), shellNodeId);
            }
            parentBranchMembers(
                branch.entryNodeId(),
                branch.exitNodeIds(),
                revision.executionEdges(),
                shellNodeId,
                parentByChild,
                regionOwnerIds);
            mapConditionBranchEdge(
                revision.executionEdges(),
                condition.regionId(),
                branch.branchId(),
                shellNodeId,
                shellByEdgeId);
          }
        }
        case SemanticRegion.Split split -> {
          String shellType =
              split.mode() == SplitMode.ASYNC
                  ? "async-split-element-2"
                  : "split-element-2";
          for (SemanticBranch.Split branch : split.branches()) {
            String shellNodeId =
                hasType(branch.entryNodeId(), shellType, nodesById)
                    ? branch.entryNodeId()
                    : split.ownerNodeId() + "-branch-" + branch.branchId();
            List<PlanProperty> properties =
                split.mode() == SplitMode.SYNC
                    ? List.of(new PlanProperty("splitName", branch.branchId()))
                    : List.of();
            if (shellNodeId.equals(branch.entryNodeId())) {
              configureExistingShell(
                  shellNodeId,
                  split.ownerNodeId(),
                  branch.order(),
                  properties,
                  parentByChild,
                  extraByNode,
                  orderByNode);
            } else {
              addShell(
                  shells,
                  reservedIds,
                  shellNodeId,
                  shellType,
                  split.ownerNodeId(),
                  branch.order(),
                  properties);
              addShellEntryEdge(shellEntryEdges, split.ownerNodeId(), shellNodeId);
            }
            parentBranchMembers(
                branch.entryNodeId(),
                branch.exitNodeIds(),
                revision.executionEdges(),
                shellNodeId,
                parentByChild,
                regionOwnerIds);
            mapSplitBranchEdge(
                revision.executionEdges(),
                split.regionId(),
                branch.branchId(),
                shellNodeId,
                shellByEdgeId);
          }
        }
        case SemanticRegion.Loop loop ->
            parentBranchMembers(
                loop.bodyEntryNodeId(),
                loop.bodyExitNodeIds(),
                revision.executionEdges(),
                loop.ownerNodeId(),
                parentByChild,
                regionOwnerIds);
        default -> {
          // Other region kinds do not need structural projection here.
        }
      }
    }
    for (SemanticRegion region : revision.regions()) {
      if (!(region instanceof SemanticRegion.ErrorScope scope)) {
        continue;
      }
      String tryShellId = scope.ownerNodeId() + "-try";
      addShell(shells, reservedIds, tryShellId, "try-2", scope.ownerNodeId(), null, List.of());
      addShellEntryEdge(shellEntryEdges, scope.ownerNodeId(), tryShellId);
      parentBranchMembers(
          scope.tryEntryNodeId(),
          scope.exitNodeIds(),
          revision.executionEdges(),
          tryShellId,
          parentByChild,
          regionOwnerIds);
      mapBranchEdge(revision.executionEdges(), scope.regionId(), SemanticRoute.TryPath.class, null, tryShellId,
          shellByEdgeId);

      int priority = 0;
      for (ErrorHandler handler : scope.handlers()) {
        SemanticNode entry = nodesById.get(handler.entryNodeId());
        String catchShellId;
        if (entry != null && "catch-2".equals(contractType(entry))) {
          catchShellId = entry.nodeId();
          parentByChild.put(catchShellId, scope.ownerNodeId());
        } else {
          catchShellId = scope.ownerNodeId() + "-catch-" + handler.handlerId();
          addShell(
              shells,
              reservedIds,
              catchShellId,
              "catch-2",
              scope.ownerNodeId(),
              priority,
              List.of(
                  new PlanProperty("exception", handler.exceptionClass()),
                  new PlanProperty("priority", Integer.toString(priority))));
          addShellEntryEdge(shellEntryEdges, scope.ownerNodeId(), catchShellId);
        }
        parentBranchMembers(
            handler.entryNodeId(),
            handler.exitNodeIds(),
            revision.executionEdges(),
            catchShellId,
            parentByChild,
            regionOwnerIds);
        if (catchShellId.equals(handler.entryNodeId())) {
          addProperty(extraByNode, catchShellId, "exception", handler.exceptionClass());
          addProperty(extraByNode, catchShellId, "priority", Integer.toString(priority));
          orderByNode.put(catchShellId, priority);
        }
        mapBranchEdge(
            revision.executionEdges(),
            scope.regionId(),
            SemanticRoute.CatchPath.class,
            handler.handlerId(),
            catchShellId,
            shellByEdgeId);
        priority++;
      }

      if (scope.finallyEntryNodeId() != null) {
        String finallyShellId = scope.ownerNodeId() + "-finally";
        addShell(
            shells,
            reservedIds,
            finallyShellId,
            "finally-2",
            scope.ownerNodeId(),
            null,
            List.of());
        addShellEntryEdge(shellEntryEdges, scope.ownerNodeId(), finallyShellId);
        parentBranchMembers(
            scope.finallyEntryNodeId(),
            scope.exitNodeIds(),
            revision.executionEdges(),
            finallyShellId,
            parentByChild,
            regionOwnerIds);
        mapBranchEdge(
            revision.executionEdges(),
            scope.regionId(),
            SemanticRoute.FinallyPath.class,
            null,
            finallyShellId,
            shellByEdgeId);
      }
    }
    return new StructuralRegionProjection(
        List.copyOf(shells), List.copyOf(shellEntryEdges), Map.copyOf(shellByEdgeId));
  }

  private static void addShellEntryEdge(
      List<ChainPlanEdge> edges, String wrapperNodeId, String shellNodeId) {
    edges.add(
        new ChainPlanEdge(
            shellNodeId + "-entry", wrapperNodeId, shellNodeId, wrapperNodeId));
  }

  private void addShell(
      List<ChainPlanNode> shells,
      Set<String> reservedIds,
      String nodeId,
      String type,
      String parentNodeId,
      Integer order,
      List<PlanProperty> properties) {
    if (!reservedIds.add(nodeId)) {
      throw new IllegalArgumentException("Structural shell node id already exists: " + nodeId);
    }
    shells.add(
        new ChainPlanNode(
            nodeId,
            type,
            nodeId,
            parentNodeId,
            order,
            schemaService.withUnconditionalSchemaDefaults(type, properties)));
  }

  private static boolean hasType(
      String nodeId, String expectedType, Map<String, SemanticNode> nodesById) {
    SemanticNode node = nodesById.get(nodeId);
    return node != null && expectedType.equals(contractType(node));
  }

  private static void configureExistingShell(
      String nodeId,
      String parentNodeId,
      Integer order,
      List<PlanProperty> properties,
      Map<String, String> parentByChild,
      Map<String, List<PlanProperty>> extraByNode,
      Map<String, Integer> orderByNode) {
    parentByChild.put(nodeId, parentNodeId);
    orderByNode.put(nodeId, order);
    for (PlanProperty property : properties) {
      addProperty(extraByNode, nodeId, property.key(), property.value());
    }
  }

  private static void parentBranchMembers(
      String entryNodeId,
      List<String> exitNodeIds,
      List<SemanticExecutionEdge> edges,
      String shellNodeId,
      Map<String, String> parentByChild,
      Set<String> regionOwnerIds) {
    Set<String> exits = Set.copyOf(exitNodeIds);
    Set<String> visited = new LinkedHashSet<>();
    List<String> pending = new ArrayList<>();
    pending.add(entryNodeId);
    for (int index = 0; index < pending.size(); index++) {
      String nodeId = pending.get(index);
      if (!visited.add(nodeId)) {
        continue;
      }
      if (!nodeId.equals(shellNodeId)) {
        parentByChild.put(nodeId, shellNodeId);
      }
      if (exits.contains(nodeId) || regionOwnerIds.contains(nodeId)) {
        continue;
      }
      for (SemanticExecutionEdge edge : edges) {
        if (nodeId.equals(edge.sourceNodeId()) && !isBranchSelection(edge.route())) {
          pending.add(edge.targetNodeId());
        }
      }
    }
  }

  private static boolean isBranchSelection(SemanticRoute route) {
    return route instanceof SemanticRoute.ConditionBranch
        || route instanceof SemanticRoute.SplitBranch
        || route instanceof SemanticRoute.LoopBody
        || route instanceof SemanticRoute.TryPath
        || route instanceof SemanticRoute.CatchPath
        || route instanceof SemanticRoute.FinallyPath;
  }

  private static void mapConditionBranchEdge(
      List<SemanticExecutionEdge> edges,
      String regionId,
      String branchId,
      String shellNodeId,
      Map<String, String> shellByEdgeId) {
    for (SemanticExecutionEdge edge : edges) {
      if (regionId.equals(edge.regionId())
          && edge.route() instanceof SemanticRoute.ConditionBranch route
          && branchId.equals(route.branchId())) {
        shellByEdgeId.put(edge.edgeId(), shellNodeId);
      }
    }
  }

  private static void mapSplitBranchEdge(
      List<SemanticExecutionEdge> edges,
      String regionId,
      String branchId,
      String shellNodeId,
      Map<String, String> shellByEdgeId) {
    for (SemanticExecutionEdge edge : edges) {
      if (regionId.equals(edge.regionId())
          && edge.route() instanceof SemanticRoute.SplitBranch route
          && branchId.equals(route.branchId())) {
        shellByEdgeId.put(edge.edgeId(), shellNodeId);
      }
    }
  }

  private static void mapBranchEdge(
      List<SemanticExecutionEdge> edges,
      String regionId,
      Class<? extends SemanticRoute> routeType,
      String handlerId,
      String shellNodeId,
      Map<String, String> shellByEdgeId) {
    for (SemanticExecutionEdge edge : edges) {
      if (!regionId.equals(edge.regionId()) || !routeType.isInstance(edge.route())) {
        continue;
      }
      if (edge.route() instanceof SemanticRoute.CatchPath catchPath
          && !Objects.equals(handlerId, catchPath.handlerId())) {
        continue;
      }
      shellByEdgeId.put(edge.edgeId(), shellNodeId);
    }
  }

  private record StructuralRegionProjection(
      List<ChainPlanNode> shellNodes,
      List<ChainPlanEdge> shellEntryEdges,
      Map<String, String> shellByEdgeId) {}

  private static void applyHttpTriggerProperties(
      ChainSemanticRevision revision,
      RequirementBrief brief,
      Map<String, SemanticNode> nodesById,
      Map<String, List<PlanProperty>> extraByNode) {
    if (brief == null) {
      return;
    }
    Map<String, RequirementEntryPoint> approvedById = new LinkedHashMap<>();
    for (RequirementEntryPoint entryPoint : brief.entryPoints()) {
      approvedById.put(entryPoint.entryPointId(), entryPoint);
    }
    Map<String, CatalogBindingHint> bindingsByEntryPoint = new LinkedHashMap<>();
    for (CatalogBindingHint hint : brief.catalogBindings()) {
      bindingsByEntryPoint.put(hint.interactionId(), hint);
    }
    for (SemanticEntryPoint semanticEntryPoint : revision.entryPoints()) {
      RequirementEntryPoint approved = approvedById.get(semanticEntryPoint.entryPointId());
      SemanticNode node = nodesById.get(semanticEntryPoint.triggerNodeId());
      if (approved == null
          || !(node instanceof SemanticNode.Trigger trigger)
          || !"http-trigger".equals(trigger.capabilityKey())) {
        continue;
      }
      Interaction interaction =
          RequirementFlowValidator.interactionForEntryPoint(brief, semanticEntryPoint.entryPointId());
      LookupAction lookupAction =
          RequirementFlowValidator.catalogLookupAction(interaction, brief.facts());
      if (lookupAction == LookupAction.SKIP) {
        if (!approved.path().isBlank()) {
          addProperty(extraByNode, trigger.nodeId(), "contextPath", approved.path());
        }
        if (!approved.httpMethod().isBlank()) {
          addProperty(
              extraByNode, trigger.nodeId(), "httpMethodRestrict", approved.httpMethod());
        }
        continue;
      }
      if (lookupAction == LookupAction.REQUIRE) {
        CatalogBindingHint binding = bindingsByEntryPoint.get(semanticEntryPoint.entryPointId());
        if (binding == null) {
          continue;
        }
        addProperty(extraByNode, trigger.nodeId(), "systemType", "INTEGRATION");
        addProperty(extraByNode, trigger.nodeId(), "integrationSystemId", binding.systemId());
        addProperty(
            extraByNode,
            trigger.nodeId(),
            "integrationSpecificationGroupId",
            binding.specificationGroupId());
        addProperty(
            extraByNode, trigger.nodeId(), "integrationSpecificationId", binding.specificationId());
        addProperty(
            extraByNode, trigger.nodeId(), "integrationOperationId", binding.integrationOperationId());
        if (binding.path() != null && !binding.path().isBlank()) {
          addProperty(
              extraByNode, trigger.nodeId(), "integrationOperationPath", binding.path());
        }
        if (binding.method() != null && !binding.method().isBlank()) {
          addProperty(extraByNode, trigger.nodeId(), "httpMethodRestrict", binding.method());
        }
      }
    }
  }

  private static String transformSiteId(
      SemanticExecutionEdge edge, Map<String, SemanticNode> nodesById) {
    SemanticNode source = nodesById.get(edge.sourceNodeId());
    if (isTransform(source)) {
      return source.nodeId();
    }
    SemanticNode target = nodesById.get(edge.targetNodeId());
    if (isTransform(target)) {
      return target.nodeId();
    }
    throw new IllegalStateException(
        "Cannot materialize execution edge '"
            + edge.edgeId()
            + "': mapping intent '"
            + edge.mappingId()
            + "' has no mapper-2 or script execution site.");
  }

  private static boolean isTransform(SemanticNode node) {
    if (!(node instanceof SemanticNode.Operation operation)) {
      return false;
    }
    String type = operation.elementType();
    return MappingExecutionSite.ELEMENT_TYPE.equals(type)
        || MappingExecutionSite.SCRIPT_ELEMENT_TYPE.equals(type);
  }

  private static void applyServiceCallProperties(
      String revisionId,
      List<SemanticNode.ServiceCall> calls,
      Map<String, List<PlanProperty>> extraByNode) {
    for (SemanticNode.ServiceCall call : calls) {
      addProperty(extraByNode, call.nodeId(), "semanticNodeId", call.nodeId());
      addProperty(extraByNode, call.nodeId(), "semanticRevisionId", revisionId);
      if (call.failureMode() == ServiceCallFailureMode.INLINE_RESPONSE) {
        addProperty(extraByNode, call.nodeId(), "errorThrowing", "false");
      }
    }
  }

  private static void validateCatalogBindingOwnership(
      List<SemanticNode> nodes,
      List<String> requiredServiceCallIds,
      List<ResolvedServiceCallBinding> bindings) {
    Map<String, String> serviceCallTargetByOccurrence = new LinkedHashMap<>();
    Set<String> triggerTargets = new LinkedHashSet<>();
    for (SemanticNode node : nodes) {
      if (node instanceof SemanticNode.ServiceCall call) {
        serviceCallTargetByOccurrence.put(call.serviceCallId(), call.nodeId());
      } else if (node instanceof SemanticNode.Trigger trigger) {
        triggerTargets.add(trigger.nodeId());
      }
    }

    Map<String, ResolvedServiceCallBinding> bindingByOccurrence = new LinkedHashMap<>();
    Set<String> boundTargets = new LinkedHashSet<>();
    for (ResolvedServiceCallBinding binding : bindings) {
      if (binding == null) {
        throw new IllegalArgumentException("catalog binding is required");
      }
      if (bindingByOccurrence.putIfAbsent(binding.serviceCallId(), binding) != null) {
        throw new IllegalArgumentException(
            "duplicate catalog binding for serviceCallId=" + binding.serviceCallId());
      }
      String expectedTarget = serviceCallTargetByOccurrence.get(binding.serviceCallId());
      if (expectedTarget == null && triggerTargets.contains(binding.targetNodeId())) {
        if (!boundTargets.add(binding.targetNodeId())) {
          throw new IllegalArgumentException(
              "duplicate catalog binding targetNodeId=" + binding.targetNodeId());
        }
        continue;
      }
      if (expectedTarget == null) {
        throw new IllegalArgumentException(
            "extra catalog binding for serviceCallId=" + binding.serviceCallId());
      }
      if (!expectedTarget.equals(binding.targetNodeId())) {
        throw new IllegalArgumentException(
            "catalog binding serviceCallId="
                + binding.serviceCallId()
                + " targets node "
                + binding.targetNodeId()
                + " but semantic owner is "
                + expectedTarget);
      }
      if (!boundTargets.add(binding.targetNodeId())) {
        throw new IllegalArgumentException(
            "duplicate catalog binding targetNodeId=" + binding.targetNodeId());
      }
    }

    List<ResolvedServiceCallBinding> requiredBindings =
        requiredServiceCallIds.stream()
            .map(bindingByOccurrence::get)
            .filter(Objects::nonNull)
            .toList();
    ResolvedServiceCallBinding.requireExactOwners(requiredServiceCallIds, requiredBindings);
  }

  private static void addProperty(
      Map<String, List<PlanProperty>> extraByNode, String nodeId, String key, String value) {
    extraByNode
        .computeIfAbsent(nodeId, ignored -> new ArrayList<>())
        .add(new PlanProperty(key, value));
  }
}

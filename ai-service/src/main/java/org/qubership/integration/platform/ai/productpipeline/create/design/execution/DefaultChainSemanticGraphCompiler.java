package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.catalog.binding.ServiceCallCatalogIdentity;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
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
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;

/**
 * Indexed projection of a validated semantic revision. Containment becomes {@code parentNodeId};
 * execution edges stay distinct and keep the region owner as {@code scopeNodeId}.
 */
@ApplicationScoped
public class DefaultChainSemanticGraphCompiler implements ChainSemanticGraphCompiler {

  private final ChainSemanticRevisionValidator validator;
  private final CatalogBindingMatcher bindingMatcher;

  @Inject
  public DefaultChainSemanticGraphCompiler(
      ChainSemanticRevisionValidator validator, CatalogBindingMatcher bindingMatcher) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.bindingMatcher = Objects.requireNonNull(bindingMatcher, "bindingMatcher");
  }

  @Override
  public ChainPlanGraph compile(
      ChainSemanticRevision revision,
      CompilerContract contract,
      List<ResolvedServiceCallBinding> bindings) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(bindings, "bindings");
    validator.validate(revision, contract);

    Map<String, SemanticNode> nodesById = new LinkedHashMap<>();
    List<SemanticNode.ServiceCall> calls = new ArrayList<>();
    for (SemanticNode node : revision.nodes()) {
      nodesById.put(node.nodeId(), node);
      if (node instanceof SemanticNode.ServiceCall call) {
        calls.add(call);
      }
    }
    bindingMatcher.match(calls, bindings);

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
      applyRegion(region, extraByNode, orderByNode);
    }
    applyMappingSites(revision, nodesById, extraByNode);
    applyServiceCallProperties(revision.revisionId(), calls, extraByNode);

    List<ChainPlanNode> planNodes = new ArrayList<>();
    for (SemanticNode node : revision.nodes()) {
      planNodes.add(toPlanNode(node, contract, parentByChild, orderByNode, extraByNode));
    }
    List<ChainPlanEdge> planEdges = new ArrayList<>();
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      planEdges.add(toPlanEdge(edge, ownerByRegionId));
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

  private static ChainPlanNode toPlanNode(
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
    List<PlanProperty> extras = extraByNode.getOrDefault(node.nodeId(), List.of());
    return new ChainPlanNode(
        node.nodeId(),
        type,
        node.nodeId(),
        parentByChild.get(node.nodeId()),
        orderByNode.get(node.nodeId()),
        List.copyOf(extras));
  }

  private static ChainPlanEdge toPlanEdge(
      SemanticExecutionEdge edge, Map<String, String> ownerByRegionId) {
    String scopeNodeId =
        edge.regionId() == null ? null : ownerByRegionId.get(edge.regionId());
    return new ChainPlanEdge(edge.edgeId(), edge.sourceNodeId(), edge.targetNodeId(), scopeNodeId);
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
      Map<String, List<PlanProperty>> extraByNode,
      Map<String, Integer> orderByNode) {
    switch (region) {
      case SemanticRegion.Sequence ignored -> {}
      case SemanticRegion.Condition condition -> {
        for (SemanticBranch.Condition branch : condition.branches()) {
          orderByNode.put(branch.entryNodeId(), branch.priority());
          if (branch.role() == ConditionBranchRole.IF) {
            addProperty(extraByNode, branch.entryNodeId(), "condition", branch.predicate());
          }
        }
      }
      case SemanticRegion.Split split -> {
        for (SemanticBranch.Split branch : split.branches()) {
          orderByNode.put(branch.entryNodeId(), branch.order());
        }
      }
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
      case SemanticRegion.ErrorScope scope -> {
        int index = 0;
        for (ErrorHandler handler : scope.handlers()) {
          addProperty(extraByNode, handler.entryNodeId(), "exception", handler.exceptionClass());
          addProperty(extraByNode, handler.entryNodeId(), "priority", Integer.toString(index));
          orderByNode.put(handler.entryNodeId(), index);
          index++;
        }
      }
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
    }
  }

  private static void addProperty(
      Map<String, List<PlanProperty>> extraByNode, String nodeId, String key, String value) {
    extraByNode
        .computeIfAbsent(nodeId, ignored -> new ArrayList<>())
        .add(new PlanProperty(key, value));
  }
}

package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRouteKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;

/**
 * LLM-facing design input for {@link ChainSemanticCaptureTool#captureChainSemanticRevision}. It
 * carries topology and references into the approved requirement brief; it is never persisted.
 *
 * <p>Server-owned state stays out of this contract: no {@code revisionId}, no {@code edgeId}, no
 * schema or compiler-contract version, no sealed domain type, and no service-call node. The server
 * materializes one service-call node per approved brief entry and names it after the brief's
 * {@code serviceCallId}, so a capture cannot break the join to the catalog binding. {@link
 * ChainSemanticCaptureAdapter} projects a capture onto the canonical {@code ChainSemanticRevision}.
 * Each variant gets its own homogeneous list so the generated tool schema stays free of polymorphic
 * {@code anyOf} branches.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChainSemanticCapture(
    @Description("Short chain name, e.g. orders-intake") String chainIdentity,
    @Description("One entry per configured trigger; entryPointId comes from the approved brief")
        List<CapturedEntryPoint> entryPoints,
    @Description("Trigger nodes; one per entry point") List<CapturedTrigger> triggers,
    @Description("Processing nodes such as script, mapper-2, condition, split, loop, or try-catch")
        List<CapturedOperation> operations,
    @Description("Plain sequence regions; omit when there is no control-flow region")
        List<CapturedSequenceRegion> sequenceRegions,
    @Description("Condition regions; omit when the chain has no branching")
        List<CapturedConditionRegion> conditionRegions,
    @Description("Split regions; omit when the chain has no split")
        List<CapturedSplitRegion> splitRegions,
    @Description("Loop regions; omit when the chain has no loop")
        List<CapturedLoopRegion> loopRegions,
    @Description("Retry regions; omit when the chain has no retry")
        List<CapturedRetryRegion> retryRegions,
    @Description("Try-catch regions; omit when the chain has no error handling")
        List<CapturedErrorScopeRegion> errorScopeRegions,
    @Description("Directed control-flow edges; the server derives every edge id")
        List<CapturedEdge> edges,
    @Description("Parent-child relations for container nodes; omit when there is no container")
        List<CapturedContainment> containment) {

  ChainSemanticCapture {
    entryPoints = copy(entryPoints);
    triggers = copy(triggers);
    operations = copy(operations);
    sequenceRegions = copy(sequenceRegions);
    conditionRegions = copy(conditionRegions);
    splitRegions = copy(splitRegions);
    loopRegions = copy(loopRegions);
    retryRegions = copy(retryRegions);
    errorScopeRegions = copy(errorScopeRegions);
    edges = copy(edges);
    containment = copy(containment);
  }

  /** Trigger occurrence that starts one exchange. {@code capabilityKey} comes from the brief. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedEntryPoint(
      @Description("entryPointId copied from the approved brief") String entryPointId,
      @Description("nodeId of the trigger node that serves this entry point") String triggerNodeId,
      @Description("nodeId of the first node reached from the trigger") String initialTargetNodeId,
      @Description("Presentation order; omit to use list position") Integer order,
      @Description("sourceFactIds copied from the approved brief") List<String> sourceFactIds,
      @Description("Short label shown to the reviewer") String label,
      @Description("One sentence shown to the reviewer") String description) {

    CapturedEntryPoint {
      sourceFactIds = copy(sourceFactIds);
    }
  }

  /** Trigger node. The catalog capability comes from the matching brief entry point. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedTrigger(
      @Description("Local node id used by edges in this capture, e.g. trigger-http") String nodeId,
      @Description("sourceFactIds copied from the approved brief") List<String> sourceFactIds) {

    CapturedTrigger {
      sourceFactIds = copy(sourceFactIds);
    }
  }

  /** Processing node. {@code elementType} must be a type the compiler contract declares. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedOperation(
      @Description("Local node id used by edges in this capture, e.g. op-transform") String nodeId,
      @Description("Compiler element type, e.g. script, mapper-2, condition, split, loop")
          String elementType,
      @Description("sourceFactIds copied from the approved brief") List<String> sourceFactIds) {

    CapturedOperation {
      sourceFactIds = copy(sourceFactIds);
    }
  }

  /** Ordered group of nodes without branching. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedSequenceRegion(
      @Description("Local region id used by edges in this capture") String regionId,
      @Description("Node ids inside this region, in execution order") List<String> memberNodeIds) {

    CapturedSequenceRegion {
      memberNodeIds = copy(memberNodeIds);
    }
  }

  /** Branching region owned by one condition node. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedConditionRegion(
      @Description("Local region id used by edges in this capture") String regionId,
      @Description("nodeId of the condition node that owns this region") String ownerNodeId,
      @Description("One entry per branch; exactly one branch has role ELSE")
          List<CapturedConditionBranch> branches,
      @Description("nodeId where the branches reconverge; omit when they do not")
          String reconvergenceNodeId) {

    CapturedConditionRegion {
      branches = copy(branches);
    }
  }

  /** One branch of a condition region. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedConditionBranch(
      @Description("Local branch id referenced by CONDITION_BRANCH edges") String branchId,
      @Description("IF or ELSE") ConditionBranchRole role,
      @Description("Predicate expression; leave empty on the ELSE branch") String predicate,
      @Description("Evaluation priority; lower runs first") Integer priority,
      @Description("nodeId of the first node on this branch") String entryNodeId,
      @Description("Node ids where this branch ends") List<String> exitNodeIds) {

    CapturedConditionBranch {
      exitNodeIds = copy(exitNodeIds);
    }
  }

  /** Parallel region owned by one split node. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedSplitRegion(
      @Description("Local region id used by edges in this capture") String regionId,
      @Description("nodeId of the split node that owns this region") String ownerNodeId,
      @Description("SYNC for split-2, ASYNC for split-async-2") SplitMode mode,
      @Description("One entry per parallel branch") List<CapturedSplitBranch> branches,
      @Description("nodeId where the branches reconverge; omit when they do not")
          String reconvergenceNodeId) {

    CapturedSplitRegion {
      branches = copy(branches);
    }
  }

  /** One branch of a split region. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedSplitBranch(
      @Description("Local branch id referenced by SPLIT_BRANCH edges") String branchId,
      @Description("Presentation order; omit to use list position") Integer order,
      @Description("nodeId of the first node on this branch") String entryNodeId,
      @Description("Node ids where this branch ends") List<String> exitNodeIds) {

    CapturedSplitBranch {
      exitNodeIds = copy(exitNodeIds);
    }
  }

  /** Repetition region owned by one loop node. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedLoopRegion(
      @Description("Local region id used by edges in this capture") String regionId,
      @Description("nodeId of the loop node that owns this region") String ownerNodeId,
      @Description("nodeId of the first node inside the loop body") String bodyEntryNodeId,
      @Description("Node ids where the loop body ends") List<String> bodyExitNodeIds,
      @Description("nodeId reached when the loop finishes") String exitNodeId,
      @Description("COPY or DO_WHILE") LoopMode loopMode,
      @Description("Loop expression, e.g. the collection to iterate") String loopExpression,
      @Description("Positive iteration cap") Integer loopSafetyBound) {

    CapturedLoopRegion {
      bodyExitNodeIds = copy(bodyExitNodeIds);
    }
  }

  /** Retry region owned by one retry node. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedRetryRegion(
      @Description("Local region id used by edges in this capture") String regionId,
      @Description("nodeId of the retry node that owns this region") String ownerNodeId,
      @Description("nodeId of the first node inside the retried body") String bodyEntryNodeId,
      @Description("Node ids where the retried body ends") List<String> bodyExitNodeIds,
      @Description("nodeId reached when every attempt fails") String exhaustedNodeId,
      @Description("Number of attempts") Integer retryCount,
      @Description("Delay between attempts, in milliseconds") Integer retryDelayMillis) {

    CapturedRetryRegion {
      bodyExitNodeIds = copy(bodyExitNodeIds);
    }
  }

  /** Try-catch region owned by one error-handling node. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedErrorScopeRegion(
      @Description("Local region id used by edges in this capture") String regionId,
      @Description("nodeId of the try-catch node that owns this region") String ownerNodeId,
      @Description("nodeId of the first node inside the try path") String tryEntryNodeId,
      @Description("Handlers in catch order") List<CapturedErrorHandler> handlers,
      @Description("nodeId of the first node on the finally path; omit when there is none")
          String finallyEntryNodeId,
      @Description("Node ids where this region ends") List<String> exitNodeIds) {

    CapturedErrorScopeRegion {
      handlers = copy(handlers);
      exitNodeIds = copy(exitNodeIds);
    }
  }

  /** One catch handler inside a try-catch region. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedErrorHandler(
      @Description("Local handler id referenced by CATCH_PATH edges") String handlerId,
      @Description("Exception class this handler catches") String exceptionClass,
      @Description("nodeId of the first node on this handler path") String entryNodeId,
      @Description("Node ids where this handler ends") List<String> exitNodeIds) {

    CapturedErrorHandler {
      exitNodeIds = copy(exitNodeIds);
    }
  }

  /**
   * Directed control-flow edge. The server derives {@code edgeId} from the edge content, so two
   * captures of the same design produce the same identifiers.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedEdge(
      @Description("nodeId the exchange leaves") String sourceNodeId,
      @Description("nodeId the exchange reaches") String targetNodeId,
      @Description("regionId this edge belongs to; omit outside a control-flow region")
          String regionId,
      @Description("Edge role; omit for a plain sequence edge") SemanticRouteKind routeKind,
      @Description("branchId for CONDITION_BRANCH and SPLIT_BRANCH") String branchId,
      @Description("Branch ids that reconverge here; RECONVERGE only") List<String> branchIds,
      @Description("handlerId for CATCH_PATH") String handlerId,
      @Description("mappingIntentId from the approved brief; omit when nothing is mapped here")
          String mappingIntentId) {

    CapturedEdge {
      branchIds = copy(branchIds);
    }
  }

  /** Structural parent-child relation. It does not describe exchange flow. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CapturedContainment(
      @Description("nodeId of the container node") String parentNodeId,
      @Description("nodeId of the contained node") String childNodeId,
      @Description("Containment role declared by the compiler contract") String role) {}

  /** Null-tolerant copy. A capture list may arrive absent, or with null holes the model emitted. */
  private static <T> List<T> copy(List<T> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    List<T> present = new ArrayList<>(values.size());
    for (T value : values) {
      if (value != null) {
        present.add(value);
      }
    }
    return List.copyOf(present);
  }
}

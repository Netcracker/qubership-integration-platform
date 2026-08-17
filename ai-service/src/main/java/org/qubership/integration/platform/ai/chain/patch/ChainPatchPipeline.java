package org.qubership.integration.platform.ai.chain.patch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;

/**
 * Assembles a chain patch attempt from a model capture, shared by every caller that drives the
 * COMPARE_AND_PATCH pipeline: the interactive scenario and the regression harness. One assembly
 * path keeps ownership and shape validation identical regardless of who is asking for the change.
 */
public final class ChainPatchPipeline {

  public static final String OWNER = "chain-patch";

  private ChainPatchPipeline() {}

  public static GraphPatch toGraphPatch(ChainPatchCapture capture) {
    return toGraphPatch(capture, null);
  }

  /**
   * The capture as a patch, with an operation the model left out worked out from the chain.
   *
   * <p>{@code operation} is the field a small model drops first, and it drops it more often the
   * larger the patch: adding a branch holding two elements failed this way in eight runs out of
   * nine. It is also the most redundant field in the shape, because the chain answers it. A body
   * naming an element the chain does not have can only be an add; a body naming one it does have
   * can only be an update.
   *
   * <p>REMOVE is never inferred. An entry carrying nothing but a target id and no operation stays
   * as it is and is refused downstream: reading a missing field as an instruction to delete is the
   * one guess here that destroys something, and a patch the reader has to retype costs far less
   * than an element they have to rebuild.
   */
  public static GraphPatch toGraphPatch(ChainPatchCapture capture, ChainPlanGraph base) {
    return new GraphPatch(
        capture.patchId(),
        OWNER,
        base == null ? capture.nodePatches() : withInferredNodeOperations(capture.nodePatches(), base),
        base == null ? capture.edgePatches() : withInferredEdgeOperations(capture.edgePatches(), base),
        capture.propertyPatches(),
        null,
        List.of(),
        capture.rationale());
  }

  private static List<NodePatch> withInferredNodeOperations(
      List<NodePatch> nodePatches, ChainPlanGraph base) {
    if (nodePatches == null || nodePatches.isEmpty()) {
      return nodePatches;
    }
    Set<String> known = new HashSet<>();
    for (ChainPlanNode node : base.nodes() == null ? List.<ChainPlanNode>of() : base.nodes()) {
      if (node != null && node.nodeId() != null) {
        known.add(node.nodeId());
      }
    }
    List<NodePatch> resolved = new ArrayList<>(nodePatches.size());
    for (NodePatch patch : nodePatches) {
      if (patch != null
          && patch.operation() == null
          && patch.node() != null
          && patch.node().nodeId() != null) {
        GraphPatchOperation inferred =
            known.contains(patch.node().nodeId())
                ? GraphPatchOperation.UPDATE
                : GraphPatchOperation.ADD;
        resolved.add(new NodePatch(inferred, patch.node(), patch.targetNodeId()));
      } else {
        resolved.add(patch);
      }
    }
    return List.copyOf(resolved);
  }

  private static List<EdgePatch> withInferredEdgeOperations(
      List<EdgePatch> edgePatches, ChainPlanGraph base) {
    if (edgePatches == null || edgePatches.isEmpty()) {
      return edgePatches;
    }
    Set<String> known = new HashSet<>();
    for (ChainPlanEdge edge : base.edges() == null ? List.<ChainPlanEdge>of() : base.edges()) {
      if (edge != null && edge.edgeId() != null) {
        known.add(edge.edgeId());
      }
    }
    List<EdgePatch> resolved = new ArrayList<>(edgePatches.size());
    for (EdgePatch patch : edgePatches) {
      if (patch != null
          && patch.operation() == null
          && patch.edge() != null
          && patch.edge().edgeId() != null) {
        GraphPatchOperation inferred =
            known.contains(patch.edge().edgeId())
                ? GraphPatchOperation.UPDATE
                : GraphPatchOperation.ADD;
        resolved.add(new EdgePatch(inferred, patch.edge(), patch.targetEdgeId()));
      } else {
        resolved.add(patch);
      }
    }
    return List.copyOf(resolved);
  }

  public static GraphPatchExecutionContext executionContext(
      ImportedChainPlan imported, String chainId, GraphPatch patch, ChainPatchOwnership ownership) {
    return executionContext(imported, chainId, patch, ownership, false);
  }

  public static GraphPatchExecutionContext executionContext(
      ImportedChainPlan imported,
      String chainId,
      GraphPatch patch,
      ChainPatchOwnership ownership,
      boolean mayRemove) {
    return new GraphPatchExecutionContext(
        chainId,
        OWNER,
        null,
        imported.baseGraphDigest(),
        null,
        null,
        null,
        List.of(),
        imported.graph(),
        ownership.forChain(imported.graph(), patch, mayRemove),
        null);
  }

  /**
   * Whether a failed {@link GraphPatchApplyResult} was refused by the ownership policy, as opposed
   * to a structural block a later stage (e.g. a missing edge id) raised after ownership already
   * passed. The two read very differently to a reader: one names a permission the patch lacks, the
   * other names something wrong with the patch itself.
   */
  public static boolean isOwnershipViolation(GraphPatchApplyResult applied) {
    return applied.validationResult().issues().stream()
        .anyMatch(
            issue ->
                GraphPatchOwnershipValidator.OWNERSHIP_VIOLATION_ISSUE_ID.equals(issue.issueId()));
  }

  public static String buildPatchRequest(
      ObjectMapper objectMapper,
      ChainPlanGraph graph,
      String userMessage,
      ChainElementCatalog elementCatalog) {
    String graphJson;
    try {
      graphJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(graph);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot render the chain graph for the model", e);
    }
    // The graph only shows the types the chain already holds, so on its own it leaves the model
    // guessing the catalog's spelling for anything new -- and a guessed type is refused as unowned.
    String elementTypes =
        elementCatalog == null ? "" : String.join(", ", elementCatalog.availableTypeLines());
    String body =
        """
        Change this chain as the user asks.

        Reference -- element types that exist, for adding an element of a type the chain does not
        have yet. Use one exactly as written here; no other type exists:
        %s

        Chain graph (the current state of the chain; node ids are catalog element ids):
        %s

        User request:
        %s
        """
            .formatted(elementTypes, graphJson, userMessage);
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
  }
}

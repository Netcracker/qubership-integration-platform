package org.qubership.integration.platform.ai.chain.patch;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/** Captures the property change a model proposes for an existing catalog chain. */
@ApplicationScoped
public class ChainPatchTool {

  private static final Logger LOG = Logger.getLogger(ChainPatchTool.class);
  private static final String TOOL = "proposeChainPatch";

  private final ChainPatchStore patchStore;

  @Inject
  public ChainPatchTool(ChainPatchStore patchStore) {
    this.patchStore = Objects.requireNonNull(patchStore, "patchStore");
  }

  @Tool(
      """
      Propose the change the user asked for on the open chain. Call this to present the change --
      do not describe it in prose and ask the user to confirm first; calling this tool is what
      shows it to them as a card to answer.
      Reconfigure an element with propertyPatches, naming a node id from the chain graph in the user
      message. Add elements with nodePatches and connect them with edgePatches, giving each new
      element a node id of your own and each new edge an edge id of your own. Remove an element or a
      connection with a REMOVE operation naming its targetNodeId or targetEdgeId; removal is final,
      so offer createChainSnapshot first. Nothing may be renamed. Call this once.
      """)
  public String proposeChainPatch(ChainPatchCapture patch) {
    String conversationId = ToolSession.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, TOOL, conversationId, describe(patch));

    String result = capture(conversationId, patch);
    ToolTraceLog.logToolComplete(LOG, TOOL, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  /**
   * Renders what the model actually submitted: which operation targets which node, and for property
   * patches which key. A patch that misses its target, repeats a node, or names a key the schema
   * does not own is only diagnosable from the trace, and the id/rationale the model fills in are
   * frequently null.
   */
  private static String describe(ChainPatchCapture patch) {
    if (patch == null) {
      return "patch=null";
    }
    StringBuilder text = new StringBuilder();
    if (patch.nodePatches() != null) {
      for (NodePatch nodePatch : patch.nodePatches()) {
        if (nodePatch == null) {
          continue;
        }
        String nodeId =
            nodePatch.node() != null ? nodePatch.node().nodeId() : nodePatch.targetNodeId();
        text.append(" node[").append(nodePatch.operation()).append(' ').append(nodeId).append(']');
      }
    }
    if (patch.edgePatches() != null) {
      for (EdgePatch edgePatch : patch.edgePatches()) {
        if (edgePatch == null) {
          continue;
        }
        String edgeId =
            edgePatch.edge() != null ? edgePatch.edge().edgeId() : edgePatch.targetEdgeId();
        text.append(" edge[").append(edgePatch.operation()).append(' ').append(edgeId).append(']');
      }
    }
    if (patch.propertyPatches() != null) {
      for (PropertyPatch propertyPatch : patch.propertyPatches()) {
        if (propertyPatch == null) {
          continue;
        }
        text.append(" property[")
            .append(propertyPatch.operation())
            .append(' ')
            .append(propertyPatch.targetNodeId())
            .append('.')
            .append(propertyPatch.property() == null ? "?" : propertyPatch.property().key())
            .append(']');
      }
    }
    return "patchId="
        + patch.patchId()
        + (text.isEmpty() ? " (no operations)" : text)
        + " rationale="
        + AiTraceLog.preview(patch.rationale(), 120);
  }

  private String capture(String conversationId, ChainPatchCapture patch) {
    if (conversationId == null || conversationId.isBlank()) {
      return "conversationId is required (no active chat session)";
    }
    if (patch == null || isEmpty(patch)) {
      return "Patch not accepted: submit at least one property, element, or connection change.";
    }
    patchStore.putCapture(conversationId, patch);
    return "Patch captured. It is shown to the user for confirmation.";
  }

  private static boolean isEmpty(ChainPatchCapture patch) {
    return isEmpty(patch.propertyPatches())
        && isEmpty(patch.nodePatches())
        && isEmpty(patch.edgePatches());
  }

  private static boolean isEmpty(java.util.List<?> patches) {
    return patches == null || patches.isEmpty();
  }
}

package org.qubership.integration.platform.ai.chain.patch;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.chat.ToolSession;

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
      Propose the change the user asked for on the open chain.
      Reconfigure an element with propertyPatches, naming a node id from the chain graph in the user
      message. Add elements with nodePatches and connect them with edgePatches, giving each new
      element a node id of your own. Nothing may be removed or renamed. Call this once.
      The change is shown to the user for confirmation; it is not written yet.
      """)
  public String proposeChainPatch(ChainPatchCapture patch) {
    String conversationId = ToolSession.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        TOOL,
        conversationId,
        "patchId="
            + (patch != null ? patch.patchId() : "null")
            + " rationale="
            + AiTraceLog.preview(patch != null ? patch.rationale() : null, 120));

    String result = capture(conversationId, patch);
    ToolTraceLog.logToolComplete(LOG, TOOL, conversationId, System.currentTimeMillis() - startMs, result);
    return result;
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

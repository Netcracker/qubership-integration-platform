package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * LangChain4j tool that lets the structure generator agent persist the plan graph.
 * The agent calls this in the same turn once the graph is complete; the graph is then
 * available to the implement pipeline via {@link ChainPlanStore}.
 *
 * <p>Conversation id is taken from {@link ToolSession} (current tool session), not from the model.
 */
@ApplicationScoped
public class ChainPlanTool {

  private static final Logger LOG = Logger.getLogger(ChainPlanTool.class);

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Structure generation did not capture a plan. The agent must call captureChainPlan"
          + " with a valid ChainPlanGraph before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Chain plan already captured. Do not call captureChainPlan again;"
          + " finish this turn without further tool calls.";

  private final CaptureSession captureSession;
  private final ChainPlanStore store;
  private final ChainPlanRepairDraftStore repairDraftStore;
  private final ChainPlanGraphValidator validator;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;

  @Inject
  ChainPlanTool(
      CaptureSession captureSession,
      ChainPlanStore store,
      ChainPlanRepairDraftStore repairDraftStore,
      ChainPlanGraphValidator validator,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore) {
    this.captureSession = captureSession;
    this.store = store;
    this.repairDraftStore = repairDraftStore;
    this.validator = validator;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
  }

  @Tool("""
      Capture the chain implementation plan as a typed ChainPlanGraph in the same turn once the\
       graph is complete.
      Do not pass conversationId — the server binds the plan to the current chat session\
       automatically.
      Follow the compiler skill document and system role for graph shape rules.
      Minimal example:
      {
        "schemaVersion": "1.0",
        "chain": {"name": "...", "description": "..."},
        "nodes": [{"nodeId": "...", "type": "http-trigger", "label": "...", "parentNodeId": null,
          "order": null}],
        "edges": [{"edgeId": "...", "fromNodeId": "...", "toNodeId": "...", "scopeNodeId": null}]
      }
      Capture only the structural plan skeleton. Do not include node properties — generator\
       skills add triggers, service calls, auth, retry, timeout, security, routing, scripts,\
       and other element properties later via captureGraphPatch.""")
  public String captureChainPlan(ChainPlanCapture graph) {

    String conversationId = resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureChainPlan",
        conversationId,
        "preview=" + AiTraceLog.previewJson(objectMapper, graph, 400));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        String message = "conversationId is required (no active chat session)";
        LOG.warnf("captureChainPlan: %s", message);
        return finish(conversationId, startMs, message);
      }
      if (graph == null) {
        String message = "graph is required";
        LOG.warnf("captureChainPlan: %s conversationId=%s", message, conversationId);
        return finish(conversationId, startMs, message);
      }

      ChainPlanGraph chainPlanGraph =
          validator.normalizeMissingSiblingExecutionEdges(toChainPlanGraph(graph));

      List<String> errors = validator.validate(chainPlanGraph);
      if (!errors.isEmpty()) {
        repairDraftStore.put(conversationId, chainPlanGraph);
        LOG.warnf(
            "captureChainPlan: validation failed conversationId=%s errors=%s",
            conversationId,
            errors);
        String message = "Plan validation failed:\n" + String.join("\n", errors);
        boolean repeated = feedbackStore.recordPlanValidationFailure(conversationId, message);
        if (repeated) {
          throw new CaptureValidationException(message);
        }
        return finish(conversationId, startMs, message);
      }

      CaptureKey key = CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, conversationId);
      int nodeCount = chainPlanGraph.nodes() != null ? chainPlanGraph.nodes().size() : 0;
      int edgeCount = chainPlanGraph.edges() != null ? chainPlanGraph.edges().size() : 0;
      String chainName = chainPlanGraph.chain() != null ? chainPlanGraph.chain().name() : "unnamed";
      String successMessage =
          "Plan captured: chain='"
              + chainName
              + "', "
              + nodeCount
              + " nodes, "
              + edgeCount
              + " edges. Do not call captureChainPlan again;"
              + " finish this turn without further tool calls.";
      String accepted =
          captureSession.accept(key, chainPlanGraph, successMessage, DUPLICATE_CAPTURE_MESSAGE);
      try {
        store.put(conversationId, chainPlanGraph);
      } catch (RuntimeException e) {
        captureSession.clearIfSame(key, chainPlanGraph);
        throw e;
      }
      repairDraftStore.remove(conversationId);
      feedbackStore.clearPlan(conversationId);

      LOG.infof(
          "captureChainPlan: stored plan conversationId=%s chain='%s' nodes=%d edges=%d",
          conversationId,
          chainName,
          nodeCount,
          edgeCount);
      return finish(conversationId, startMs, accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureChainPlan", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing plan: " + e.getMessage();
    }
  }

  private ChainPlanGraph toChainPlanGraph(ChainPlanCapture capture) {
    return new ChainPlanGraph(
        capture.schemaVersion(),
        capture.chain(),
        toChainPlanNodes(capture.nodes()),
        capture.edges());
  }

  private List<ChainPlanNode> toChainPlanNodes(List<ChainPlanNodeCapture> nodes) {
    if (nodes == null) {
      return null;
    }
    List<ChainPlanNode> converted = new ArrayList<>(nodes.size());
    for (ChainPlanNodeCapture node : nodes) {
      converted.add(toChainPlanNode(node));
    }
    return converted;
  }

  private ChainPlanNode toChainPlanNode(ChainPlanNodeCapture node) {
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.of());
  }

  static String resolveConversationId() {
    return ToolSession.resolveConversationId();
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureChainPlan", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}

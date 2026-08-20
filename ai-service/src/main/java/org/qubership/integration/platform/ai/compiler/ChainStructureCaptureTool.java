package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.edit.ChainEditScopeException;
import org.qubership.integration.platform.ai.chain.edit.ChainEditStructureBase;
import org.qubership.integration.platform.ai.chain.edit.ChainEditStructureMerge;
import org.qubership.integration.platform.ai.chain.edit.ChainEditSubgraphAssembly;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFeedbackChannel;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

/** Captures typed {@link ChainStructure} output for planning flow. */
@ApplicationScoped
public class ChainStructureCaptureTool {

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Structure generation did not capture chain structure. The agent must call"
          + " captureChainStructure with a valid graph before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Chain structure already captured. Do not call captureChainStructure again;"
          + " finish this turn without further tool calls.";

  private static final Logger LOG = Logger.getLogger(ChainStructureCaptureTool.class);

  private final CaptureSession captureSession;
  private final ChainPlanGraphValidator graphValidator;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureToolOutcomeGateway outcomeGateway;
  private final ChainStructurePropertySanitizer propertySanitizer;

  @Inject
  ChainStructureCaptureTool(
      CaptureSession captureSession,
      ChainPlanGraphValidator graphValidator,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureToolOutcomeGateway outcomeGateway,
      ChainStructurePropertySanitizer propertySanitizer) {
    this.captureSession = captureSession;
    this.graphValidator = graphValidator;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.outcomeGateway = outcomeGateway;
    this.propertySanitizer = propertySanitizer;
  }

  @Tool("""
      Capture chain structure with the first valid ChainPlanGraph revision.
      Do not pass conversationId — the server binds this capture to the current chat session.
      graph must be present and pass deterministic graph validation.
      Always copy configured http-trigger endpoint properties from ConfiguredTriggerSet
      (contextPath, httpMethodRestrict, externalRoute). Never emit properties:null on triggers.
      An edit that nests existing elements captures subgraph instead of graph: the container type,
      its branches, the elements each branch creates, and the ids of the existing elements that
      move into a branch. Never both.""")
  public String captureChainStructure(ChainStructure capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureChainStructure",
        conversationId,
        "preview=" + AiTraceLog.preview(previewCapture(capture), 400));
    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(conversationId, startMs, "conversationId is required (no active chat session)");
      }
      if (capture == null) {
        return finish(conversationId, startMs, "capture is required");
      }
      ChainEditStructureBase editBase = editBase(conversationId);
      ChainStructure shaped;
      try {
        shaped = withGraphAssembledFromSubgraph(capture, editBase);
      } catch (IllegalArgumentException e) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId,
                capture,
                captureFailureClass(e),
                "Structure validation failed:\n" + e.getMessage()));
      }
      if (shaped.graph() == null) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId, capture, CaptureFailureClass.CORRECTABLE, "graph is required"));
      }
      ChainStructurePropertySanitizer.SanitizationResult sanitized =
          propertySanitizer.sanitize(shaped);
      for (ChainStructurePropertySanitizer.RemovedProperty removed :
          sanitized.removedProperties()) {
        LOG.warnf(
            "Stripped schema-unknown structure property"
                + " conversationId=%s nodeId=%s elementType=%s key=%s",
            conversationId,
            removed.nodeId(),
            removed.elementType(),
            removed.key());
      }
      ChainStructure normalized =
          mergeConfiguredTriggerProperties(conversationId, sanitized.structure());
      ChainPlanGraph graphUnderTest;
      try {
        graphUnderTest = asMergedOntoEditedChain(editBase, normalized.graph());
      } catch (IllegalArgumentException e) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId,
                capture,
                captureFailureClass(e),
                "Structure validation failed:\n" + e.getMessage()));
      }
      List<String> errors = graphValidator.validate(graphUnderTest);
      if (!errors.isEmpty()) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId,
                capture,
                CaptureFailureClass.CORRECTABLE,
                "Structure validation failed:\n" + String.join("\n", errors)));
      }

      String accepted =
          captureSession.accept(
              CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, conversationId),
              normalized,
              "Chain structure captured. Do not call captureChainStructure again;"
                  + " finish this turn without further tool calls.",
              DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureChainStructure", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing chain structure: " + e.getMessage();
    }
  }

  /**
   * The chain this capture is editing, or {@code null} for a CREATE run that plans a new one.
   */
  private ChainEditStructureBase editBase(String conversationId) {
    return captureSession
        .get(
            CaptureKey.conversation(CaptureSlot.CHAIN_EDIT_STRUCTURE_BASE, conversationId),
            ChainEditStructureBase.class)
        .orElse(null);
  }

  /**
   * Assembles the graph a nesting edit proposes, so the rest of the capture sees a graph either
   * way.
   *
   * <p>A nesting edit captures what it adds and nothing else, which is what keeps it from
   * enclosing an element the reader never named. The whole-graph shape is refused for such an edit
   * rather than merged, because accepting both would leave the defect this contract removes
   * reachable through the older field.
   */
  private static ChainStructure withGraphAssembledFromSubgraph(
      ChainStructure capture, ChainEditStructureBase editBase) {
    boolean nesting = editBase != null && editBase.intent().capturesSubgraph();
    if (!nesting) {
      if (capture.subgraph() != null) {
        throw new IllegalArgumentException(
            "subgraph describes what a nesting edit adds, and this run is not one."
                + " Capture the graph instead.");
      }
      return capture;
    }
    if (capture.subgraph() == null) {
      throw new IllegalArgumentException(
          "This edit nests existing elements, so capture subgraph rather than graph: the container"
              + " type, its branches, the elements each branch creates, and in moveExisting the ids"
              + " of the existing elements that move into a branch.");
    }
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            editBase.baseGraph(), capture.subgraph(), editBase.intent());
    return new ChainStructure(
        assembled, capture.sourceRequirementFactIds(), capture.knowledgeCitations());
  }

  /**
   * Returns the graph this capture actually produces, so validation judges that and not a draft.
   *
   * <p>An edit that captures a whole chain has it merged onto the imported one before anything is
   * built: the merge restores connections the capture dropped and pins fields it echoed
   * differently. Validating the raw capture therefore reports defects the merge repairs, and
   * misses none it does not. A CREATE run publishes no base, and its capture is the whole graph
   * already.
   *
   * <p>A nesting edit was assembled from its subgraph, which leaves the merge nothing to decide.
   *
   * <p>A merge the compiler would refuse is raised as an {@link IllegalArgumentException} here, one
   * turn earlier than before, so the generator is asked to correct it while it still can.
   */
  private static ChainPlanGraph asMergedOntoEditedChain(
      ChainEditStructureBase editBase, ChainPlanGraph captured) {
    if (editBase == null || editBase.intent().capturesSubgraph()) {
      return captured;
    }
    return ChainEditStructureMerge.merge(editBase.baseGraph(), captured, editBase.intent());
  }

  private String repairable(
      String conversationId,
      ChainStructure capture,
      CaptureFailureClass failureClass,
      String message) {
    return outcomeGateway.onFailure(
        CaptureFeedbackChannel.PLAN,
        conversationId,
        null,
        CaptureFailureKind.VALIDATION,
        failureClass,
        "captureChainStructure",
        capture,
        message);
  }

  /**
   * Preserves {@link ConfiguredTriggerSet} endpoint fields when structure capture omits or nulls
   * trigger properties. Does not invent values — only copies already-captured trigger properties.
   */
  ChainStructure mergeConfiguredTriggerProperties(String conversationId, ChainStructure capture) {
    ConfiguredTriggerSet triggerSet =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId),
                ConfiguredTriggerSet.class)
            .orElse(null);
    ChainPlanGraph graph = capture == null ? null : capture.graph();
    ChainPlanGraph merged = ConfiguredTriggerSetGraphEnricher.enrich(graph, triggerSet);
    if (merged == null || merged == graph) {
      return capture;
    }
    LOG.infof(
        "Merged ConfiguredTriggerSet properties into chain structure conversationId=%s",
        conversationId);
    return new ChainStructure(
        merged, capture.sourceRequirementFactIds(), capture.knowledgeCitations());
  }

  /**
   * Classifies a refused capture. A scope refusal the intent itself causes is PERMANENT: the
   * generator is being asked for a capture that cannot exist, so a soft retry would spend the
   * turn restating an impossible request. Everything else is a capture the generator can correct.
   */
  private static CaptureFailureClass captureFailureClass(IllegalArgumentException failure) {
    if (failure instanceof ChainEditScopeException scope && scope.unsatisfiable()) {
      return CaptureFailureClass.PERMANENT;
    }
    return CaptureFailureClass.CORRECTABLE;
  }

  private String previewCapture(ChainStructure capture) {
    if (capture == null) {
      return "null";
    }
    try {
      return objectMapper.writeValueAsString(capture);
    } catch (Exception e) {
      return capture.toString();
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureChainStructure", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}

package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.edit.ChainEditDisposition;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditScopeException;
import org.qubership.integration.platform.ai.chain.edit.ChainEditStructureBase;
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
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.mapping.MappingStructurePhase;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

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
  private final CatalogElementDescriptorLoader descriptorLoader;

  @Inject
  ChainStructureCaptureTool(
      CaptureSession captureSession,
      ChainPlanGraphValidator graphValidator,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureToolOutcomeGateway outcomeGateway,
      ChainStructurePropertySanitizer propertySanitizer,
      CatalogElementDescriptorLoader descriptorLoader) {
    this.captureSession = captureSession;
    this.graphValidator = graphValidator;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.outcomeGateway = outcomeGateway;
    this.propertySanitizer = propertySanitizer;
    this.descriptorLoader = descriptorLoader;
  }

  @Tool("""
      Capture the whole chain graph. Use this when planning a NEW chain, not when editing one.
      Do not pass conversationId — the server binds this capture to the current chat session.
      graph must be present and pass deterministic graph validation.
      Always copy configured http-trigger endpoint properties from ConfiguredTriggerSet
      (contextPath, httpMethodRestrict, externalRoute). Never emit properties:null on triggers.
      To change a chain that already exists, call captureChainEditSubgraph instead.""")
  public String captureChainStructure(ChainStructure capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureChainStructure",
        conversationId,
        "preview=" + AiTraceLog.preview(previewCapture(capture), 400));
    if (conversationId == null || conversationId.isBlank()) {
      return finish(conversationId, startMs, "conversationId is required (no active chat session)");
    }
    ChainEditStructureBase editBase = editBase(conversationId);
    // Ahead of the null check on purpose. A generator that reaches for the CREATE tool on an edit
    // run often calls it with no argument at all, and "capture is required" answers a question it
    // did not ask: the tool is wrong for this run whatever the argument was. Saying so here keeps
    // the redirect reachable instead of ending the turn on a message that names no next step.
    if (editBase != null && editBase.intent().capturesSubgraph()) {
      return finish(
          conversationId,
          startMs,
          repairable(
              conversationId,
              capture,
              CaptureFailureClass.CORRECTABLE,
              "This run edits a chain that already exists, so the whole graph is not the capture"
                  + " for it. Call captureChainEditSubgraph instead. "
                  + subgraphRequiredMessage(editBase.intent())));
    }
    if (capture == null) {
      return finish(conversationId, startMs, "capture is required");
    }
    ChainStructure shaped;
    try {
      shaped = wholeGraphCapture(capture, editBase);
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
    return completeCapture(conversationId, startMs, shaped, capture);
  }

  /**
   * Sanitizes, validates, and stores an already-shaped capture, whichever tool produced it.
   *
   * <p>Both capture tools converge here, so a CREATE graph and an assembled edit graph are held to
   * the same deterministic validation and reach the session through one path.
   */
  private String completeCapture(
      String conversationId, long startMs, ChainStructure shaped, Object fingerprintSource) {
    ChainStructure capture = shaped;
    try {
      if (shaped.graph() == null) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId,
                fingerprintSource,
                CaptureFailureClass.CORRECTABLE,
                "graph is required"));
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
      try {
        normalized = placeMappingShells(conversationId, normalized);
      } catch (IllegalStateException e) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId,
                fingerprintSource,
                CaptureFailureClass.CORRECTABLE,
                "Structure validation failed:\n" + e.getMessage()));
      }
      ChainPlanGraph graphUnderTest = normalized.graph();
      List<String> errors = graphValidator.validate(graphUnderTest);
      if (!errors.isEmpty()) {
        return finish(
            conversationId,
            startMs,
            repairable(
                conversationId,
                fingerprintSource,
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
   * The capture for a CREATE run.
   *
   * <p>An edit captures only what it adds, through {@code captureChainEditSubgraph}. Accepting a
   * whole graph here as well would leave the defect this contract removes reachable through the
   * older shape: a graph that re-states every element lets the generator reparent, drop, or rewrite
   * one the reader never named, and Java could only refuse that afterwards. An edit run never
   * reaches this method: {@link #captureChainStructure} redirects it to the subgraph tool first,
   * before the argument is examined at all.
   */
  private static ChainStructure wholeGraphCapture(
      ChainStructure capture, ChainEditStructureBase editBase) {
    if (capture.subgraph() != null) {
      throw new IllegalArgumentException(
          "subgraph belongs to captureChainEditSubgraph. Capture graph here, or call that tool.");
    }
    return capture;
  }

  /**
   * Captures what a structural edit adds, and assembles the chain it produces.
   *
   * <p>Its own tool rather than a second field on the CREATE capture, because the shape a run needs
   * is something Java already knows and the generator should not have to choose. The parameter here
   * has no {@code graph} field at all, so an edit cannot answer with a whole chain the way it could
   * while both shapes shared one capture.
   */
  @Tool("""
      Capture what a structural edit ADDS to a chain that already exists. Use this for any wrap,
      insertion, or replacement on the open chain, and never captureChainStructure.
      Do not pass conversationId — the server binds this capture to the current chat session.
      A wrap names containerType and one branch per child the container has; each branch names its
      childType, lists in moveExisting the ids of the existing elements that move into it, and puts
      the elements it creates in its own body.
      An insertion or a replacement names no container: its new elements and the connections
      between them go in the top-level body.
      Never name an existing element anywhere except in moveExisting, and never give a new element a
      parent — the branch it is declared in is where it nests. Java places the result and reconnects
      the chain around it.""")
  public String captureChainEditSubgraph(ChainEditSubgraph subgraph) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureChainEditSubgraph",
        conversationId,
        "preview=" + AiTraceLog.preview(previewCapture(subgraph), 400));
    if (conversationId == null || conversationId.isBlank()) {
      return finish(conversationId, startMs, "conversationId is required (no active chat session)");
    }
    if (subgraph == null) {
      return finish(conversationId, startMs, "subgraph is required");
    }
    ChainEditStructureBase editBase = editBase(conversationId);
    if (editBase == null || !editBase.intent().capturesSubgraph()) {
      return finish(
          conversationId,
          startMs,
          repairable(
              conversationId,
              subgraph,
              CaptureFailureClass.PERMANENT,
              "This run plans a new chain, so there is nothing to add a subgraph to."
                  + " Call captureChainStructure with the whole graph instead."));
    }
    ChainStructure shaped;
    try {
      shaped =
          new ChainStructure(
              ChainEditSubgraphAssembly.assemble(
                  editBase.baseGraph(),
                  subgraph,
                  editBase.intent(),
                  new CatalogElementDescriptorCache(descriptorLoader)),
              List.of(),
              List.of());
    } catch (IllegalArgumentException e) {
      return finish(
          conversationId,
          startMs,
          repairable(
              conversationId,
              subgraph,
              captureFailureClass(e),
              "Structure validation failed:\n" + e.getMessage()));
    }
    return completeCapture(conversationId, startMs, shaped, subgraph);
  }

  /** Tells the generator what to name instead, for whichever of the four subgraph shapes applies. */
  private static String subgraphRequiredMessage(ChainEditIntent intent) {
    if (intent.disposition() == ChainEditDisposition.KEEP) {
      return "This edit inserts elements at an address, so capture subgraph rather than graph: no"
          + " container, and in body the new elements and the connections between them.";
    }
    if (intent.disposition() == ChainEditDisposition.REMOVE) {
      return "This edit replaces an existing element, so capture subgraph rather than graph: no"
          + " container, and in body the new elements and the connections between them. Do not"
          + " name the replaced element anywhere in the capture -- Java removes it and reconnects"
          + " its neighbours to the body automatically.";
    }
    if (intent.disposition() == ChainEditDisposition.ATTACH) {
      return "This edit adds a branch to a container the chain already has, so capture subgraph"
          + " rather than graph: no containerType -- the container is not new -- and exactly one"
          + " branch, naming its child type and the elements it creates.";
    }
    return "This edit nests existing elements, so capture subgraph rather than graph: the container"
        + " type, its branches, the elements each branch creates, and in moveExisting the ids"
        + " of the existing elements that move into a branch.";
  }

  private String repairable(
      String conversationId,
      Object fingerprintSource,
      CaptureFailureClass failureClass,
      String message) {
    return outcomeGateway.onFailure(
        CaptureFeedbackChannel.PLAN,
        conversationId,
        null,
        CaptureFailureKind.VALIDATION,
        failureClass,
        "captureChainStructure",
        fingerprintSource,
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

  private String previewCapture(Object capture) {
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

  private ChainStructure placeMappingShells(String conversationId, ChainStructure structure) {
    return captureSession
        .get(
            CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId),
            RequirementBrief.class)
        .filter(brief -> !brief.mappingIntents().isEmpty() && structure.graph() != null)
        .map(brief -> placedStructure(structure, brief))
        .orElse(structure);
  }

  private static ChainStructure placedStructure(ChainStructure structure, RequirementBrief brief) {
    ChainPlanGraph placed = MappingStructurePhase.placeShells(structure.graph(), brief);
    if (placed == structure.graph()) {
      return structure;
    }
    return new ChainStructure(
        placed,
        structure.sourceRequirementFactIds(),
        structure.knowledgeCitations(),
        structure.subgraph());
  }
}

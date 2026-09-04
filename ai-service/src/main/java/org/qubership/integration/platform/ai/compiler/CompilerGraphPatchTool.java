package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceIds;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.mapping.MappingCaptureValidator;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.patch.ChainPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchShapeValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * LangChain4j tool for compiler skill agents to persist a {@link GraphPatch} in
 * the same turn.
 *
 * <p>
 * Conversation id and capability id are bound by the runtime, not supplied by the model.
 */
@ApplicationScoped
public class CompilerGraphPatchTool {

  public static final String CAPTURE_REQUIRED_MESSAGE = "Compiler skill did not capture a graph patch. The agent must call captureGraphPatch"
      + " with a valid GraphPatch before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Graph patch already captured. Do not call captureGraphPatch again;"
          + " finish this turn without further tool calls.";

  private static final String TRANSFORMATION_GENERATOR = "cip-transformation-generator";
  private static final String SCRIPT_GENERATOR = "cip-script-generator";
  private static final String MAPPING_CAPTURE_PREFIX = "Mapping capture:";

  private static final Logger LOG = Logger.getLogger(CompilerGraphPatchTool.class);

  private final CaptureSession captureSession;
  private final ChainPlanStore planStore;
  private final DeterministicElementSchemaService schemaService;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final QipKnowledgePackRepository packRepository;
  private final GeneratorReadinessEvaluator readinessEvaluator;
  private final GraphPatchApplier patchApplier;
  private final CaptureRepairMessageBuilder repairMessageBuilder;
  private final GraphPatchExecutionContextStore executionContextStore;
  private final CaptureRouter captureRouter;
  private final KnowledgeCitationResolver citationResolver;
  private final ValidatedGraphPatchApplier validatedPatchApplier;
  private final GraphPatchPreviewValidator previewValidator;
  private final CompilationArtifacts compilationArtifacts;
  private final MappingCaptureValidator mappingCaptureValidator;

  @Inject
  CompilerGraphPatchTool(
      CaptureSession captureSession,
      ChainPlanStore planStore,
      DeterministicElementSchemaService schemaService,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      QipKnowledgePackRepository packRepository,
      GeneratorReadinessEvaluator readinessEvaluator,
      GraphPatchApplier patchApplier,
      CaptureRepairMessageBuilder repairMessageBuilder,
      GraphPatchExecutionContextStore executionContextStore,
      CaptureRouter captureRouter,
      KnowledgeCitationResolver citationResolver,
      CompilationArtifacts compilationArtifacts) {
    this.captureSession = captureSession;
    this.planStore = planStore;
    this.schemaService = schemaService;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.packRepository = packRepository;
    this.readinessEvaluator = readinessEvaluator;
    this.patchApplier = patchApplier;
    this.repairMessageBuilder = repairMessageBuilder;
    this.executionContextStore = executionContextStore;
    this.captureRouter = captureRouter;
    this.citationResolver = citationResolver;
    this.compilationArtifacts = compilationArtifacts;
    this.mappingCaptureValidator = new MappingCaptureValidator();
    this.validatedPatchApplier =
        new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), patchApplier);
    this.previewValidator =
        new GraphPatchPreviewValidator(
            validatedPatchApplier,
            patchApplier,
            new org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator(schemaService),
            readinessEvaluator,
            new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                objectMapper));
  }

  @Tool("""
      Capture the compiler skill output as a typed GraphPatch object in the same turn.
      Do not pass conversationId or capabilityId — the server binds them automatically.
      If the skill is not applicable, set notApplicable=true and keep all patch arrays empty
      (do not invent node/edge/property/chain patches). Omit notApplicable or set false for
      normal patches.
      ownerCapabilityId must match the skill being executed.
      GraphPatch shape:
      {
        "patchId": "unique-id",
        "ownerCapabilityId": "cip-error-handling-generator",
        "notApplicable": false,
        "nodePatches": [],
        "edgePatches": [],
        "propertyPatches": [
          {"operation": "ADD", "targetNodeId": "http-trigger-1", "key": "accessControlType",
           "value": "RBAC"},
          {"operation": "ADD", "targetNodeId": "http-trigger-1", "key": "roles",
           "value": ["qip-viewer"]}
        ],
        "chainPatches": [],
        "usedKnowledgeRefs": ["exact-refId-from-runtime-context"],
        "rationale": "why this patch was or was not generated"
      }
      Node and edge patches use ADD, UPDATE, or REMOVE operations with ChainPlanNode/ChainPlanEdge bodies.
      node.properties must be an array of {key,value} objects. Prefer properties:[] when only changing labels.
      Never send a bare Groovy string or string array as properties.
      Only cip-script-generator may set property key "script" (bodies). Other skills must omit it.
      Property and chain patches use key plus structured value (string, number, boolean, array, or object).
      Do not put JSON arrays or objects inside string values.""")
  public String captureGraphPatch(GraphPatchCapture patch) {

    String conversationId = resolveConversationId();
    String capabilityId = resolveCapabilityId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureGraphPatch",
        conversationId,
        "capabilityId="
            + capabilityId
            + " patchId="
            + (patch != null ? patch.patchId() : "null")
            + " notApplicable="
            + (patch != null && patch.isNotApplicable())
            + " rationale="
            + AiTraceLog.preview(patch != null ? patch.rationale() : null, 120));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(conversationId, startMs, "conversationId is required (no active chat session)");
      }
      if (capabilityId == null || capabilityId.isBlank()) {
        return finish(conversationId, startMs, "compiler skill capabilityId is required (runtime binding)");
      }
      Optional<String> routeMismatch = rejectIfWrongCaptureRoute(capabilityId);
      if (routeMismatch.isPresent()) {
        String message = routeMismatch.get();
        return finish(
            conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
      }
      if (patch == null) {
        return finish(conversationId, startMs, "patch is required");
      }

      if (patch.isNotApplicable() && captureHasPatchOperations(patch)) {
        String message =
            "notApplicable=true requires empty nodePatches, edgePatches, propertyPatches, and"
                + " chainPatches; remove invented patches or set notApplicable=false";
        return finish(
            conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
      }

      GraphPatch graphPatch;
      try {
        graphPatch = toGraphPatch(conversationId, patch);
      } catch (IllegalArgumentException e) {
        String message = "Invalid property value: " + e.getMessage();
        boolean repeated =
            feedbackStore.recordPatchConversionFailure(conversationId, capabilityId, message);
        if (repeated) {
          finish(conversationId, startMs, message);
          throw new CaptureValidationException(
              "Repeated graph patch conversion failure: " + message);
        }
        return finish(conversationId, startMs, message);
      }

      if (graphPatch.ownerCapabilityId() == null || !capabilityId.equals(graphPatch.ownerCapabilityId())) {
        String message = "ownerCapabilityId must be '" + capabilityId + "'";
        return finish(conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
      }

      Optional<String> scriptPatchError =
          ScriptBodyPromptRedaction.validatePatch(capabilityId, graphPatch);
      if (scriptPatchError.isPresent()) {
        // Fail closed: ownership of key "script" is enforced in-tool so the LLM cannot bypass via
        // soft retries. CaptureRepairRunner retries with the recorded validation feedback.
        String message = scriptPatchError.get();
        feedbackStore.recordPatchValidationFailure(conversationId, capabilityId, message);
        finish(conversationId, startMs, message);
        throw new CaptureValidationException(message);
      }

      List<String> shapeErrors = GraphPatchShapeValidator.validate(graphPatch);
      if (!shapeErrors.isEmpty()) {
        String message = "Invalid graph patch shape: " + GraphPatchShapeValidator.summarize(shapeErrors);
        return finish(conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
      }

      PreviewOutcome preview = runPreview(conversationId, capabilityId, graphPatch);
      if (preview.failure().isPresent()) {
        Optional<GraphPatchCapture> recovered =
            ErrorHandlingGraphPatchRecovery.recover(
                capabilityId,
                resolvePreviewBaseGraph(conversationId, capabilityId),
                patch,
                preview.failure().get());
        if (recovered.isPresent()) {
          LOG.infof(
              "captureGraphPatch: recovered EH ADD on existing topology conversationId=%s"
                  + " capabilityId=%s fromPatchId=%s toPatchId=%s notApplicable=%s",
              conversationId,
              capabilityId,
              patch.patchId(),
              recovered.get().patchId(),
              recovered.get().isNotApplicable());
          patch = recovered.get();
          graphPatch = toGraphPatch(conversationId, patch);
          preview = runPreview(conversationId, capabilityId, graphPatch);
        }
      }
      if (preview.failure().isPresent()) {
        String message = preview.failure().get();
        return finish(
            conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
      }

      ChainPlanGraph graphForGate = preview.graphForGate();
      if (graphForGate != null) {
        GraphPatchOwnershipPolicy ownership =
            executionContextStore
                .get(conversationId, capabilityId)
                .or(executionContextStore::current)
                .map(GraphPatchExecutionContext::ownership)
                .orElse(GraphPatchOwnershipPolicy.denyAll());
        List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
            OwnedSchemaRequiredPropertyGate.findGaps(
                graphForGate, ownership, schemaService::requiredPatchPropertyKeys);
        if (!gaps.isEmpty()) {
          String message =
              OwnedSchemaRequiredPropertyGate.formatCorrectableMessage(capabilityId, gaps);
          return finish(
              conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
        }
      }

      Optional<String> mappingCaptureError =
          validateMappingCapture(conversationId, capabilityId, graphPatch, graphForGate);
      if (mappingCaptureError.isPresent()) {
        String message = mappingCaptureError.get();
        return finish(
            conversationId, startMs, wrapValidationMessage(conversationId, capabilityId, message));
      }
      graphPatch =
          withHopBodyMappingCoverage(conversationId, capabilityId, graphPatch, graphForGate);

      CaptureKey key =
          CaptureKey.capability(CaptureSlot.GRAPH_PATCH, conversationId, capabilityId);
      String successMessage =
          patch.isNotApplicable()
              ? "Graph patch captured (notApplicable): patchId='"
                  + graphPatch.patchId()
                  + "', rationale="
                  + AiTraceLog.preview(graphPatch.rationale(), 120)
                  + ". Do not call captureGraphPatch again;"
                  + " finish this turn without further tool calls."
              : "Graph patch captured: patchId='"
                  + graphPatch.patchId()
                  + "', rationale="
                  + AiTraceLog.preview(graphPatch.rationale(), 120)
                  + ". Do not call captureGraphPatch again;"
                  + " finish this turn without further tool calls.";
      String accepted =
          captureSession.accept(key, graphPatch, successMessage, DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPatch(conversationId, capabilityId);
      LOG.infof(
          "captureGraphPatch: stored conversationId=%s capabilityId=%s patchId=%s notApplicable=%s nodePatches=%d edgePatches=%d propertyPatches=%d chainPatches=%d",
          conversationId,
          capabilityId,
          graphPatch.patchId(),
          patch.isNotApplicable(),
          graphPatch.nodePatches() != null ? graphPatch.nodePatches().size() : 0,
          graphPatch.edgePatches() != null ? graphPatch.edgePatches().size() : 0,
          graphPatch.propertyPatches() != null ? graphPatch.propertyPatches().size() : 0,
          graphPatch.chainPatches() != null ? graphPatch.chainPatches().size() : 0);
      // Terminal signal: PreventsErrorHandlerExecution aborts the streaming tool loop so
      // CaptureRepairRunner can complete and harvest can run without waiting for an LLM end-turn.
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureGraphPatch", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing graph patch: " + e.getMessage();
    }
  }

  private String wrapValidationMessage(
      String conversationId, String capabilityId, String message) {
    boolean repeated =
        feedbackStore.recordPatchValidationFailure(conversationId, capabilityId, message);
    if (repeated) {
      throw new CaptureValidationException(
          "Repeated graph patch validation failure: " + message);
    }
    return message;
  }

  /**
   * Shared {@code CompilerSkillAgent} also exposes this tool to naming/trigger skills. Reject when
   * the capability's addon route is not a graph-patch harvest path so the model retries with the
   * correct capture tool (for example {@code captureConfiguredTriggerSet}).
   */
  private Optional<String> rejectIfWrongCaptureRoute(String capabilityId) {
    if (captureRouter == null) {
      return Optional.empty();
    }
    try {
      CaptureRoute route = captureRouter.routeFor(capabilityId);
      if (route.captureTool() == CaptureTool.CAPTURE_GRAPH_PATCH
          || route.captureTool() == CaptureTool.REPAIR_SCRIPT_BODIES) {
        return Optional.empty();
      }
      return Optional.of(
          "captureGraphPatch is not valid for "
              + capabilityId
              + "; call "
              + route.captureTool().toolName()
              + " instead (this skill harvests "
              + route.captureTool().toolName()
              + ", not GRAPH_PATCH).");
    } catch (IllegalStateException e) {
      return Optional.of(e.getMessage());
    }
  }

  private ChainPlanGraph resolvePreviewBaseGraph(String conversationId, String capabilityId) {
    GraphPatchExecutionContext executionContext =
        executionContextStore
            .get(conversationId, capabilityId)
            .or(executionContextStore::current)
            .filter(context -> context.inputGraph() != null)
            .orElse(null);
    if (executionContext != null) {
      return executionContext.inputGraph();
    }
    return planStore.get(conversationId).orElse(null);
  }

  private record PreviewOutcome(Optional<String> failure, ChainPlanGraph graphForGate) {}

  private PreviewOutcome runPreview(
      String conversationId, String capabilityId, GraphPatch graphPatch) {
    GraphPatchExecutionContext executionContext =
        executionContextStore
            .get(conversationId, capabilityId)
            .or(executionContextStore::current)
            .filter(context -> context.inputGraph() != null)
            .orElse(null);
    ChainPlanGraph base =
        executionContext != null
            ? executionContext.inputGraph()
            : planStore.get(conversationId).orElse(null);
    CompilerGeneratorPolicy policy = packRepository.loadCompilerGeneratorPolicy();
    List<String> declared = policy.readinessSignalsFor(capabilityId);
    if (base == null) {
      return new PreviewOutcome(Optional.empty(), null);
    }
    if (!hasPatchOperations(graphPatch) && declared.isEmpty()) {
      return new PreviewOutcome(Optional.empty(), base);
    }
    GraphPatchPreviewValidator.GraphPatchPreviewResult preview =
        previewValidator.validate(base, graphPatch, executionContext, declared);
    if (preview.pass()) {
      return new PreviewOutcome(Optional.empty(), preview.patchedGraph());
    }
    String message;
    if (GraphPatchPreviewValidator.digestMismatch(executionContext, preview.inputGraphDigest())) {
      message = "contract failure: capture input graph digest mismatch";
    } else if (!preview.ownershipResult().valid()) {
      message = "Patch apply failed: " + preview.ownershipResult().summary();
    } else if (!preview.structuralValidation().isEmpty()) {
      message =
          "Patch produced invalid graph: " + String.join("; ", preview.structuralValidation());
    } else if (!preview.readinessGaps().isEmpty()) {
      message = completenessSummary(preview.readinessGaps(), preview.patchedGraph());
    } else {
      message = "Graph patch preview validation failed";
    }
    return new PreviewOutcome(Optional.of(message), preview.patchedGraph());
  }

  private String completenessSummary(List<String> unmet, ChainPlanGraph graph) {
    List<String> modelRepairSignals =
        unmet.stream()
            .filter(signal -> !"incomplete_service_call_bindings".equals(signal))
            .toList();
    StringBuilder summary =
        new StringBuilder(repairMessageBuilder.completenessSummary(modelRepairSignals));
    if (unmet.contains("script_nodes_missing_body")) {
      List<String> missing = readinessEvaluator.scriptNodesMissingBody(graph);
      if (!missing.isEmpty()) {
        summary
            .append(" Missing script node ids: ")
            .append(String.join(", ", missing))
            .append(". Submit one patch with propertyPatches for all listed targetNodeIds.");
      }
    }
    if (unmet.contains("incomplete_service_call_bindings")) {
      List<String> missing = readinessEvaluator.serviceCallNodesMissingBindings(graph);
      if (!missing.isEmpty()) {
        summary
            .append(" Service-call server binding is missing or stale on node ids: ")
            .append(String.join(", ", missing))
            .append(". Catalog identity is server-owned; do not submit propertyPatches for it.");
      }
      readinessEvaluator
          .serviceCallBindingSchemaFailure(graph)
          .ifPresent(detail -> summary.append(" Schema detail: ").append(detail));
    }
    return summary.toString();
  }

  private GraphPatch toGraphPatch(String conversationId, GraphPatchCapture patch) {
    return new GraphPatch(
        patch.patchId(),
        patch.ownerCapabilityId(),
        patch.nodePatches(),
        patch.edgePatches(),
        toPropertyPatches(conversationId, patch, patch.propertyPatches()),
        toChainPatches(patch.chainPatches()),
        citationResolver.resolve(conversationId, patch.usedKnowledgeRefs()),
        patch.rationale());
  }

  private List<PropertyPatch> toPropertyPatches(
      String conversationId, GraphPatchCapture patch, List<PropertyPatchCapture> patches) {
    if (patches == null) {
      return null;
    }
    List<PropertyPatch> converted = new ArrayList<>(patches.size());
    for (PropertyPatchCapture propertyPatch : patches) {
      converted.add(toPropertyPatch(conversationId, patch, propertyPatch));
    }
    return converted;
  }

  private PropertyPatch toPropertyPatch(
      String conversationId, GraphPatchCapture patch, PropertyPatchCapture propertyPatch) {
    String elementType = resolveTargetNodeType(conversationId, patch, propertyPatch.targetNodeId());
    if (elementType != null) {
      Optional<String> validationError = schemaService.validateCapturePropertyValue(
          elementType, propertyPatch.key(), propertyPatch.value());
      if (validationError.isPresent()) {
        throw new IllegalArgumentException(
            "node '"
                + propertyPatch.targetNodeId()
                + "' ("
                + elementType
                + ") property '"
                + propertyPatch.key()
                + "': "
                + validationError.get());
      }
    }
    try {
      String asString = propertyValueToString(propertyPatch.value());
      if (asString != null
          && !asString.isBlank()
          && !"script".equals(propertyPatch.key())
          && !OwnedSchemaRequiredPropertyGate.isRealValue(asString)) {
        throw new IllegalArgumentException(
            "node '"
                + propertyPatch.targetNodeId()
                + "' property '"
                + propertyPatch.key()
                + "' must not use placeholder tokens; resolve a real value from the requirement"
                + " brief or catalog");
      }
      return new PropertyPatch(
          propertyPatch.operation(),
          propertyPatch.targetNodeId(),
          new PlanProperty(propertyPatch.key(), asString));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private String resolveTargetNodeType(
      String conversationId, GraphPatchCapture patch, String targetNodeId) {
    if (targetNodeId == null || targetNodeId.isBlank()) {
      return null;
    }
    Optional<ChainPlanGraph> graph = planStore.get(conversationId);
    if (graph.isPresent() && graph.get().nodes() != null) {
      for (ChainPlanNode node : graph.get().nodes()) {
        if (targetNodeId.equals(node.nodeId())) {
          return node.type();
        }
      }
    }
    if (patch.nodePatches() != null) {
      for (NodePatch nodePatch : patch.nodePatches()) {
        if (nodePatch.node() != null && targetNodeId.equals(nodePatch.node().nodeId())) {
          return nodePatch.node().type();
        }
      }
    }
    return null;
  }

  private List<ChainPatch> toChainPatches(List<ChainPatchCapture> patches) {
    if (patches == null) {
      return Collections.emptyList();
    }
    List<ChainPatch> converted = new ArrayList<>(patches.size());
    for (ChainPatchCapture patch : patches) {
      converted.add(toChainPatch(patch));
    }
    return converted;
  }

  private ChainPatch toChainPatch(ChainPatchCapture patch) {
    try {
      return new ChainPatch(
          patch.operation(), new PlanProperty(patch.key(), propertyValueToString(patch.value())));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private String propertyValueToString(JsonNode value) throws JsonProcessingException {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isTextual()) {
      return value.asText();
    }
    return objectMapper.writeValueAsString(value);
  }

  static String resolveConversationId() {
    return ToolSession.resolveConversationId();
  }

  static String resolveCapabilityId() {
    Object mdcValue = MDC.get(CompilerSkillMdc.CAPABILITY_ID);
    if (mdcValue != null) {
      String id = mdcValue.toString().trim();
      if (!id.isBlank()) {
        return id;
      }
    }
    return ToolInvocationSink.currentParentSkillId()
        .map(EvidenceIds::strip)
        .filter(id -> !id.isBlank())
        .orElse(null);
  }

  private static boolean hasPatchOperations(GraphPatch patch) {
    return !empty(patch.nodePatches())
        || !empty(patch.edgePatches())
        || !empty(patch.propertyPatches())
        || !empty(patch.chainPatches());
  }

  private static boolean captureHasPatchOperations(GraphPatchCapture patch) {
    return !empty(patch.nodePatches())
        || !empty(patch.edgePatches())
        || !empty(patch.propertyPatches())
        || !empty(patch.chainPatches());
  }

  private static boolean empty(List<?> values) {
    return values == null || values.isEmpty();
  }

  private Optional<String> validateMappingCapture(
      String conversationId,
      String capabilityId,
      GraphPatch graphPatch,
      ChainPlanGraph graphForGate) {
    if (!TRANSFORMATION_GENERATOR.equals(capabilityId) && !SCRIPT_GENERATOR.equals(capabilityId)) {
      return Optional.empty();
    }
    if (!touchesMappingCaptureKeys(capabilityId, graphPatch)) {
      return Optional.empty();
    }
    if (graphForGate == null) {
      return Optional.of(MAPPING_CAPTURE_PREFIX + " patched graph is required");
    }
    GraphPatchExecutionContext context =
        executionContextStore
            .get(conversationId, capabilityId)
            .or(executionContextStore::current)
            .orElse(null);
    try {
      for (String nodeId : patchedNodeIds(graphPatch)) {
        ChainPlanNode node = findNode(graphForGate, nodeId);
        if (node == null) {
          continue;
        }
        if (TRANSFORMATION_GENERATOR.equals(capabilityId)
            && nodeTouchesKey(
                graphPatch, nodeId, MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY)) {
          validateMapper2Capture(conversationId, context, node);
        }
        if (SCRIPT_GENERATOR.equals(capabilityId)
            && (nodeTouchesKey(graphPatch, nodeId, MappingExecutionSite.SCRIPT_PROPERTY)
                || nodeTouchesKey(
                    graphPatch, nodeId, MappingExecutionSite.MAPPING_COVERAGE_PROPERTY))) {
          validateScriptCapture(conversationId, context, node, graphPatch, nodeId);
        }
      }
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.of(e.getMessage());
    }
  }

  private void validateMapper2Capture(
      String conversationId, GraphPatchExecutionContext context, ChainPlanNode node) {
    if (!MappingExecutionSite.isMapper2(node)) {
      throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " node type must be mapper-2");
    }
    MappingIntent intent = requireIntent(context, MappingExecutionSite.mappingIntentId(node));
    MappingEnvelope envelope =
        requireEnvelope(conversationId, context, MappingExecutionSite.mappingIntentId(node));
    MappingDescriptionDocument captured =
        parseMappingDescription(MappingExecutionSite.mappingDescription(node));
    mappingCaptureValidator.validateMapper2(envelope, intent, captured);
  }

  private void validateScriptCapture(
      String conversationId,
      GraphPatchExecutionContext context,
      ChainPlanNode node,
      GraphPatch graphPatch,
      String nodeId) {
    String intentId = MappingExecutionSite.mappingIntentId(node);
    if (intentId == null || intentId.isBlank()) {
      if (nodeTouchesKey(graphPatch, nodeId, MappingExecutionSite.MAPPING_COVERAGE_PROPERTY)) {
        throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " mappingIntentId is required");
      }
      return;
    }
    MappingIntent intent = requireIntent(context, intentId);
    String script = MappingExecutionSite.scriptBody(node);
    if (script == null) {
      throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " script body is required");
    }
    MappingEnvelope envelope = requireEnvelope(conversationId, context, intentId);
    List<String> coverage =
        mappingCaptureValidator.hopBodyCoverage(parseMappingCoverage(node), envelope);
    mappingCaptureValidator.validateScript(intent, script, coverage, envelope);
  }

  private MappingIntent requireIntent(GraphPatchExecutionContext context, String mappingIntentId) {
    if (mappingIntentId == null || mappingIntentId.isBlank()) {
      throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " mappingIntentId is required");
    }
    if (context == null || context.requirementBrief() == null) {
      throw new IllegalArgumentException(
          MAPPING_CAPTURE_PREFIX + " mapping intent '" + mappingIntentId + "' is required");
    }
    for (MappingIntent intent : context.requirementBrief().mappingIntents()) {
      if (mappingIntentId.equals(intent.mappingIntentId())) {
        return intent;
      }
    }
    throw new IllegalArgumentException(
        MAPPING_CAPTURE_PREFIX + " mapping intent '" + mappingIntentId + "' is required");
  }

  private MappingEnvelope requireEnvelope(
      String conversationId, GraphPatchExecutionContext context, String mappingIntentId) {
    if (compilationArtifacts == null || context == null) {
      throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " mapping envelope is required");
    }
    if (mappingIntentId == null || mappingIntentId.isBlank()) {
      throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " mapping envelope is required");
    }
    for (CompilationArtifacts.Reference reference : context.consumedArtifacts()) {
      if (reference.kind() != CompilationArtifacts.Kind.MAPPING_ENVELOPE) {
        continue;
      }
      Optional<CompilationArtifacts.Revision> revision =
          compilationArtifacts.get(conversationId, reference);
      if (revision.isEmpty()) {
        continue;
      }
      MappingEnvelope envelope =
          compilationArtifacts.payload(revision.get(), MappingEnvelope.class);
      if (mappingIntentId.equals(envelope.mappingIntentId())) {
        return envelope;
      }
    }
    throw new IllegalArgumentException(MAPPING_CAPTURE_PREFIX + " mapping envelope is required");
  }

  private MappingDescriptionDocument parseMappingDescription(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(
          MAPPING_CAPTURE_PREFIX + " cannot parse mappingDescription");
    }
    try {
      return objectMapper.readValue(json, MappingDescriptionDocument.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          MAPPING_CAPTURE_PREFIX + " cannot parse mappingDescription", e);
    }
  }

  private List<String> parseMappingCoverage(ChainPlanNode node) {
    return parseMappingCoverageJson(MappingExecutionSite.mappingCoverage(node));
  }

  private List<String> parseMappingCoverageJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      JsonNode parsed = objectMapper.readTree(raw);
      if (!parsed.isArray()) {
        throw new IllegalArgumentException(
            MAPPING_CAPTURE_PREFIX + " mappingCoverage must be a JSON array of strings");
      }
      List<String> paths = new ArrayList<>();
      for (JsonNode item : parsed) {
        if (!item.isTextual()) {
          throw new IllegalArgumentException(
              MAPPING_CAPTURE_PREFIX + " mappingCoverage must be a JSON array of strings");
        }
        paths.add(item.asText());
      }
      return paths;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          MAPPING_CAPTURE_PREFIX + " mappingCoverage must be a JSON array of strings", e);
    }
  }

  private GraphPatch withHopBodyMappingCoverage(
      String conversationId,
      String capabilityId,
      GraphPatch graphPatch,
      ChainPlanGraph graphForGate) {
    if (!SCRIPT_GENERATOR.equals(capabilityId)
        || graphPatch == null
        || !touchesKey(graphPatch, MappingExecutionSite.MAPPING_COVERAGE_PROPERTY)) {
      return graphPatch;
    }
    GraphPatchExecutionContext context =
        executionContextStore
            .get(conversationId, capabilityId)
            .or(executionContextStore::current)
            .orElse(null);
    return new GraphPatch(
        graphPatch.patchId(),
        graphPatch.ownerCapabilityId(),
        withHopBodyCoverageNodes(graphPatch.nodePatches(), conversationId, context),
        graphPatch.edgePatches(),
        withHopBodyCoverageProperties(
            graphPatch.propertyPatches(), conversationId, context, graphForGate),
        graphPatch.chainPatches(),
        graphPatch.usedKnowledgeRefs(),
        graphPatch.rationale());
  }

  private List<PropertyPatch> withHopBodyCoverageProperties(
      List<PropertyPatch> patches,
      String conversationId,
      GraphPatchExecutionContext context,
      ChainPlanGraph graphForGate) {
    if (patches == null || patches.isEmpty()) {
      return patches;
    }
    List<PropertyPatch> rewritten = new ArrayList<>(patches.size());
    for (PropertyPatch patch : patches) {
      if (patch == null
          || patch.property() == null
          || !MappingExecutionSite.MAPPING_COVERAGE_PROPERTY.equals(patch.property().key())) {
        rewritten.add(patch);
        continue;
      }
      ChainPlanNode node = findNode(graphForGate, patch.targetNodeId());
      rewritten.add(
          new PropertyPatch(
              patch.operation(),
              patch.targetNodeId(),
              hopBodyCoverageProperty(patch.property(), conversationId, context, node)));
    }
    return rewritten;
  }

  private List<NodePatch> withHopBodyCoverageNodes(
      List<NodePatch> patches, String conversationId, GraphPatchExecutionContext context) {
    if (patches == null || patches.isEmpty()) {
      return patches;
    }
    List<NodePatch> rewritten = new ArrayList<>(patches.size());
    for (NodePatch patch : patches) {
      if (patch == null || patch.node() == null || patch.node().properties() == null) {
        rewritten.add(patch);
        continue;
      }
      ChainPlanNode node = patch.node();
      List<PlanProperty> properties = new ArrayList<>();
      boolean changed = false;
      for (PlanProperty property : node.properties()) {
        if (property == null
            || !MappingExecutionSite.MAPPING_COVERAGE_PROPERTY.equals(property.key())) {
          properties.add(property);
          continue;
        }
        properties.add(hopBodyCoverageProperty(property, conversationId, context, node));
        changed = true;
      }
      if (!changed) {
        rewritten.add(patch);
        continue;
      }
      rewritten.add(
          new NodePatch(
              patch.operation(),
              new ChainPlanNode(
                  node.nodeId(),
                  node.type(),
                  node.label(),
                  node.parentNodeId(),
                  node.order(),
                  List.copyOf(properties)),
              patch.targetNodeId()));
    }
    return rewritten;
  }

  private PlanProperty hopBodyCoverageProperty(
      PlanProperty property,
      String conversationId,
      GraphPatchExecutionContext context,
      ChainPlanNode node) {
    String intentId = MappingExecutionSite.mappingIntentId(node);
    MappingEnvelope envelope =
        intentId == null || intentId.isBlank()
            ? null
            : requireEnvelope(conversationId, context, intentId);
    List<String> stripped =
        mappingCaptureValidator.hopBodyCoverage(
            parseMappingCoverageJson(property.value()), envelope);
    try {
      return new PlanProperty(property.key(), objectMapper.writeValueAsString(stripped));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          MAPPING_CAPTURE_PREFIX + " mappingCoverage must be a JSON array of strings", e);
    }
  }

  private static boolean touchesMappingCaptureKeys(String capabilityId, GraphPatch graphPatch) {
    if (TRANSFORMATION_GENERATOR.equals(capabilityId)) {
      return touchesKey(graphPatch, MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY);
    }
    return touchesKey(graphPatch, MappingExecutionSite.SCRIPT_PROPERTY)
        || touchesKey(graphPatch, MappingExecutionSite.MAPPING_COVERAGE_PROPERTY);
  }

  private static boolean touchesKey(GraphPatch graphPatch, String key) {
    if (graphPatch.propertyPatches() != null) {
      for (PropertyPatch propertyPatch : graphPatch.propertyPatches()) {
        if (propertyPatch != null
            && propertyPatch.property() != null
            && key.equals(propertyPatch.property().key())) {
          return true;
        }
      }
    }
    if (graphPatch.nodePatches() != null) {
      for (NodePatch nodePatch : graphPatch.nodePatches()) {
        if (nodePatch == null || nodePatch.node() == null || nodePatch.node().properties() == null) {
          continue;
        }
        for (PlanProperty property : nodePatch.node().properties()) {
          if (property != null && key.equals(property.key())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean nodeTouchesKey(GraphPatch graphPatch, String nodeId, String key) {
    if (graphPatch.propertyPatches() != null) {
      for (PropertyPatch propertyPatch : graphPatch.propertyPatches()) {
        if (propertyPatch != null
            && nodeId.equals(propertyPatch.targetNodeId())
            && propertyPatch.property() != null
            && key.equals(propertyPatch.property().key())) {
          return true;
        }
      }
    }
    if (graphPatch.nodePatches() != null) {
      for (NodePatch nodePatch : graphPatch.nodePatches()) {
        if (nodePatch == null
            || nodePatch.node() == null
            || !nodeId.equals(nodePatch.node().nodeId())
            || nodePatch.node().properties() == null) {
          continue;
        }
        for (PlanProperty property : nodePatch.node().properties()) {
          if (property != null && key.equals(property.key())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static Set<String> patchedNodeIds(GraphPatch graphPatch) {
    LinkedHashSet<String> nodeIds = new LinkedHashSet<>();
    if (graphPatch.propertyPatches() != null) {
      for (PropertyPatch propertyPatch : graphPatch.propertyPatches()) {
        if (propertyPatch != null && propertyPatch.targetNodeId() != null) {
          nodeIds.add(propertyPatch.targetNodeId());
        }
      }
    }
    if (graphPatch.nodePatches() != null) {
      for (NodePatch nodePatch : graphPatch.nodePatches()) {
        if (nodePatch != null && nodePatch.node() != null && nodePatch.node().nodeId() != null) {
          nodeIds.add(nodePatch.node().nodeId());
        }
      }
    }
    return nodeIds;
  }

  private static ChainPlanNode findNode(ChainPlanGraph graph, String nodeId) {
    if (graph == null || graph.nodes() == null) {
      return null;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (nodeId.equals(node.nodeId())) {
        return node;
      }
    }
    return null;
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureGraphPatch", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}

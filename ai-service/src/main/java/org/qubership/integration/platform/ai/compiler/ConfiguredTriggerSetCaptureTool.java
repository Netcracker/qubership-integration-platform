package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Captures typed {@link ConfiguredTriggerSet} output for planning flow. */
@ApplicationScoped
public class ConfiguredTriggerSetCaptureTool {

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Trigger generation did not capture configured triggers. The agent must call"
          + " captureConfiguredTriggerSet with at least one trigger before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Configured trigger set already captured. Do not call captureConfiguredTriggerSet again;"
          + " finish this turn without further tool calls.";

  private static final Logger LOG = Logger.getLogger(ConfiguredTriggerSetCaptureTool.class);

  private final CaptureSession captureSession;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final DeterministicElementSchemaService schemaService;

  @Inject
  ConfiguredTriggerSetCaptureTool(
      CaptureSession captureSession,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      DeterministicElementSchemaService schemaService) {
    this.captureSession = captureSession;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.schemaService = schemaService;
  }

  @Tool("""
      Capture configured trigger set in the same turn after trigger generation is complete.
      Do not pass conversationId — the server binds this capture to the current chat session.
      triggers must include at least one configured trigger.""")
  public String captureConfiguredTriggerSet(ConfiguredTriggerSet capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureConfiguredTriggerSet",
        conversationId,
        "preview=" + AiTraceLog.previewJson(objectMapper, capture, 400));
    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(conversationId, startMs, "conversationId is required (no active chat session)");
      }
      if (capture == null) {
        return finish(conversationId, startMs, "capture is required");
      }
      if (capture.triggers() == null || capture.triggers().isEmpty()) {
        return reject(
            conversationId, startMs, "triggers must include at least one configured trigger");
      }
      List<String> validationErrors = validateProperties(capture);
      if (!validationErrors.isEmpty()) {
        return reject(
            conversationId,
            startMs,
            "Configured trigger validation failed: " + String.join("; ", validationErrors));
      }
      String accepted =
          captureSession.accept(
              CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId),
              capture,
              "Configured trigger set captured. Do not call captureConfiguredTriggerSet again;"
                  + " finish this turn without further tool calls.",
              DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG,
          "captureConfiguredTriggerSet",
          conversationId,
          System.currentTimeMillis() - startMs,
          e);
      return "Error capturing configured trigger set: " + e.getMessage();
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG,
        "captureConfiguredTriggerSet",
        conversationId,
        System.currentTimeMillis() - startMs,
        result);
    return result;
  }

  private String reject(String conversationId, long startMs, String message) {
    boolean repeated = feedbackStore.recordPlanValidationFailure(conversationId, message);
    if (repeated) {
      throw new CaptureValidationException(message);
    }
    return finish(conversationId, startMs, message);
  }

  private List<String> validateProperties(ConfiguredTriggerSet capture) {
    List<String> errors = new ArrayList<>();
    for (int triggerIndex = 0; triggerIndex < capture.triggers().size(); triggerIndex++) {
      ConfiguredTrigger trigger = capture.triggers().get(triggerIndex);
      if (trigger == null) {
        errors.add("trigger[" + triggerIndex + "] must not be null");
        continue;
      }
      String triggerId = triggerId(trigger, triggerIndex);
      String elementType = trigger.elementType();
      if (elementType == null || elementType.isBlank()) {
        errors.add("trigger '" + triggerId + "' elementType is required");
        continue;
      }
      if (!schemaService.hasElementSchema(elementType)) {
        errors.add(
            "trigger '" + triggerId + "' has no schema for element type '" + elementType + "'");
        continue;
      }
      Set<String> allowedKeys = schemaService.allowedPatchPropertyKeys(elementType);
      Set<String> seenKeys = new LinkedHashSet<>();
      for (PlanProperty property : trigger.properties()) {
        if (property == null || property.key() == null || property.key().isBlank()) {
          errors.add("trigger '" + triggerId + "' contains a property without a key");
          continue;
        }
        String key = property.key();
        if (!seenKeys.add(key)) {
          errors.add("trigger '" + triggerId + "' contains duplicate property '" + key + "'");
          continue;
        }
        if (!allowedKeys.contains(key)) {
          errors.add(
              "trigger '"
                  + triggerId
                  + "' ("
                  + elementType
                  + ") property '"
                  + key
                  + "' is not defined by the element schema");
          continue;
        }
        if (property.value() == null) {
          errors.add("trigger '" + triggerId + "' property '" + key + "' must not be null");
          continue;
        }
        Object coerced =
            schemaService.coercePatchPropertyValue(elementType, key, property.value());
        schemaService
            .validateCapturePropertyValue(elementType, key, objectMapper.valueToTree(coerced))
            .ifPresent(
                error ->
                    errors.add(
                        "trigger '"
                            + triggerId
                            + "' ("
                            + elementType
                            + ") property '"
                            + key
                            + "': "
                            + error));
      }
    }
    return List.copyOf(errors);
  }

  private static String triggerId(ConfiguredTrigger trigger, int index) {
    if (trigger.semanticNodeId() != null && !trigger.semanticNodeId().isBlank()) {
      return trigger.semanticNodeId();
    }
    if (trigger.roleId() != null && !trigger.roleId().isBlank()) {
      return trigger.roleId();
    }
    return "trigger[" + index + "]";
  }
}

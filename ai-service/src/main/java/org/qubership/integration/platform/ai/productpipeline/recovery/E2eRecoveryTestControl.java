package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.qubership.integration.platform.ai.compiler.HttpTriggerCapture;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

/** Disabled observation and fault controls for one harness-owned process and conversation. */
@ApplicationScoped
public class E2eRecoveryTestControl {
  private final ObjectMapper mapper;
  private final Path directory;
  private final JsonNode plan;
  private long sequence;
  private boolean injected;
  private int argumentFaults;
  private String injectedArguments;
  @Inject CaptureAttemptFeedbackStore feedbackStore;

  @Inject
  public E2eRecoveryTestControl(AppConfig config, ObjectMapper mapper) {
    this(config.e2e().recoveryControlDirectory().map(Path::of).orElse(null), mapper);
  }

  public E2eRecoveryTestControl(Path directory, ObjectMapper mapper) {
    this.mapper = mapper;
    this.directory = directory;
    try {
      plan = directory == null ? mapper.createObjectNode() : mapper.readTree(directory.resolve("plan.json").toFile());
      if (directory != null && (plan.path("schemaVersion").asInt() != 1
          || plan.path("conversationId").asText().isBlank()
          || !List.of("checkpoint", "completion").contains(plan.path("boundary").asText()))) {
        throw new IllegalArgumentException("Invalid recovery test control plan");
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read recovery test control plan", failure);
    }
  }

  public synchronized void stageStarted(StageExecutionContext context) {
    if (!owns(context.conversationId())) return;
    try {
      mapper.writeValue(directory.resolve("provider-context.json").toFile(), java.util.Map.of(
          "runId", context.runId(), "conversationId", context.conversationId(),
          "stageId", context.stageId(), "attemptId", context.attemptId(), "inputRefs", context.inputRefs()));
    } catch (IOException failure) { org.jboss.logging.Logger.getLogger(getClass()).warn("Provider context evidence is incomplete", failure); }
  }

  private boolean owns(String conversationId) {
    return directory != null && plan.path("conversationId").asText().equals(conversationId);
  }

  /** Receives arguments only after ordinary normalization and schema validation have succeeded. */
  public synchronized ToolExecutionRequest httpArguments(
      String conversationId, String capabilityId, ToolExecutionRequest request) {
    String mutation = plan.path("mutation").asText();
    int maximum = "http-distinct-fields".equals(mutation) ? 2 : 1;
    if (!owns(conversationId) || argumentFaults >= maximum
        || !List.of("http-exposure-type", "http-distinct-fields").contains(mutation)) return request;
    try {
      var root = mapper.readTree(request.arguments());
      var capture = root.path("capture");
      var endpoint = capture.path("endpoints").path(0);
      if (!endpoint.path("externalRoute").isBoolean()) { return request; }
      var original = capture.deepCopy();
      if (argumentFaults == 0) ((ObjectNode) endpoint).put("externalRoute", endpoint.path("externalRoute").asText());
      else ((ObjectNode) endpoint).put("semanticNodeId", false);
      injectedArguments = mapper.writeValueAsString(root);
      var event = event("FAULT_APPLIED", conversationId);
      event.put("capabilityId", capabilityId);
      event.put("tool", "captureHttpTriggers");
      event.put("parameter", "capture");
      event.set("original", original);
      event.set("mutated", capture);
      saveEvent(event);
      injected = true;
      argumentFaults++;
      return request.toBuilder().arguments(injectedArguments).build();
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot mutate validated HTTP capture arguments", failure);
    }
  }

  public synchronized void rejectedHttpArguments(
      String conversationId, String capabilityId, ToolExecutionRequest request, String result) {
    if (!owns(conversationId) || !injected
        || !request.arguments().equals(injectedArguments)
        || !List.of("http-exposure-type", "http-distinct-fields").contains(plan.path("mutation").asText())) { return; }
    try {
      var event = event("CAPTURE_REJECTED", conversationId);
      event.put("capabilityId", capabilityId);
      event.put("tool", "captureHttpTriggers");
      event.set("proposal", mapper.readTree(request.arguments()).path("capture"));
      event.set("nativeResult", mapper.readTree(result));
      var feedback = feedbackStore.lastPlanFailure(conversationId).orElseThrow();
      event.set("rejectionRef", mapper.valueToTree(feedback.evidenceReference()));
      saveEvent(event);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot record native HTTP argument rejection", failure);
    }
  }

  public synchronized HttpTriggerCapture httpCapture(
      String conversationId, String capabilityId, HttpTriggerCapture proposal) {
    if (!owns(conversationId) || injected || proposal == null || proposal.endpoints().isEmpty()
        || !"http-semantic-reference".equals(plan.path("mutation").asText())) {
      return proposal;
    }
    var endpoints = new ArrayList<>(proposal.endpoints());
    var first = endpoints.getFirst();
    endpoints.set(0, new HttpTriggerCapture.Endpoint(
        first.roleId(), UUID.randomUUID().toString(), first.externalRoute()));
    var mutated = new HttpTriggerCapture(endpoints);
    var event = event("FAULT_APPLIED", conversationId);
    event.put("capabilityId", capabilityId);
    event.set("original", mapper.valueToTree(proposal));
    event.set("mutated", mapper.valueToTree(mutated));
    saveEvent(event);
    injected = true;
    return mutated;
  }

  public synchronized void acceptedHttpCapture(String conversationId, String capabilityId,
      HttpTriggerCapture proposal, ConfiguredTriggerSet accepted) {
    if (!owns(conversationId)) { return; }
    var event = event("CAPTURE_ACCEPTED", conversationId);
    event.put("capabilityId", capabilityId);
    event.put("source", "model-tool");
    event.put("tool", "captureHttpTriggers");
    event.put("nativeAdmission", "configured-trigger-set");
    event.set("proposal", mapper.valueToTree(proposal));
    event.set("configuredTriggers", mapper.valueToTree(accepted));
    saveEvent(event);
  }

  public boolean needsLiteralFault(String conversationId) {
    return owns(conversationId) && !injected && "requirement-literal-binding".equals(plan.path("mutation").asText());
  }

  public synchronized org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput requirements(
      String conversationId, org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput proposal) {
    if (!owns(conversationId) || injected || proposal == null
        || !"requirement-literal-binding".equals(plan.path("mutation").asText())) return proposal;
    var original = mapper.<ObjectNode>valueToTree(proposal);
    var mutated = original.deepCopy();
    for (var fact : mutated.path("facts")) {
      var intent = fact.path("elementIntent");
      if (!"LOCAL_NODE".equals(intent.path("placement").asText()) || intent.path("requestedProperties").isEmpty()) continue;
      var exact = intent.path("exactValues");
      if (exact.isEmpty()) {
        for (var property : intent.path("requestedProperties")) {
          String value = property.path("value").asText();
          if (value.length() > 3 && fact.path("text").asText().contains(value)) {
            ((ObjectNode) intent).putArray("exactValues").add(value);
            break;
          }
        }
      }
      if (intent.path("exactValues").isEmpty()) continue;
      ((ObjectNode) intent).putArray("requestedProperties");
      recordFault(conversationId, "captureRequirementDraft", "draft", original, mutated);
      return mapper.convertValue(mutated, org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput.class);
    }
    return proposal;
  }

  public synchronized org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture semantic(
      String conversationId,
      org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture proposal,
      java.util.function.BooleanSupplier admissible) {
    if (!owns(conversationId) || injected || proposal == null
        || !"semantic-required-node".equals(plan.path("mutation").asText()) || !admissible.getAsBoolean()) return proposal;
    var original = mapper.<ObjectNode>valueToTree(proposal);
    var mutated = original.deepCopy();
    var operations = (com.fasterxml.jackson.databind.node.ArrayNode) mutated.path("operations");
    if (operations.isEmpty()) return proposal;
    var required = org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
        .designBinding(conversationId).map(binding -> binding.approvedBrief().facts().stream()
            .map(org.qubership.integration.platform.ai.plan.RequirementFact::elementIntent)
            .filter(intent -> intent != null && intent.placement()
                == org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE)
            .map(org.qubership.integration.platform.ai.plan.RequirementElementIntent::elementRef)
            .collect(java.util.stream.Collectors.toSet())).orElse(java.util.Set.of());
    int selected = -1;
    for (int i = 0; i < operations.size(); i++) {
      if (required.contains(operations.get(i).path("nodeId").asText())) { selected = i; break; }
    }
    if (selected < 0) return proposal;
    operations.remove(selected);
    recordFault(conversationId, "captureChainSemanticRevision", "capture", original, mutated);
    return mapper.convertValue(mutated,
        org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.class);
  }

  private void recordFault(String conversationId, String tool, String parameter, JsonNode original, JsonNode mutated) {
    var event = event("FAULT_APPLIED", conversationId);
    event.put("tool", tool);
    event.put("parameter", parameter);
    event.set("original", original);
    event.set("mutated", mutated);
    saveEvent(event);
    injected = true;
  }

  public synchronized void requirementOutcome(String conversationId,
      org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput proposal,
      org.qubership.integration.platform.ai.plan.RequirementCaptureResult result) {
    if (!owns(conversationId) || !"requirement-literal-binding".equals(plan.path("mutation").asText())) return;
    var event = nativeOutcome(conversationId, "captureRequirementDraft", proposal, result.accepted(), mapper.valueToTree(result));
    event.set("rejectionRef", mapper.valueToTree(feedbackStore.lastRequirementFeedbackReference(conversationId).orElse(null)));
    event.set("acceptedRef", mapper.valueToTree(result.revision()));
    saveEvent(event);
  }

  public synchronized void semanticOutcome(String conversationId,
      org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture proposal, String result) {
    if (!owns(conversationId) || !"semantic-required-node".equals(plan.path("mutation").asText())) return;
    try {
      var nativeResult = mapper.readTree(result);
      var event = nativeOutcome(conversationId, "captureChainSemanticRevision", proposal,
          nativeResult.path("accepted").asBoolean(), nativeResult);
      event.set("rejectionRef", mapper.valueToTree(feedbackStore.lastPlanFailure(conversationId)
          .map(org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback::evidenceReference).orElse(null)));
      saveEvent(event);
    } catch (IOException failure) { throw new IllegalStateException("Cannot retain native semantic admission", failure); }
  }

  private ObjectNode nativeOutcome(String conversationId, String tool, Object proposal, boolean accepted, JsonNode result) {
    var event = event(accepted ? "CAPTURE_ACCEPTED" : "CAPTURE_REJECTED", conversationId);
    event.put("tool", tool);
    event.put("source", "model-tool");
    event.put("nativeAdmission", tool);
    event.set("proposal", mapper.valueToTree(proposal));
    event.set("nativeResult", result);
    return event;
  }

  /** Called only after the materialization consumer has checked all inputs and endpoints. */
  public synchronized boolean materializationInputsAccepted(
      StageExecutionContext context, Reference graphRef, List<Reference> inputRefs) {
    if (!owns(context.conversationId())) { return false; }
    var event = event("CHECKPOINT", context.conversationId());
    event.put("runId", context.runId());
    event.put("attemptId", context.attemptId());
    event.put("boundary", "materialization-inputs-accepted");
    event.set("graphRef", mapper.valueToTree(graphRef));
    event.set("inputRefs", mapper.valueToTree(inputRefs));
    saveEvent(event);
    write(directory.resolve("checkpoint.json"), event);
    return "checkpoint".equals(plan.path("boundary").asText());
  }

  private ObjectNode event(String type, String conversationId) {
    var event = mapper.createObjectNode();
    event.put("type", type);
    event.put("sequence", ++sequence);
    event.put("observedAt", Instant.now().toString());
    event.put("conversationId", conversationId);
    return event;
  }

  private void saveEvent(ObjectNode event) {
    write(directory.resolve("events").resolve(String.format("%06d.json", sequence)), event);
  }

  private void write(Path target, JsonNode value) {
    try {
      Files.createDirectories(target.getParent());
      Path temporary = target.resolveSibling(".evidence-" + UUID.randomUUID() + ".json");
      mapper.writeValue(temporary.toFile(), value);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot persist recovery test observation", failure);
    }
  }
}

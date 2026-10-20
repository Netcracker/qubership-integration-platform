package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

  private boolean owns(String conversationId) {
    return directory != null && plan.path("conversationId").asText().equals(conversationId);
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
    event.put("nativeAdmission", "configured-trigger-set");
    event.set("proposal", mapper.valueToTree(proposal));
    event.set("configuredTriggers", mapper.valueToTree(accepted));
    saveEvent(event);
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

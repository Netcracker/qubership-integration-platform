package org.qubership.integration.platform.ai.plan;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

/** Authored requirement content. The server owns its accepted revision and derived state. */
public final class RequirementCaptureInput {

  private RequirementCaptureInput() {}

  public record DraftInput(
      FlowInput flow,
      List<FactInput> facts,
      List<CapabilityInput> capabilities,
      List<QuestionInput> openQuestions,
      DraftSettings settings) {}

  public record FlowInput(
      List<InteractionInput> interactions, List<TransitionInput> transitions) {}

  public record InteractionInput(
      String interactionId,
      @NullableCaptureValue Direction direction,
      @NullableCaptureValue String participant,
      @NullableCaptureValue String operation,
      @NullableCaptureValue String description,
      @NullableCaptureValue ServiceCallFailureMode failureMode,
      @NullableCaptureValue RetryInput retryPolicy) {}

  public record RetryInput(
      @NullableCaptureValue Integer retryCount,
      @NullableCaptureValue Integer retryDelayMs) {}

  public record TransitionInput(String sourceInteractionId, String targetInteractionId) {}

  public record FactInput(
      String sourceFactId,
      List<String> interactionIds,
      FactKind kind,
      Polarity polarity,
      String text) {}

  public enum FactKind {
    GOAL,
    PARAMETER,
    BEHAVIOR,
    CONSTRAINT,
    VISIBILITY,
    ROUTING
  }

  public enum Polarity {
    POSITIVE,
    NEGATIVE
  }

  public record CapabilityInput(
      String interactionId,
      String capabilityKey,
      @NullableCaptureValue(description = "Only for http-trigger; null for every other capability.")
          HttpMode httpMode,
      @NullableCaptureValue(description = "Only for http-trigger or http-sender; otherwise null.")
          HttpMethod httpMethod,
      @NullableCaptureValue(description = "HTTP trigger path or direct HTTP sender URI; otherwise null.")
          String path,
      @NullableCaptureValue(description = "Only for a Kafka trigger or sender; otherwise null.")
          String topic,
      @NullableCaptureValue(description = "Only for mcp-trigger; otherwise null.")
          String mcpServerId,
      @NullableCaptureValue(description = "Only for chain-call-2 or mcp-trigger after target lookup; otherwise null.")
          String targetReference) {}

  public enum HttpMode {
    CUSTOM,
    CATALOG
  }

  public enum HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
    OPTIONS,
    TRACE,
    CONNECT
  }

  public record QuestionInput(String questionId, List<String> interactionIds, String text) {}

  public record DraftSettings(
      @NullableCaptureValue Boolean idsRequested,
      @NullableCaptureValue SystemType preferredSystemType) {}

  public enum SystemType {
    INTERNAL,
    EXTERNAL
  }

  public record DraftUpdate(
      List<InteractionInput> addInteractions,
      List<InteractionInput> updateInteractions,
      List<String> removeInteractionIds,
      List<TransitionInput> addTransitions,
      List<TransitionInput> removeTransitions,
      List<FactInput> addFacts,
      List<FactInput> updateFacts,
      List<String> removeFactIds,
      List<CapabilityInput> setCapabilities,
      List<String> removeCapabilityInteractionIds,
      List<QuestionInput> addQuestions,
      List<QuestionInput> updateQuestions,
      List<String> removeQuestionIds,
      @NullableCaptureValue DraftSettings settings) {}
}

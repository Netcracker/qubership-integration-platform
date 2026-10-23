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
      @NullableCaptureValue(description = "INLINE_RESPONSE when this call's failure fields go to the same"
          + " result delivery; ERROR_SCOPE only for a requested catch path; null propagates and skips successors.")
          ServiceCallFailureMode failureMode,
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
      String text,
      @NullableCaptureValue(description = "Required for FIELD_MAPPING; exact approved source and target fields.")
          FieldMappingInput fieldMapping,
      @NullableCaptureValue(description = "Only for a CONSTRAINT tied to one interaction: company policy,"
          + " requested and recommended property values, persisted question ID, and the user's decision.")
          RequirementPolicyConflict policyConflict,
      @NullableCaptureValue(description = "Explicit requested element, local node, inline hook, or chain name. Copy exact author values; null when unspecified.")
          RequirementElementIntent elementIntent) {

    public FactInput(
        String sourceFactId, List<String> interactionIds, FactKind kind, Polarity polarity,
        String text, FieldMappingInput fieldMapping, RequirementPolicyConflict policyConflict) {
      this(sourceFactId, interactionIds, kind, polarity, text, fieldMapping, policyConflict, null);
    }

    public FactInput(
        String sourceFactId, List<String> interactionIds, FactKind kind, Polarity polarity,
        String text, FieldMappingInput fieldMapping) {
      this(sourceFactId, interactionIds, kind, polarity, text, fieldMapping, null);
    }

    public FactInput(
        String sourceFactId, List<String> interactionIds, FactKind kind, Polarity polarity,
        String text) {
      this(sourceFactId, interactionIds, kind, polarity, text, null, null);
    }
  }

  public record FieldMappingInput(
      String sourceInteractionId,
      @NullableCaptureValue(description = "One source field path, never a field list. Use null and"
          + " describe the inputs in expression for computed values or retained context.") String sourcePath,
      String targetInteractionId,
      String targetPath,
      @NullableCaptureValue String expression) {}

  public enum FactKind {
    GOAL,
    PARAMETER,
    FIELD_MAPPING,
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
      @NullableCaptureValue(description = "Only for http-trigger or http-sender. Copy the author's method;"
          + " leave null and ask when unspecified. Never guess a method; null for other capabilities.")
          HttpMethod httpMethod,
      @NullableCaptureValue(description = "HTTP trigger path or direct HTTP sender URI; otherwise null.")
          String path,
      @NullableCaptureValue(description = "Only for a Kafka trigger or sender; otherwise null.")
          String topic,
      @NullableCaptureValue(description = "Only for mcp-trigger; otherwise null.")
          String mcpServerId,
      @NullableCaptureValue(description = "For chain-call-2, the verified chain-trigger element ID only,"
          + " never a chain ID or chainId/triggerId. For mcp-trigger, the verified MCP system ID. Otherwise null.")
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

  public record QuestionInput(String questionId, List<String> interactionIds, String text,
      @NullableCaptureValue(description = "Target field, remaining uncertainty, and every reviewed author source turn ID."
          + " Re-read saved sources before asking, including the latest answer; null only for legacy questions.")
      org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence evidence) {
    public QuestionInput(String questionId, List<String> interactionIds, String text) {
      this(questionId, interactionIds, text, null);
    }
  }

  public record DraftSettings(
      @NullableCaptureValue Boolean idsRequested,
      @NullableCaptureValue SystemType preferredSystemType,
      @NullableCaptureValue(description = "Catalog folder the user asked to put the new chain in,"
          + " as written: a folder name, or a path from the root such as Integrations/Salesforce."
          + " Missing folders are created with the chain. Null when the user named no folder.")
          String targetFolderPath) {

    /** Keeps existing test and call sites on the two-field constructor. */
    public DraftSettings(Boolean idsRequested, SystemType preferredSystemType) {
      this(idsRequested, preferredSystemType, null);
    }
  }

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
      @NullableCaptureValue DraftSettings settings,
      List<AmendmentInput> amendments,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      @com.fasterxml.jackson.annotation.JsonProperty(required = false)
      @NullableCaptureValue(description = "Exact pendingProposal.token from readRequirementDraft; only for focused"
          + " repair of the current rejected initial proposal. Omit or use null for accepted-draft updates.")
      String pendingProposalToken) {

    public DraftUpdate(
        List<InteractionInput> addInteractions, List<InteractionInput> updateInteractions,
        List<String> removeInteractionIds, List<TransitionInput> addTransitions,
        List<TransitionInput> removeTransitions, List<FactInput> addFacts, List<FactInput> updateFacts,
        List<String> removeFactIds, List<CapabilityInput> setCapabilities,
        List<String> removeCapabilityInteractionIds, List<QuestionInput> addQuestions,
        List<QuestionInput> updateQuestions, List<String> removeQuestionIds, DraftSettings settings,
        List<AmendmentInput> amendments) {
      this(addInteractions, updateInteractions, removeInteractionIds, addTransitions, removeTransitions,
          addFacts, updateFacts, removeFactIds, setCapabilities, removeCapabilityInteractionIds,
          addQuestions, updateQuestions, removeQuestionIds, settings, amendments, null);
    }

    public DraftUpdate(
        List<InteractionInput> addInteractions, List<InteractionInput> updateInteractions,
        List<String> removeInteractionIds, List<TransitionInput> addTransitions,
        List<TransitionInput> removeTransitions, List<FactInput> addFacts, List<FactInput> updateFacts,
        List<String> removeFactIds, List<CapabilityInput> setCapabilities,
        List<String> removeCapabilityInteractionIds, List<QuestionInput> addQuestions,
        List<QuestionInput> updateQuestions, List<String> removeQuestionIds, DraftSettings settings) {
      this(addInteractions, updateInteractions, removeInteractionIds, addTransitions, removeTransitions,
          addFacts, updateFacts, removeFactIds, setCapabilities, removeCapabilityInteractionIds,
          addQuestions, updateQuestions, removeQuestionIds, settings, List.of());
    }
  }

  public record AmendmentInput(
      String path,
      String sourceTurnId,
      String quote) {}
}

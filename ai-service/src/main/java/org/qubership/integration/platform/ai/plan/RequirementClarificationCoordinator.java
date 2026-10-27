package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionAdmission;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind;

/** Selects the one authored question a requirement run may present. */
public final class RequirementClarificationCoordinator {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The queue head, its draft identity, and the values the question is about. */
  public record ActiveQuestion(
      CompilationArtifacts.Reference draftReference,
      QuestionInput question,
      String sourceGeneration,
      JsonNode targetProjection) {}

  public Optional<ActiveQuestion> activeQuestion(
      Revision draftRevision, RequirementDraft draft, String sourceGeneration) {
    if (draftRevision == null || draft == null || draft.authoredDraft() == null) {
      return Optional.empty();
    }
    for (QuestionInput question : draft.authoredDraft().openQuestions()) {
      if (question == null || question.questionId() == null || question.questionId().isBlank()) {
        continue;
      }
      return Optional.of(new ActiveQuestion(draftRevision.reference(), question, sourceGeneration,
          targetProjection(draft, question)));
    }
    return Optional.empty();
  }

  public JsonNode targetProjection(RequirementDraft draft, QuestionInput question) {
    ObjectNode projection = JSON.createObjectNode();
    AuthorQuestionEvidence evidence = question == null ? null : question.evidence();
    if (evidence == null) {
      return projection;
    }
    projection.put("interactionId", evidence.interactionId());
    projection.put("propertyKey", evidence.propertyKey());
    if (evidence.kind() == Kind.POLICY_CHOICE) {
      RequirementPolicyConflict policy = policy(draft, evidence, question.questionId());
      if (policy == null || policy.decision() == null) {
        projection.putNull("policyDecision");
      } else {
        projection.put("policyDecision", policy.decision().name());
      }
      if (policy == null || policy.selectedValue() == null) {
        projection.putNull("selectedValue");
      } else {
        projection.put("selectedValue", policy.selectedValue());
      }
      return projection;
    }
    if (evidence.kind() == Kind.CATALOG_CHOICE) {
      CapabilityInput capability = chainCall(draft, evidence.interactionId());
      if (capability != null) {
        putText(projection, "targetReference", capability.targetReference());
        putText(projection, "resolvedTarget",
            RequirementCaptureProjection.resolvedTargets(draft).get(evidence.interactionId()));
        return projection;
      }
    }
    var requested = projection.putArray("requestedValues");
    requestedValues(draft, evidence).forEach(requested::add);
    return projection;
  }

  public ClarificationTransition.Offer createOffer(ActiveQuestion active, long runRevision) {
    CompilationArtifacts.Reference reference = active.draftReference();
    String sourceGeneration = active.sourceGeneration() == null ? "" : active.sourceGeneration();
    String offerId = CaptureProposal.digest(List.of(
        reference.kind().name(),
        reference.artifactId(),
        reference.contentHash(),
        active.question().questionId(),
        Long.toString(runRevision),
        sourceGeneration));
    return new ClarificationTransition.Offer(offerId, reference, active.question().questionId(), runRevision,
        sourceGeneration);
  }

  private static void putText(ObjectNode projection, String field, String value) {
    if (value == null) {
      projection.putNull(field);
    } else {
      projection.put(field, value);
    }
  }

  private static RequirementPolicyConflict policy(RequirementDraft draft, AuthorQuestionEvidence evidence,
      String questionId) {
    if (draft == null || draft.authoredDraft() == null) {
      return null;
    }
    RequirementPolicyConflict fallback = null;
    for (FactInput fact : draft.authoredDraft().facts()) {
      RequirementPolicyConflict policy = fact.policyConflict();
      if (policy == null || !evidence.propertyKey().equals(policy.propertyKey())
          || fact.interactionIds() == null || !fact.interactionIds().contains(evidence.interactionId())) {
        continue;
      }
      if (questionId.equals(policy.questionId())) {
        return policy;
      }
      if (fallback == null) {
        fallback = policy;
      }
    }
    return fallback;
  }

  private static CapabilityInput chainCall(RequirementDraft draft, String interactionId) {
    if (draft == null || draft.authoredDraft() == null) {
      return null;
    }
    return draft.authoredDraft().capabilities().stream()
        .filter(capability -> interactionId.equals(capability.interactionId())
            && "chain-call-2".equals(capability.capabilityKey()))
        .findFirst().orElse(null);
  }

  private static List<String> requestedValues(RequirementDraft draft, AuthorQuestionEvidence evidence) {
    if (draft == null || draft.authoredDraft() == null) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    var facts = RequirementCaptureProjection.facts(draft.authoredDraft(),
        RequirementCaptureProjection.resolvedTargets(draft));
    for (var fact : facts) {
      if (fact.polarity() != RequirementFactPolarity.POSITIVE) {
        continue;
      }
      boolean scoped = fact.targetsInteraction(evidence.interactionId())
          || fact.elementIntent() != null && evidence.interactionId().equals(fact.elementIntent().elementRef());
      if (!scoped) {
        continue;
      }
      for (String value : AuthorQuestionAdmission.suppliedValues(fact, evidence.propertyKey())) {
        if (!values.contains(value)) {
          values.add(value);
        }
      }
    }
    return List.copyOf(values);
  }
}

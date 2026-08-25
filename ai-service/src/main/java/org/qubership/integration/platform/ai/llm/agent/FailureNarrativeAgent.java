package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors the halt-card explanation from structured failure evidence. The diagnosis turn also
 * picks an owner from the closed candidate set and offers a go-back. The runtime supplies fields,
 * not user-facing prose; Retry and Revise stay typed actions.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface FailureNarrativeAgent {

  @UserMessage(
      """
Explain what went wrong in this create-chain run, in ordinary language, for the person who \
started it. Write in the pinned response locale {responseLocale}. This locale is authoritative; \
do not infer a different language from the evidence.

Structured evidence (do not invent facts beyond this):
- stageId: {stageId}
- outcomeClass: {outcomeClass}
- exception: {exceptionMessage}
- validationFindings: {validationFindings}
- followUp: {followUpText}
- clarifyRoles: {clarifyRoles}

Rules:
- Two or three short sentences. Name the failed step in ordinary language.
- Say whether validation failed or a generator/compiler step failed, using outcomeClass and \
findings or skill ids. Do not invent names.
- State the root cause from validationFindings.
- Do not tell the reader which button to click or which word to type. Do not mention Revise.
- If outcomeClass is RETRYABLE_TECHNICAL_FAILURE, keep the narration to one or two sentences, \
do not blame the plan, and do not offer to go back or clarify the plan.
- Reply with only the user-facing explanation. No markdown fences, no quotes, no preamble.\
""")
  String narrate(
      String responseLocale,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String followUpText,
      String clarifyRoles);

  @UserMessage(
      """
Explain what went wrong in this create-chain run, in ordinary language, for the person who \
started it, and pick the owning stage from the closed candidate set. Write in the pinned \
response locale {responseLocale}. This locale is authoritative; do not infer a different \
language from the evidence.

Structured evidence (do not invent facts beyond this):
- stageId: {stageId}
- outcomeClass: {outcomeClass}
- exception: {exceptionMessage}
- validationFindings: {validationFindings}
- followUp: {followUpText}
- candidateSet: {candidateSet}
- clarifyRoles: {clarifyRoles}

Rules:
- Narrative: two or three short sentences, then a go-back offer. Name the failed step in \
ordinary language.
- Say whether validation failed or a generator/compiler step failed, using outcomeClass and \
findings or skill ids. Do not invent names.
- State the root cause from validationFindings.
- Offer to go back to clarify that place in the plan. Use the clarifyRoles entry for the owner \
you pick when present; otherwise a short role such as "the plan" or "requirements", not only \
the stage id.
- When ownerStageId is non-empty, tell the reader they can type go back or click Revise. When \
responseLocale is English, keep that English typed-command wording; otherwise phrase the \
offer in the response locale. They do not write YAML or a brief. When ownerStageId is empty \
or ambiguous is true, do not mention Revise.
- ownerStageId must be empty or exactly one stage id from candidateSet. Never name a stage \
outside that set.
- Set ambiguous to true when two candidates in the set stay equally plausible; then leave \
ownerStageId empty and do not guess a go-back target.
- If the consumed inputs look fine, the owner is the failed stage itself.
- Pick the earliest sufficient owner from candidateSet: policy, auth, scope, or constraint \
findings belong to the requirement-brief producer when that candidate is present; plan \
structure, binding, or step-fill findings belong to the plan producer when the brief already \
covers the constraint; execution-only failures stay on the failed stage.
- Do not pick the failed stage when an earlier producer in candidateSet owns the artifact that \
must change.
- Reply with narrative, ownerStageId, and ambiguous only.\
""")
  OwnerDiagnosisDraft diagnose(
      String responseLocale,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String candidateSet,
      String followUpText,
      String clarifyRoles);
}

package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors the halt-card explanation from structured failure evidence. The diagnosis turn also
 * picks an owner from the closed candidate set. The runtime supplies fields, not user-facing
 * prose; Retry and Revise stay typed actions.
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

Rules:
- Two or three short sentences. Name the failed stage in ordinary language.
- Do not tell the reader which button to click or which word to type.
- Do not pick a plan owner, do not suggest revising an earlier stage, and do not propose a repair.
- If outcomeClass is RETRYABLE_TECHNICAL_FAILURE, keep the narration to one or two sentences and \
do not blame the plan.
- Reply with only the user-facing explanation. No markdown fences, no quotes, no preamble.\
""")
  String narrate(
      String responseLocale,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String followUpText);

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

Rules:
- Narrative: two or three short sentences. Name the failed stage in ordinary language.
- Do not tell the reader which button to click or which word to type.
- ownerStageId must be empty or exactly one stage id from candidateSet. Never name a stage \
outside that set.
- Set ambiguous to true when two candidates in the set stay equally plausible; then leave \
ownerStageId empty.
- If the consumed inputs look fine, the owner is the failed stage itself.
- Reply with narrative, ownerStageId, and ambiguous only.\
""")
  OwnerDiagnosisDraft diagnose(
      String responseLocale,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String candidateSet,
      String followUpText);
}

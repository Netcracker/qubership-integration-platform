package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors the halt-card explanation from structured failure evidence. The diagnosis turn also
 * picks an owner from the closed candidate set, offers a go-back, and names the change that would
 * clear the halt. A third turn reads a message typed at the halt and answers it when it asks rather
 * than instructs, and a fourth does the same for a message typed at an approval card, where the
 * evidence is the candidate rather than a failure. The runtime supplies fields, not user-facing
 * prose; Retry, Revise, and Agree stay typed actions.
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
started it, pick the owning stage from the closed candidate set, and name the change that \
would clear the halt. Write in the pinned response locale {responseLocale}. This locale is \
authoritative; do not infer a different language from the evidence.

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
- When ownerStageId is non-empty, offer to go back to clarify that place. Do not name a UI \
control or a typed command. They do not write YAML or a brief.
- ownerStageId must be empty or exactly one stage id from candidateSet. Never name a stage \
outside that set.
- Set ambiguous to true when two candidates in the set stay equally plausible; then leave \
ownerStageId empty and do not guess a go-back target.
- If the consumed inputs look fine, the owner is the failed stage itself, except for \
INTERNAL_FAILURE. For INTERNAL_FAILURE, leave ownerStageId empty when candidateSet contains no \
upstream stage.
- Pick the earliest sufficient owner from candidateSet: policy, auth, scope, or constraint \
findings belong to the requirement-brief producer when that candidate is present; plan \
structure, binding, or step-fill findings belong to the plan producer when the brief already \
covers the constraint; execution-only failures stay on the failed stage.
- Do not pick the failed stage when an earlier producer in candidateSet owns the artifact that \
must change.
- The rules above govern the narrative alone. The narrative explains what happened; it does not \
prescribe the change to make. The instruction field carries that.
- remedy: exactly one of RETRY, REVISE_INPUT, REOPEN_STAGE, DROP_ELEMENT, UNRECOVERABLE, or \
empty when the evidence supports no concrete change. Use REOPEN_STAGE only when ownerStageId \
names a stage from candidateSet.
- instruction: one sentence in the response locale naming what to add, remove, or correct — a \
missing fact, an element the design cannot support, a wrong value, or the artifact to go back \
to. Describe the change to the work, not a control to click or a word to type. Leave it empty \
when the evidence supports no such change; do not invent one.
- Reply with narrative, ownerStageId, ambiguous, remedy, and instruction only.\
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

  @UserMessage(
      """
Someone typed a message at a create-chain run that is halted and waiting. Decide whether the \
message asks about the run or tells the run what to do, and answer it when it asks.

Message: {message}

Structured evidence for the halt (do not invent facts beyond this):
- stageId: {stageId}
- outcomeClass: {outcomeClass}
- exception: {exceptionMessage}
- validationFindings: {validationFindings}
- candidateSet: {candidateSet}
- priorFollowUp: {followUpText}

Rules:
- verdict: exactly QUESTION or INSTRUCTION.
- QUESTION when the message asks about the run: why it stopped, what failed, what the evidence \
means, what would clear it, or what happens next. A question mark is not required.
- INSTRUCTION when the message tells the run what to change, where to go back to, or what to do \
next, even when it is phrased politely.
- Read the message in whatever language it is written. Do not treat English phrasing as the only \
way to ask a question, and do not fall back to INSTRUCTION because the wording is unfamiliar.
- answer: two to four short sentences in the pinned response locale {responseLocale}. This locale \
is authoritative; do not answer in the language of the evidence and do not answer in the language \
of the message when the two differ.
- Answer from the structured evidence alone. Name the failed step in ordinary language and stay \
with what the evidence states.
- When the evidence does not cover what was asked, say so plainly, then name what it does cover. \
Never guess at a fact the evidence does not hold.
- Do not tell the reader which button to click or which word to type.
- Leave answer empty when verdict is INSTRUCTION.
- Reply with verdict and answer only. No markdown fences, no quotes, no preamble.\
""")
  HaltQuestionDraft answerHaltQuestion(
      String responseLocale,
      String message,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String candidateSet,
      String followUpText);

  @UserMessage(
      """
Someone typed a message at a create-chain run that is waiting for a person to approve a \
candidate artifact or ask for a different one. Decide whether the message asks about the \
candidate or asks for it to change, and answer it when it asks.

Message: {message}

Structured evidence for the pause (do not invent facts beyond this):
- stageId: {stageId}
- candidate: {candidate}

Rules:
- verdict: exactly QUESTION or INSTRUCTION.
- QUESTION when the message asks about the candidate or the pause: what the candidate contains, \
why it looks the way it does, whether it covers something, what approving it leads to, or what \
happens next. A question mark is not required.
- INSTRUCTION when the message asks for a different candidate: what to add, remove, or correct, \
even when it is phrased politely or as a suggestion.
- A message that asks about the candidate and then asks for a change is an INSTRUCTION.
- Read the message in whatever language it is written. Do not treat English phrasing as the only \
way to ask a question, and do not fall back to INSTRUCTION because the wording is unfamiliar.
- answer: two to four short sentences in the pinned response locale {responseLocale}. This locale \
is authoritative; do not answer in the language of the candidate and do not answer in the \
language of the message when the two differ.
- Answer from the candidate evidence alone. Describe what it holds in ordinary language and stay \
with what it states. Do not invent element names, values, or steps it does not carry.
- When the candidate does not cover what was asked, say so plainly, then name what it does cover.
- Nothing is approved by answering. Do not tell the reader which button to click or which word to \
type, and do not restate the approval request.
- Leave answer empty when verdict is INSTRUCTION.
- Reply with verdict and answer only. No markdown fences, no quotes, no preamble.\
""")
  HaltQuestionDraft answerApprovalQuestion(
      String responseLocale, String message, String stageId, String candidate);
}

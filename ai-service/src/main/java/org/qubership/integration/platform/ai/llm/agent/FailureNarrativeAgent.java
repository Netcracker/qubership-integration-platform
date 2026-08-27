package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors the halt-card explanation from structured failure evidence. The diagnosis turn explains
 * the same evidence; the runtime selects the owner and writes the instruction. A third turn reads a
 * message typed at the halt and answers it when it asks rather than instructs, and a fourth does
 * the same for a message typed at an approval card, where the evidence is the candidate rather than
 * a failure. The runtime supplies fields, not user-facing prose; Retry, Revise, and Agree stay
 * typed actions.
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
- Do not name a change to make. The runtime states the instruction.
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
started it. Write in the pinned response locale {responseLocale}. This locale is \
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
- Two or three short sentences. Name the failed step in ordinary language.
- Say whether validation failed or a generator/compiler step failed, using outcomeClass and \
findings or skill ids. Do not invent names.
- State the root cause from validationFindings.
- You may name artifacts in ordinary language using clarifyRoles. Do not pick an owner, name a \
stage to reopen, or guess which candidate is at fault.
- Do not tell the reader which button to click or which word to type. Do not mention Revise.
- Do not name a change to make. The runtime states the instruction and selects the owner.
- If outcomeClass is RETRYABLE_TECHNICAL_FAILURE, keep the narration to one or two sentences, \
do not blame the plan, and do not offer to go back or clarify the plan.
- Reply with narrative only. No markdown fences, no quotes, no preamble.\
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

  @UserMessage(
      """
Ask the author for the one missing fact this create-chain run needs. Write in the pinned \
response locale {responseLocale}. This locale is authoritative; do not infer a different \
language from the evidence.

Structured evidence (do not invent facts beyond this):
- requestedFact: {requestedFact}
- stageId: {stageId}
- exception: {exceptionMessage}

Rules:
- One short question in the response locale. Do not wrap requestedFact in an English template.
- Name the missing fact in ordinary language the author can answer.
- Reply with only the question. No markdown fences, no quotes, no preamble.\
""")
  String askClarification(
      String responseLocale, String requestedFact, String stageId, String exceptionMessage);
}

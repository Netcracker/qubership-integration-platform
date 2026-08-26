package org.qubership.integration.platform.ai.productpipeline.create;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.llm.agent.HaltQuestionDraft;
import org.qubership.integration.platform.ai.llm.agent.OwnerDiagnosisDraft;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/**
 * Model-authored halt-card body from structured evidence. Empty when the turn fails; callers keep
 * Retry and the raw evidence instead of a canned sentence.
 *
 * <p>A turn is bounded twice: it waits at most {@code timeout} for the model, and one run spends at
 * most {@code maxCallsPerRun} turns. A spent budget and an expired timeout both take the same path
 * as a failed turn, so a run that halts in a loop cannot multiply the cost of narrating it, and a
 * slow model cannot hold the halt card open.
 *
 * <p>The per-run count is process-local and resets when the service restarts. The budget guards
 * cost on a card body the run can always do without; the raw evidence it falls back to is the
 * durable part. Making the count survive a restart would put a journal write on every halt to bound
 * something a restart already interrupts.
 *
 * <p>Questions typed at a pause take the same bounded turn, but they are deduplicated rather than
 * rationed: an answer is remembered by the identity of the question and of the evidence it was
 * asked against, so asking twice about a run that has not moved costs no call at all. A halt and an
 * approval card are different pauses over different evidence, so each answer is remembered under
 * its own pause kind and the two cannot answer for each other.
 */
public final class FailureNarrative {

  private static final Logger LOG = Logger.getLogger(FailureNarrative.class);

  /** Budget that never runs out, for the callers that hold no limits. */
  private static final int UNBOUNDED_CALLS = Integer.MAX_VALUE;
  private static final Duration DEFAULT_CACHE_IDLE_TIMEOUT = Duration.ofHours(1);

  /** Verdict token that reads a message at a pause as a question rather than an instruction. */
  private static final String QUESTION_VERDICT = "QUESTION";

  /** Answer-cache namespaces, so a question at one kind of pause never answers for the other. */
  private static final String HALT_PAUSE = "halt";

  private static final String APPROVAL_PAUSE = "approval";

  /** Ceiling on remembered answers, so a run asked about all day cannot grow without end. */
  private static final int MAX_CACHED_ANSWERS = 1_000;

  private final FailureNarrativeAgent agent;
  private final int maxCallsPerRun;
  private final Duration timeout;
  private final ConcurrentMap<String, Integer> callsByRun;

  /**
   * Answers by the identity of the question and of the evidence it was asked against. Process-local
   * on purpose: losing it on a restart costs one model call, not correctness, so it does not earn a
   * journal write per question.
   */
  private final ConcurrentMap<String, String> answersByQuestion;

  private volatile ExecutorService workers;

  /**
   * @param maxCallsPerRun model calls one run may spend on halt narration; zero blocks the turn
   * @param timeout wall-clock bound on one turn; {@code null} waits for the model
   */
  public FailureNarrative(FailureNarrativeAgent agent, int maxCallsPerRun, Duration timeout) {
    this(agent, maxCallsPerRun, timeout, DEFAULT_CACHE_IDLE_TIMEOUT);
  }

  /**
   * @param cacheIdleTimeout idle lifetime for the per-run call budget
   */
  public FailureNarrative(
      FailureNarrativeAgent agent,
      int maxCallsPerRun,
      Duration timeout,
      Duration cacheIdleTimeout) {
    this.agent = agent;
    this.maxCallsPerRun = Math.max(0, maxCallsPerRun);
    this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? null : timeout;
    Duration idle =
        cacheIdleTimeout == null || cacheIdleTimeout.isZero() || cacheIdleTimeout.isNegative()
            ? DEFAULT_CACHE_IDLE_TIMEOUT
            : cacheIdleTimeout;
    this.callsByRun =
        Caffeine.newBuilder().expireAfterAccess(idle).<String, Integer>build().asMap();
    this.answersByQuestion =
        Caffeine.newBuilder()
            .expireAfterAccess(idle)
            .maximumSize(MAX_CACHED_ANSWERS)
            .<String, String>build()
            .asMap();
  }

  /** Unbounded variant for tests and callers that hold no configuration. */
  public FailureNarrative(FailureNarrativeAgent agent) {
    this(agent, UNBOUNDED_CALLS, null);
  }

  /** Test / runtime helper without LLM; narrate returns empty so the caller keeps raw evidence. */
  public FailureNarrative() {
    this(null);
  }

  /**
   * Asks the model to explain the halt. Empty when there is no agent, the run has spent its budget,
   * the turn times out, the call fails, or the reply is blank. Never a fallback marketing sentence.
   */
  public Optional<String> narrate(
      String runId,
      String responseLocale,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings) {
    return narrate(
        runId, responseLocale, stageId, outcomeClass, exceptionMessage, validationFindings, "");
  }

  public Optional<String> narrate(
      String runId,
      String responseLocale,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String followUpText) {
    if (agent == null) {
      return Optional.empty();
    }
    String locale = normalizedLocale(responseLocale);
    String stage = stageId == null || stageId.isBlank() ? "" : stageId.trim();
    String outcome = outcomeClass == null ? "" : outcomeClass.name();
    String exception = exceptionMessage == null ? "" : exceptionMessage;
    String findings = optionalField(validationFindings);
    String followUp = optionalField(followUpText);
    String authored =
        runTurn(
            runId,
            "Failure narrative",
            () -> agent.narrate(locale, stage, outcome, exception, findings, followUp, ""));
    if (authored != null && !authored.isBlank()) {
      return Optional.of(authored.trim());
    }
    return Optional.empty();
  }

  /**
   * Same turn as the halt narrative, plus an owner from {@code candidates} and a remedy from the
   * closed {@link HaltRemedy} set. An owner outside the set is dropped; the narrative is kept.
   * Finding category remaps a self, empty, or insufficient owner to the earliest sufficient
   * producer in the set. A follow-up that names exactly one candidate wins over that remap.
   */
  public OwnerDiagnosis diagnose(
      String runId,
      String responseLocale,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings,
      List<OwnerCandidate> candidates,
      String followUpText) {
    List<OwnerCandidate> closed = candidates == null ? List.of() : List.copyOf(candidates);
    String stage = stageId == null || stageId.isBlank() ? "" : stageId.trim();
    String exception = exceptionMessage == null ? "" : exceptionMessage;
    String findings = optionalField(validationFindings);
    if (agent == null) {
      return preferOwner(OwnerDiagnosis.none(""), closed, stage, findings, exception, followUpText);
    }
    String locale = normalizedLocale(responseLocale);
    String outcome = outcomeClass == null ? "" : outcomeClass.name();
    String followUp = optionalField(followUpText);
    String candidateSet = OwnerCandidateSet.format(closed);
    String clarifyRoles = OwnerCandidateSet.formatClarifyRoles(closed);
    OwnerDiagnosisDraft draft =
        runTurn(
            runId,
            "Owner diagnosis",
            () ->
                agent.diagnose(
                    locale,
                    stage,
                    outcome,
                    exception,
                    findings,
                    candidateSet,
                    followUp,
                    clarifyRoles));
    return preferOwner(fromDraft(draft, closed), closed, stage, findings, exception, followUpText);
  }

  /**
   * Reads a message typed at a recoverable halt and answers it when it asks about the run. Empty
   * when the message instructs the run instead, when the turn fails, or when there is no agent,
   * which leaves the caller's existing follow-up branches in charge.
   *
   * <p>One turn both decides and answers. Two turns would spend the run's budget twice on a single
   * message and give a hung model a second chance to hold the pause open, and the classifier would
   * need the same evidence the answer is written from anyway.
   *
   * <p>Answers are remembered by identity rather than rationed by a count: the same question
   * against unchanged evidence comes back without a model call and without spending budget, and
   * evidence that has moved on is a different key and a fresh answer. ADR 0005 made the same trade
   * one layer down: the budget belongs on the defect, not on the person asking about it.
   */
  public Optional<String> answerHaltQuestion(
      String runId,
      String responseLocale,
      String message,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings,
      List<OwnerCandidate> candidates,
      String followUpText) {
    if (agent == null || message == null || message.isBlank()) {
      return Optional.empty();
    }
    String question = message.trim();
    String stage = stageId == null || stageId.isBlank() ? "" : stageId.trim();
    String outcome = outcomeClass == null ? "" : outcomeClass.name();
    String exception = exceptionMessage == null ? "" : exceptionMessage;
    String findings = optionalField(validationFindings);
    String candidateSet = OwnerCandidateSet.format(candidates == null ? List.of() : candidates);
    String followUp = optionalField(followUpText);
    String locale = normalizedLocale(responseLocale);
    return answeredOnce(
        runId,
        "Halt question",
        answerKey(
            HALT_PAUSE, question, stage, outcome, exception, findings, candidateSet, followUp),
        () ->
            agent.answerHaltQuestion(
                locale, question, stage, outcome, exception, findings, candidateSet, followUp));
  }

  /**
   * Reads a message typed at an approval card and answers it when it asks about the candidate.
   * Empty when the message asks for a different candidate instead, when the turn fails, or when
   * there is no agent, which leaves the refine path in charge exactly as before.
   *
   * <p>An approval pause holds no failure, so this is a sibling turn rather than the halt turn with
   * its fields blanked: outcome class, exception, and findings are absent here, and handing the
   * halt turn empty ones would have it answer from nothing or, worse, from an earlier halt the
   * question is not about. What the pause does hold is {@code candidate}, the artifact the person
   * is being asked to accept, which the caller assembles.
   *
   * <p>The answer cache is the same one halt questions use, under its own pause kind. The candidate
   * evidence carries the content hash of the artifact under approval, so a new candidate is a new
   * key and a stale answer cannot be served.
   */
  public Optional<String> answerApprovalQuestion(
      String runId,
      String responseLocale,
      String message,
      String stageId,
      String candidate) {
    if (agent == null || message == null || message.isBlank()) {
      return Optional.empty();
    }
    String question = message.trim();
    String stage = stageId == null || stageId.isBlank() ? "" : stageId.trim();
    String artifact = optionalField(candidate);
    String locale = normalizedLocale(responseLocale);
    return answeredOnce(
        runId,
        "Approval question",
        answerKey(APPROVAL_PAUSE, question, stage, artifact),
        () -> agent.answerApprovalQuestion(locale, question, stage, artifact));
  }

  /**
   * Serves a remembered answer or spends one bounded turn on a fresh one. A verdict of INSTRUCTION
   * is remembered as an empty answer, because it is as durable a reading of the message as an
   * answer is; a turn that failed is not remembered at all, since freezing a transient outage into
   * a permanent verdict would silence every later ask.
   */
  private Optional<String> answeredOnce(
      String runId, String turnName, String key, Supplier<HaltQuestionDraft> turn) {
    String remembered = answersByQuestion.get(key);
    if (remembered != null) {
      return remembered.isEmpty() ? Optional.empty() : Optional.of(remembered);
    }
    HaltQuestionDraft draft = runTurn(runId, turnName, turn);
    if (draft == null) {
      return Optional.empty();
    }
    String answer = answerOf(draft);
    answersByQuestion.put(key, answer);
    return answer.isEmpty() ? Optional.empty() : Optional.of(answer);
  }

  /**
   * The answer a draft contributes: blank for an instruction and for a verdict the closed pair does
   * not hold, so an unrecognized reply leaves the existing follow-up paths untouched.
   */
  private static String answerOf(HaltQuestionDraft draft) {
    return QUESTION_VERDICT.equalsIgnoreCase(draft.verdict().trim()) ? draft.answer().trim() : "";
  }

  /**
   * Identity of one question against one pause: {@code pauseKind + NUL + failureSignature(evidence)
   * + NUL + failureSignature(question)}. Both signed halves take the normalization the repair
   * budget already uses, so wording that differs only by case, spacing, or a masked id is one key,
   * and evidence that has moved on is another. {@code pauseKind} is a fixed token holding no NUL,
   * so a halt question and an approval question on one run stay in separate namespaces even when
   * their evidence normalizes alike.
   */
  private static String answerKey(String pauseKind, String question, String... evidence) {
    return pauseKind
        + '\u0000'
        + ToolCallFingerprints.failureSignature(String.join("\n", evidence))
        + '\u0000'
        + ToolCallFingerprints.failureSignature(question);
  }

  /**
   * Runs one turn under the per-run budget and the timeout. Returns {@code null} when the budget is
   * spent, the timeout expires, or the model call throws, which is the raw-evidence path the caller
   * already takes for a failed turn.
   */
  private <T> T runTurn(String runId, String turnName, Supplier<T> turn) {
    if (!consumeCall(runId)) {
      LOG.warnf(
          "%s budget of %d model calls is spent for run %s; keeping raw evidence",
          turnName, maxCallsPerRun, runId);
      return null;
    }
    if (timeout == null) {
      return callOrNull(turnName, turn);
    }
    Future<T> pending = workers().submit(turn::get);
    try {
      return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      pending.cancel(true);
      LOG.warnf("%s timed out after %s; keeping raw evidence", turnName, timeout);
    } catch (ExecutionException ex) {
      LOG.warnf(ex.getCause(), "%s LLM failed; keeping raw evidence", turnName);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      LOG.warnf("%s was interrupted; keeping raw evidence", turnName);
    }
    return null;
  }

  private static <T> T callOrNull(String turnName, Supplier<T> turn) {
    try {
      return turn.get();
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "%s LLM failed; keeping raw evidence", turnName);
      return null;
    }
  }

  /**
   * Counts one attempted call against the run. A call that fails or times out still counts: the run
   * paid for it either way. Runs without an id share one budget.
   */
  private boolean consumeCall(String runId) {
    if (maxCallsPerRun >= UNBOUNDED_CALLS) {
      return true;
    }
    if (maxCallsPerRun == 0) {
      return false;
    }
    String key = runId == null ? "" : runId.trim();
    return callsByRun.merge(key, 1, Integer::sum) <= maxCallsPerRun;
  }

  /**
   * Daemon workers that let a turn be abandoned on timeout. A worker is released when the model
   * call it was left holding returns.
   */
  private ExecutorService workers() {
    ExecutorService running = workers;
    if (running != null) {
      return running;
    }
    synchronized (this) {
      if (workers == null) {
        workers =
            Executors.newCachedThreadPool(
                runnable -> {
                  Thread thread = new Thread(runnable, "failure-narrative");
                  thread.setDaemon(true);
                  return thread;
                });
      }
      return workers;
    }
  }

  private static OwnerDiagnosis preferOwner(
      OwnerDiagnosis diagnosis,
      List<OwnerCandidate> candidates,
      String failedStageId,
      String findings,
      String evidence,
      String followUpText) {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            diagnosis, candidates, failedStageId, findings, evidence);
    return OwnerCandidateSet.preferNamedOwner(remapped, candidates, followUpText);
  }

  /** Formats validation findings for the narrative turn; empty when none are present. */
  public static String findingsText(List<ArtifactCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (ArtifactCandidate candidate : candidates) {
      appendFindings(lines, candidate);
    }
    return String.join("\n", lines);
  }

  private static OwnerDiagnosis fromDraft(
      OwnerDiagnosisDraft draft, List<OwnerCandidate> candidates) {
    if (draft == null) {
      return OwnerDiagnosis.none("");
    }
    String narrative = draft.narrative() == null ? "" : draft.narrative().trim();
    String owner = draft.ownerStageId() == null ? "" : draft.ownerStageId().trim();
    boolean ownerHonored = OwnerCandidateSet.containsStage(candidates, owner);
    OwnerDiagnosis diagnosis;
    if (draft.ambiguous()) {
      diagnosis = OwnerDiagnosis.ask(narrative);
    } else if (ownerHonored) {
      diagnosis = OwnerDiagnosis.of(narrative, owner);
    } else {
      diagnosis = OwnerDiagnosis.none(narrative);
    }
    return diagnosis.withRemedy(honoredRemedy(draft, diagnosis), draft.instruction());
  }

  /**
   * The remedy the card may state. A token outside the closed set parses to {@link
   * HaltRemedy#NONE}, and a go-back is honored only when the model named a stage the candidate set
   * holds, so a dropped remedy costs the card its extra sentence and leaves the narrative alone.
   */
  private static HaltRemedy honoredRemedy(OwnerDiagnosisDraft draft, OwnerDiagnosis diagnosis) {
    HaltRemedy remedy = HaltRemedy.fromModelValue(draft.remedy());
    return remedy == HaltRemedy.REOPEN_STAGE && diagnosis.owner().isEmpty()
        ? HaltRemedy.NONE
        : remedy;
  }

  private static void appendFindings(List<String> lines, ArtifactCandidate candidate) {
    if (candidate == null || !(candidate.payload() instanceof PlanValidationResult result)) {
      return;
    }
    for (PlanValidationFinding finding : result.findings()) {
      if (finding != null) {
        lines.add(formatFinding(finding));
      }
    }
  }

  private static String formatFinding(PlanValidationFinding finding) {
    String code = finding.code() == null ? "" : finding.code();
    String message = finding.message() == null ? "" : finding.message();
    return code + ": " + message + (finding.blocker() ? " (blocker)" : "");
  }

  private static String optionalField(String value) {
    return value == null || value.isBlank() ? "(none)" : value.trim();
  }

  private static String normalizedLocale(String responseLocale) {
    return responseLocale == null || responseLocale.isBlank()
        ? ResponseLocaleResolver.DEFAULT_LOCALE
        : responseLocale.trim();
  }
}

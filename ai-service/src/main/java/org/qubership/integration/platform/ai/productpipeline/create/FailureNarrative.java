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
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
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
 */
public final class FailureNarrative {

  private static final Logger LOG = Logger.getLogger(FailureNarrative.class);

  /** Budget that never runs out, for the callers that hold no limits. */
  private static final int UNBOUNDED_CALLS = Integer.MAX_VALUE;
  private static final Duration DEFAULT_CACHE_IDLE_TIMEOUT = Duration.ofHours(1);

  private final FailureNarrativeAgent agent;
  private final int maxCallsPerRun;
  private final Duration timeout;
  private final ConcurrentMap<String, Integer> callsByRun;
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
    this.callsByRun =
        Caffeine.newBuilder()
            .expireAfterAccess(
                cacheIdleTimeout == null || cacheIdleTimeout.isZero() || cacheIdleTimeout.isNegative()
                    ? DEFAULT_CACHE_IDLE_TIMEOUT
                    : cacheIdleTimeout)
            .<String, Integer>build()
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
   * Same turn as the halt narrative, plus an owner from {@code candidates}. An owner outside the
   * set is dropped; the narrative is kept. Finding category remaps a self, empty, or insufficient
   * owner to the earliest sufficient producer in the set. A follow-up that names exactly one
   * candidate wins over that remap.
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
    if (draft.ambiguous()) {
      return OwnerDiagnosis.ask(narrative);
    }
    String owner = draft.ownerStageId() == null ? "" : draft.ownerStageId().trim();
    if (owner.isBlank()) {
      return OwnerDiagnosis.none(narrative);
    }
    if (!OwnerCandidateSet.containsStage(candidates, owner)) {
      return OwnerDiagnosis.none(narrative);
    }
    return OwnerDiagnosis.of(narrative, owner);
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

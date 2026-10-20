package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;

/**
 * One authority for whether another recovery attempt is allowed. Guards ask this module; they do
 * not count journal transitions themselves.
 *
 * <p>Durability is the run journal. Reconstruct by passing the document's transitions; nothing is
 * held in process memory.
 */
public final class RecoveryAttemptLedger {

  public static final String AUTHOR_REOPEN_REASON_PREFIX = "author-reopen:";
  public static final String AUTOMATIC_REOPEN_REASON_PREFIX = "automatic-reopen:";
  public static final String CORRECTION_REASON_PREFIX = "recovery-correction:";
  public static final String CLEAN_REBUILD_REASON_PREFIX = "recovery-clean-rebuild:";
  public static final String UNSENT_ATTEMPT_REASON_PREFIX = "recovery-not-dispatched:";
  public static final String SETTLED_ATTEMPT_REASON_PREFIX = "recovery-dispatched:";
  public static final String GRANT_REASON_PREFIX = "repair-grant:";
  public static final String GRANT_TARGETED_REASON_PREFIX = GRANT_REASON_PREFIX + "targeted:";
  public static final int ADDITIONAL_ATTEMPTS_PER_STRATEGY = 2;
  public static final int MAX_CLEAN_REBUILDS_PER_DEFECT = 2;

  /**
   * Absolute per-run backstop. Same default as {@code
   * qip.ai.create.failure-narrative.max-calls-per-run}; a working conversation does not reach it.
   */
  public static final int DEFAULT_PER_RUN_CEILING = 12;

  /** Corrective task invocations allowed after the original failure of one document cause. */
  public static final int DOCUMENT_CORRECTIVE_LIMIT = 3;

  /** Owner slot for a document cause. The current stage is not part of the identity. */
  public static final String DOCUMENT_CAUSE_OWNER = "work-document-cause";

  public static final String TECHNICAL_RETRY_REASON_PREFIX = "recovery-technical-retry:";

  /** Who sent the reopen command. The automatic budget counts only {@link #AUTOMATIC}. */
  public enum ReopenInitiator {
    AUTHOR,
    AUTOMATIC
  }

  /**
   * Per-key limits and the absolute per-run ceiling. Values come from {@code qip.ai.create.*};
   * {@link #defaults()} matches the constants already in the runtime.
   */
  public record Limits(int maxSemanticRepairs, int maxCausalReopens, int perRunCeiling, int maxCleanRebuilds) {

    public Limits(int maxSemanticRepairs, int maxCausalReopens, int perRunCeiling) {
      this(maxSemanticRepairs, maxCausalReopens, perRunCeiling, MAX_CLEAN_REBUILDS_PER_DEFECT);
    }

    public Limits {
      maxSemanticRepairs = Math.max(0, maxSemanticRepairs);
      maxCausalReopens = Math.max(0, maxCausalReopens);
      perRunCeiling = Math.max(0, perRunCeiling);
      maxCleanRebuilds = Math.max(0, maxCleanRebuilds);
    }

    public static Limits defaults() {
      return new Limits(
          ProductPipelineStageExecutor.MAX_SEMANTIC_REPAIRS,
          ProductPipelineRunSupport.MAX_CAUSAL_REOPENS,
          DEFAULT_PER_RUN_CEILING);
    }
  }

  private static final char FIELD = '\u0000';
  private static final String FINDING_ID_PREFIX = "finding:";

  private final Limits limits;

  public RecoveryAttemptLedger() {
    this(Limits.defaults());
  }

  public RecoveryAttemptLedger(Limits limits) {
    this.limits = limits == null ? Limits.defaults() : limits;
  }

  public Limits limits() {
    return limits;
  }

  /**
   * One budget slot for a work-document cause. {@code causeIdentity} is
   * {@code (runId, originRecordId, issueCategory, canonicalFieldPointer)}. The slot stores a hash
   * of that tuple so a field separator inside the tuple cannot split the journal record. Stage,
   * revision, and wording are not part of it.
   */
  public RecoveryAttemptKey documentCauseKey(String causeIdentity) {
    if (causeIdentity == null || causeIdentity.isBlank()) {
      throw new IllegalArgumentException("Cause identity is required.");
    }
    return new RecoveryAttemptKey(
        DOCUMENT_CAUSE_OWNER, RecoveryCauseCode.VALIDATION_BLOCKER, sha256Hex(causeIdentity), 0);
  }

  /** Run-level slot for transport retries. It does not spend a document cause's corrective budget. */
  public RecoveryAttemptKey technicalKey(String runId) {
    return new RecoveryAttemptKey(
        DOCUMENT_CAUSE_OWNER,
        RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED,
        runId == null ? "" : runId,
        0);
  }

  public boolean mayTechnicalRetry(
      List<RunTransition> transitions, RecoveryAttemptKey key, int executorLimit) {
    if (key == null || executorLimit <= 0 || ceilingReached(transitions)) {
      return false;
    }
    return technicalRetriesUsed(transitions, key) < executorLimit;
  }

  public String recordTechnicalRetry(RecoveryAttemptKey key) {
    if (key == null) {
      return TECHNICAL_RETRY_REASON_PREFIX;
    }
    return TECHNICAL_RETRY_REASON_PREFIX + payload(key, "");
  }

  /**
   * Identity of the structured findings, not of author text and not of a formatted exception. Does
   * not mask identifiers: two findings that name different properties are two defects.
   */
  public static String evidenceIdentity(RecoveryCause cause) {
    RecoveryCause typed = cause == null ? RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER) : cause;
    StringBuilder canonical = new StringBuilder();
    for (String identity : typed.findings().stream().map(RecoveryAttemptLedger::findingIdentity).sorted().toList()) {
      canonical.append(identity).append('\n');
    }
    canonical.append(FIELD).append(nvl(typed.requestedFact()));
    return sha256Hex(canonical.toString());
  }

  private static String findingIdentity(PlanValidationFinding finding) {
    if (!finding.originRecordId().isBlank() || !finding.fieldPointer().isBlank()) {
      return finding.code() + FIELD + "location" + FIELD + finding.originRecordId() + FIELD + finding.fieldPointer();
    }
    return finding.code() + FIELD + finding.message();
  }

  /**
   * Content hashes of approved artifacts the owner and its predecessors produced. Excludes user
   * input, so a rephrasing that leaves those artifacts identical does not change the identity.
   */
  public static String inputArtifactIdentity(ProductPipelineRunDocument doc, String ownerStageId) {
    if (doc == null || doc.run() == null) {
      return "";
    }
    return inputArtifactIdentity(doc.run().stages(), ownerStageId);
  }

  /**
   * Same identity as {@link #inputArtifactIdentity(ProductPipelineRunDocument, String)} from a
   * snapshot list, including an in-memory approval that is not committed yet.
   */
  public static String inputArtifactIdentity(List<StageSnapshot> stages, String ownerStageId) {
    if (stages == null) {
      return "";
    }
    String owner = ownerStageId == null ? "" : ownerStageId;
    StringBuilder identity = new StringBuilder();
    for (StageSnapshot snapshot : stages) {
      String hash = approvedContentHash(snapshot);
      if (!hash.isEmpty()) {
        if (identity.length() > 0) {
          identity.append(FIELD);
        }
        identity.append(snapshot.stageId()).append('=').append(hash);
      }
      if (owner.equals(snapshot.stageId())) {
        break;
      }
    }
    return identity.toString();
  }

  public RecoveryAttemptKey key(
      String ownerStageId,
      RecoveryCause cause,
      String inputArtifactIdentity,
      List<RunTransition> transitions) {
    RecoveryCause typed = cause == null ? RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER) : cause;
    String evidenceId = evidenceIdentity(typed);
    int epoch =
        correctionEpoch(
            transitions, ownerStageId, typed.causeCode(), evidenceId, inputArtifactIdentity);
    var findingKeys = new TreeMap<String, RecoveryAttemptKey>();
    if (!typed.findings().isEmpty()) {
      List<PlanValidationFinding> blockers = typed.findings().stream().filter(PlanValidationFinding::blocker).toList();
      for (PlanValidationFinding finding : blockers.isEmpty() ? typed.findings() : blockers) {
        String identity = FINDING_ID_PREFIX
            + evidenceIdentity(new RecoveryCause(typed.causeCode(), List.of(finding), typed.requestedFact()));
        RecoveryCauseCode memberCause = RecoveryCauseCode.VALIDATION_BLOCKER;
        findingKeys.put(identity, new RecoveryAttemptKey(ownerStageId, memberCause, identity,
            correctionEpoch(transitions, ownerStageId, memberCause, identity, inputArtifactIdentity)));
      }
    }
    return new RecoveryAttemptKey(ownerStageId, typed.causeCode(), evidenceId, epoch,
        List.copyOf(findingKeys.values()));
  }

  /**
   * Records that an accepted correction observed {@code inputArtifactIdentity}. Returns a journal
   * reason when the identity is new for this defect; empty when it is a rephrasing of the same
   * artifact, when nothing has been observed yet, or when origin is not trusted.
   */
  public String recordCorrection(
      List<RunTransition> transitions,
      RecoveryAttemptKey key,
      String inputArtifactIdentity,
      InputOrigin origin) {
    if (key == null || !InputOrigin.of(origin).isTrusted()) {
      return "";
    }
    if (!correctionAdvancesEpoch(transitions, key, inputArtifactIdentity)) {
      return "";
    }
    return CORRECTION_REASON_PREFIX + payload(key, nvl(inputArtifactIdentity));
  }

  public boolean correctionAdvancesEpoch(
      List<RunTransition> transitions, RecoveryAttemptKey key, String inputArtifactIdentity) {
    if (key == null) {
      return false;
    }
    if (key.findingKeys().stream().anyMatch(member -> correctionAdvancesEpoch(transitions, member, inputArtifactIdentity))) {
      return true;
    }
    List<String> seen =
        observedArtifacts(transitions, key.ownerStageId(), key.causeCode(), key.evidenceIdentity());
    String current = artifactToken(inputArtifactIdentity);
    return !seen.isEmpty() && !current.isEmpty() && !seen.contains(current);
  }

  public boolean mayRepair(
      List<RunTransition> transitions, RecoveryAttemptKey key, InputOrigin origin) {
    if (key == null || ceilingReached(transitions)) {
      return false;
    }
    return targetedRemaining(transitions, key, origin) > 0;
  }

  public String recordRepair(RecoveryAttemptKey key, String inputArtifactIdentity) {
    if (key == null) {
      return ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX;
    }
    return ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX
        + payload(key, nvl(inputArtifactIdentity));
  }

  /** Clean regeneration has separate credit and does not consume targeted correction credit. */
  public boolean mayCleanRebuild(List<RunTransition> transitions, RecoveryAttemptKey key) {
    return key != null
        && !ceilingReached(transitions)
        && cleanRemaining(transitions, key) > 0;
  }

  public String recordGrant(RecoveryAttemptKey key, String inputArtifactIdentity) {
    return GRANT_REASON_PREFIX + payload(key, inputArtifactIdentity);
  }

  /** The accepted offer and its first correction intent share one atomic journal receipt. */
  public String recordGrantWithTargetedAttempt(RecoveryAttemptKey key, String inputArtifactIdentity) {
    return GRANT_TARGETED_REASON_PREFIX + payload(key, inputArtifactIdentity);
  }

  /** Restores only the unsent intent immediately preceding this stage's resource wait. */
  public java.util.Optional<String> reissueUnsentAttempt(List<RunTransition> transitions, String stageId) {
    for (int i = transitions.size() - 1; i >= 0; i--) {
      RunTransition transition = transitions.get(i);
      if (!stageId.equals(transition.stageId())) {
        break;
      }
      String reason = transition.reason() == null ? "" : transition.reason();
      if (reason.startsWith(UNSENT_ATTEMPT_REASON_PREFIX)) {
        String revision = reason.substring(UNSENT_ATTEMPT_REASON_PREFIX.length());
        return transitions.stream().filter(intent -> revision.equals(Long.toString(intent.toRevision()))
                && stageId.equals(intent.stageId()) && parse(intent.reason()) != null)
            .map(RunTransition::reason).map(original -> original.startsWith(GRANT_TARGETED_REASON_PREFIX)
                ? ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX
                    + original.substring(GRANT_TARGETED_REASON_PREFIX.length()) : original).findFirst();
      }
      if (transition.toStatus() != org.qubership.integration.platform.ai.productpipeline.store.RunStatus.WAITING_FOR_INPUT) {
        break;
      }
    }
    return java.util.Optional.empty();
  }

  private int targetedRemaining(List<RunTransition> transitions, RecoveryAttemptKey key, InputOrigin origin) {
    if (!key.findingKeys().isEmpty()) {
      return Math.max(0, key.findingKeys().stream()
          .mapToInt(member -> targetedRemaining(transitions, member, origin)).max().orElse(0)
          - aggregateDebitsWithoutMembers(transitions, key, false, origin));
    }
    return Math.max(0, limits.maxSemanticRepairs() + grantedAttempts(transitions, key) - repairsUsed(transitions, key, origin));
  }

  private int cleanRemaining(List<RunTransition> transitions, RecoveryAttemptKey key) {
    if (!key.findingKeys().isEmpty()) {
      return Math.max(0, key.findingKeys().stream().mapToInt(member -> cleanRemaining(transitions, member)).max().orElse(0)
          - aggregateDebitsWithoutMembers(transitions, key, true, InputOrigin.ABSENT));
    }
    return Math.max(0, limits.maxCleanRebuilds() + grantedAttempts(transitions, key) - cleanRebuildsUsed(transitions, key));
  }

  private int aggregateDebitsWithoutMembers(
      List<RunTransition> transitions, RecoveryAttemptKey key, boolean clean, InputOrigin origin) {
    var aggregate = new RecoveryAttemptKey(key.ownerStageId(), key.causeCode(), key.evidenceIdentity(), key.correctionEpoch());
    return (int) dispatchedTransitions(transitions).stream().filter(transition -> {
      String reason = transition.reason();
      if (reason == null || !(clean ? reason.startsWith(CLEAN_REBUILD_REASON_PREFIX)
          : reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX))) {
        return false;
      }
      Parsed parsed = parse(reason);
      return parsed != null && parsed.findingKeys().isEmpty() && matchesDefect(parsed, aggregate)
          && (!InputOrigin.of(origin).isTrusted() || matchedEpoch(parsed, aggregate) == aggregate.correctionEpoch());
    }).count();
  }

  private int grantedAttempts(List<RunTransition> transitions, RecoveryAttemptKey key) {
    if (transitions == null || key == null) {
      return 0;
    }
    int grants = 0;
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null || !reason.startsWith(GRANT_REASON_PREFIX)) {
        continue;
      }
      Parsed parsed = parse(reason);
      if (parsed != null && matchesDefect(parsed, key) && matchedEpoch(parsed, key) == key.correctionEpoch()) {
        grants++;
      }
    }
    int memberMinimum = key.findingKeys().stream()
        .mapToInt(member -> grantedAttempts(transitions, member)).min().orElse(0);
    return Math.max(grants * ADDITIONAL_ATTEMPTS_PER_STRATEGY, memberMinimum);
  }

  public String recordCleanRebuild(RecoveryAttemptKey key, String inputArtifactIdentity) {
    return CLEAN_REBUILD_REASON_PREFIX + payload(key, nvl(inputArtifactIdentity));
  }

  /**
   * True when this is a chain author's correction of a known brief defect. Automatic replay of the
   * same defect stays blocked by {@link #ownerAlreadyReopened}.
   */
  public static boolean isAuthorMissingBriefFactsCorrection(
      ReopenInitiator initiator, RecoveryCauseCode causeCode) {
    return initiator == ReopenInitiator.AUTHOR && RecoveryCause.isKnownBriefDefect(causeCode);
  }

  public boolean mayReopen(
      List<RunTransition> transitions,
      RecoveryAttemptKey key,
      InputOrigin origin,
      ReopenInitiator initiator,
      String legacyFailureSignature) {
    if (key == null || ceilingReached(transitions)) {
      return false;
    }
    if (ownerAlreadyReopened(transitions, key, legacyFailureSignature)
        && !isAuthorMissingBriefFactsCorrection(initiator, key.causeCode())) {
      return false;
    }
    if (initiator != ReopenInitiator.AUTOMATIC) {
      return true;
    }
    return automaticReopensUsed(transitions, key, origin) < limits.maxCausalReopens();
  }

  public String recordReopen(
      RecoveryAttemptKey key, ReopenInitiator initiator, String inputArtifactIdentity) {
    String prefix =
        initiator == ReopenInitiator.AUTHOR
            ? AUTHOR_REOPEN_REASON_PREFIX
            : AUTOMATIC_REOPEN_REASON_PREFIX;
    if (key == null) {
      return prefix;
    }
    return prefix + payload(key, nvl(inputArtifactIdentity));
  }

  /**
   * Whether this owner has already seen this defect. Counts author, automatic, and legacy reopen
   * prefixes. Ignores epoch: the question is whether the owner has seen the defect, not who sent
   * the command.
   */
  public boolean ownerAlreadyReopened(
      List<RunTransition> transitions, RecoveryAttemptKey key, String legacyFailureSignature) {
    if (key == null || transitions == null) {
      return false;
    }
    List<RecoveryAttemptKey> members = key.findingKeys();
    if (!members.isEmpty()
        && members.stream().allMatch(member -> ownerAlreadyReopened(transitions, member, legacyFailureSignature))) {
      return true;
    }
    String legacy =
        ProductPipelineRunSupport.causalReopenReason(key.ownerStageId(), nvl(legacyFailureSignature));
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null) {
        continue;
      }
      if (reason.equals(legacy)) {
        return true;
      }
      Parsed parsed = parse(reason);
      if (parsed != null
          && parsed.reopen
          && matchesDefect(parsed, key)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when automatic replay already reopened this owner for this defect. Author reopens do not
   * count; a later unchanged candidate still has no fresh automatic budget.
   */
  public boolean automaticReopenRecorded(
      List<RunTransition> transitions, RecoveryAttemptKey key, String legacyFailureSignature) {
    if (key == null || transitions == null) {
      return false;
    }
    List<RecoveryAttemptKey> members = key.findingKeys();
    if (!members.isEmpty()
        && members.stream().allMatch(member -> automaticReopenRecorded(transitions, member, legacyFailureSignature))) {
      return true;
    }
    String legacy =
        ProductPipelineRunSupport.causalReopenReason(key.ownerStageId(), nvl(legacyFailureSignature));
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null) {
        continue;
      }
      if (reason.equals(legacy)) {
        return true;
      }
      if (!reason.startsWith(AUTOMATIC_REOPEN_REASON_PREFIX)) {
        continue;
      }
      Parsed parsed = parse(reason);
      if (parsed != null
          && matchesDefect(parsed, key)) {
        return true;
      }
    }
    return false;
  }

  public SemanticRecoveryState.RemainingAttempts remaining(
      List<RunTransition> transitions, RecoveryAttemptKey key, InputOrigin origin) {
    if (key == null || ceilingReached(transitions)) {
      return SemanticRecoveryState.RemainingAttempts.none();
    }
    return new SemanticRecoveryState.RemainingAttempts(
        targetedRemaining(transitions, key, origin),
        Math.max(0, limits.maxCausalReopens() - automaticReopensUsed(transitions, key, origin)),
        cleanRemaining(transitions, key));
  }

  public static boolean isReopenReason(String reason) {
    if (reason == null) {
      return false;
    }
    return reason.startsWith(ProductPipelineRunSupport.CAUSAL_REOPEN_REASON_PREFIX)
        || reason.startsWith(AUTHOR_REOPEN_REASON_PREFIX)
        || reason.startsWith(AUTOMATIC_REOPEN_REASON_PREFIX);
  }

  public int repairsUsed(
      List<RunTransition> transitions, RecoveryAttemptKey key, InputOrigin origin) {
    if (key == null || transitions == null) {
      return 0;
    }
    boolean trusted = InputOrigin.of(origin).isTrusted();
    int used = 0;
    for (RunTransition transition : dispatchedTransitions(transitions)) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null
          || !reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)
              && !reason.startsWith(GRANT_TARGETED_REASON_PREFIX)) {
        continue;
      }
      Parsed parsed = parse(reason);
      if (parsed == null) {
        if (key.ownerStageId().equals(transition.stageId())) {
          used++;
        }
        continue;
      }
      if (!matchesDefect(parsed, key)) {
        continue;
      }
      if (trusted && matchedEpoch(parsed, key) != key.correctionEpoch()) {
        continue;
      }
      used++;
    }
    int memberMinimum = key.findingKeys().stream()
        .mapToInt(member -> repairsUsed(transitions, member, origin)).min().orElse(0);
    return Math.max(used, memberMinimum);
  }

  private int automaticReopensUsed(
      List<RunTransition> transitions, RecoveryAttemptKey key, InputOrigin origin) {
    if (key == null || transitions == null) {
      return 0;
    }
    boolean trusted = InputOrigin.of(origin).isTrusted();
    int used = 0;
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null) {
        continue;
      }
      if (reason.startsWith(ProductPipelineRunSupport.CAUSAL_REOPEN_REASON_PREFIX)
          && !reason.startsWith(AUTHOR_REOPEN_REASON_PREFIX)
          && !reason.startsWith(AUTOMATIC_REOPEN_REASON_PREFIX)) {
        used++;
        continue;
      }
      if (!reason.startsWith(AUTOMATIC_REOPEN_REASON_PREFIX)) {
        continue;
      }
      Parsed parsed = parse(reason);
      if (parsed == null || !matchesDefect(parsed, key)) {
        continue;
      }
      if (trusted && matchedEpoch(parsed, key) != key.correctionEpoch()) {
        continue;
      }
      used++;
    }
    int memberMinimum = key.findingKeys().stream()
        .mapToInt(member -> automaticReopensUsed(transitions, member, origin)).min().orElse(0);
    return Math.max(used, memberMinimum);
  }

  /** True when the absolute per-run recovery ceiling is spent. */
  public boolean perRunCeilingReached(List<RunTransition> transitions) {
    return ceilingReached(transitions);
  }

  private boolean ceilingReached(List<RunTransition> transitions) {
    if (transitions == null || limits.perRunCeiling() <= 0) {
      return true;
    }
    int spent = 0;
    for (RunTransition transition : dispatchedTransitions(transitions)) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null) {
        continue;
      }
      if (reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)
          || reason.startsWith(GRANT_TARGETED_REASON_PREFIX)
          || reason.startsWith(CLEAN_REBUILD_REASON_PREFIX)
          || reason.startsWith(TECHNICAL_RETRY_REASON_PREFIX)
          || isReopenReason(reason)) {
        spent++;
      }
    }
    return spent >= limits.perRunCeiling();
  }

  private int correctionEpoch(
      List<RunTransition> transitions,
      String ownerStageId,
      RecoveryCauseCode causeCode,
      String evidenceIdentity,
      String inputArtifactIdentity) {
    List<String> seen = observedArtifacts(transitions, ownerStageId, causeCode, evidenceIdentity);
    String current = artifactToken(inputArtifactIdentity);
    if (!current.isEmpty() && !seen.contains(current)) {
      seen.add(current);
    }
    if (seen.isEmpty()) {
      return 0;
    }
    int index = current.isEmpty() ? seen.size() - 1 : seen.indexOf(current);
    return Math.max(0, index);
  }

  private int technicalRetriesUsed(List<RunTransition> transitions, RecoveryAttemptKey key) {
    if (transitions == null || key == null) {
      return 0;
    }
    int used = 0;
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null || !reason.startsWith(TECHNICAL_RETRY_REASON_PREFIX)) {
        continue;
      }
      Parsed parsed = parse(reason);
      if (parsed != null && matchesDefect(parsed, key)) {
        used++;
      }
    }
    return used;
  }

  public int cleanRebuildsUsed(
      List<RunTransition> transitions, RecoveryAttemptKey key) {
    if (transitions == null) {
      return 0;
    }
    int used = 0;
    for (RunTransition transition : dispatchedTransitions(transitions)) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null || !reason.startsWith(CLEAN_REBUILD_REASON_PREFIX)) {
        continue;
      }
      Parsed parsed = parse(reason);
      if (parsed != null && matchesDefect(parsed, key)) {
        used++;
      }
    }
    int memberMinimum = key.findingKeys().stream()
        .mapToInt(member -> cleanRebuildsUsed(transitions, member)).min().orElse(0);
    return Math.max(used, memberMinimum);
  }

  private static List<RunTransition> dispatchedTransitions(List<RunTransition> transitions) {
    var unsent = transitions.stream().filter(java.util.Objects::nonNull)
        .map(RunTransition::reason).filter(java.util.Objects::nonNull)
        .filter(reason -> reason.startsWith(UNSENT_ATTEMPT_REASON_PREFIX))
        .map(reason -> reason.substring(UNSENT_ATTEMPT_REASON_PREFIX.length()))
        .collect(java.util.stream.Collectors.toSet());
    return transitions.stream().filter(transition -> transition == null
        || !unsent.contains(Long.toString(transition.toRevision()))).toList();
  }

  private List<String> observedArtifacts(
      List<RunTransition> transitions,
      String ownerStageId,
      RecoveryCauseCode causeCode,
      String evidenceIdentity) {
    List<String> seen = new ArrayList<>();
    if (transitions == null) {
      return seen;
    }
    String owner = ownerStageId == null ? "" : ownerStageId;
    RecoveryAttemptKey key = new RecoveryAttemptKey(owner, causeCode, evidenceIdentity, 0);
    for (RunTransition transition : transitions) {
      Parsed parsed = transition == null ? null : parse(transition.reason());
      if (parsed == null
          || parsed.artifactIdentity.isEmpty()
          || !matchesDefect(parsed, key)) {
        continue;
      }
      if (!seen.contains(parsed.artifactIdentity)) {
        seen.add(parsed.artifactIdentity);
      }
    }
    return seen;
  }

  private static boolean matchesDefect(Parsed parsed, RecoveryAttemptKey key) {
    var stored = new RecoveryAttemptKey(parsed.ownerStageId, parsed.causeCode, parsed.evidenceIdentity, parsed.correctionEpoch);
    return key.ownerStageId().equals(parsed.ownerStageId)
        && (sameDefect(stored, key) || parsed.findingKeys.stream().anyMatch(member -> sameDefect(member, key)));
  }

  private static int matchedEpoch(Parsed parsed, RecoveryAttemptKey key) {
    return parsed.findingKeys.stream().filter(member -> sameDefect(member, key))
        .mapToInt(RecoveryAttemptKey::correctionEpoch).findFirst().orElse(parsed.correctionEpoch);
  }

  private static boolean sameDefect(RecoveryAttemptKey left, RecoveryAttemptKey right) {
    if (isFindingKey(left) || isFindingKey(right)) {
      String leftHash = isFindingKey(left) ? left.evidenceIdentity().substring(FINDING_ID_PREFIX.length()) : left.evidenceIdentity();
      String rightHash = isFindingKey(right) ? right.evidenceIdentity().substring(FINDING_ID_PREFIX.length()) : right.evidenceIdentity();
      return leftHash.equals(rightHash);
    }
    return left.causeCode() == right.causeCode() && left.evidenceIdentity().equals(right.evidenceIdentity());
  }

  private static boolean isFindingKey(RecoveryAttemptKey key) {
    return key.causeCode() == RecoveryCauseCode.VALIDATION_BLOCKER && key.evidenceIdentity().startsWith(FINDING_ID_PREFIX);
  }

  private String payload(RecoveryAttemptKey key, String artifactIdentity) {
    String payload = key.ownerStageId()
        + FIELD
        + key.causeCode().name()
        + FIELD
        + key.evidenceIdentity()
        + FIELD
        + artifactToken(artifactIdentity)
        + FIELD
        + key.correctionEpoch();
    if (key.findingKeys().isEmpty()) {
      return payload;
    }
    StringBuilder members = new StringBuilder();
    for (RecoveryAttemptKey member : key.findingKeys()) {
      if (!members.isEmpty()) {
        members.append(FIELD);
      }
      members.append(member.causeCode().name()).append(FIELD).append(member.evidenceIdentity())
          .append(FIELD).append(member.correctionEpoch());
    }
    return payload + FIELD + members;
  }

  private static String artifactToken(String inputArtifactIdentity) {
    String raw = nvl(inputArtifactIdentity);
    return raw.isEmpty() ? "" : sha256Hex(raw);
  }

  private static Parsed parse(String reason) {
    if (reason == null) {
      return null;
    }
    boolean reopen = false;
    String rest;
    if (reason.startsWith(AUTHOR_REOPEN_REASON_PREFIX)) {
      reopen = true;
      rest = reason.substring(AUTHOR_REOPEN_REASON_PREFIX.length());
    } else if (reason.startsWith(AUTOMATIC_REOPEN_REASON_PREFIX)) {
      reopen = true;
      rest = reason.substring(AUTOMATIC_REOPEN_REASON_PREFIX.length());
    } else if (reason.startsWith(CORRECTION_REASON_PREFIX)) {
      rest = reason.substring(CORRECTION_REASON_PREFIX.length());
    } else if (reason.startsWith(CLEAN_REBUILD_REASON_PREFIX)) {
      rest = reason.substring(CLEAN_REBUILD_REASON_PREFIX.length());
    } else if (reason.startsWith(GRANT_TARGETED_REASON_PREFIX)) {
      rest = reason.substring(GRANT_TARGETED_REASON_PREFIX.length());
    } else if (reason.startsWith(GRANT_REASON_PREFIX)) {
      rest = reason.substring(GRANT_REASON_PREFIX.length());
    } else if (reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)) {
      rest = reason.substring(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX.length());
    } else if (reason.startsWith(TECHNICAL_RETRY_REASON_PREFIX)) {
      rest = reason.substring(TECHNICAL_RETRY_REASON_PREFIX.length());
    } else {
      return null;
    }
    if (rest.indexOf(FIELD) < 0) {
      return null;
    }
    String[] fields = split(rest, 6);
    if (fields.length < 4) {
      return null;
    }
    RecoveryCauseCode causeCode;
    try {
      causeCode = RecoveryCauseCode.valueOf(fields[1]);
    } catch (IllegalArgumentException e) {
      return null;
    }
    int epoch = 0;
    if (fields.length >= 5 && !fields[4].isEmpty()) {
      try {
        epoch = Integer.parseInt(fields[4]);
      } catch (NumberFormatException ignored) {
        epoch = 0;
      }
    }
    return new Parsed(reopen, fields[0], causeCode, fields[2], fields[3], epoch,
        fields.length < 6 ? List.of() : parseFindingKeys(fields[0], fields[5]));
  }

  private static List<RecoveryAttemptKey> parseFindingKeys(String owner, String payload) {
    String[] fields = split(payload, Integer.MAX_VALUE);
    if (fields.length % 3 != 0) {
      throw new IllegalStateException("Invalid persisted recovery finding budgets.");
    }
    var keys = new TreeMap<String, RecoveryAttemptKey>();
    for (int i = 0; i < fields.length; i += 3) {
      try {
        RecoveryCauseCode causeCode = RecoveryCauseCode.valueOf(fields[i]);
        int epoch = Integer.parseInt(fields[i + 2]);
        if (fields[i + 1].isEmpty() || epoch < 0
            || keys.putIfAbsent(fields[i + 1], new RecoveryAttemptKey(owner, causeCode, fields[i + 1], epoch)) != null) {
          throw new IllegalArgumentException("Invalid finding budget identity or epoch.");
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("Invalid persisted recovery finding budgets.", e);
      }
    }
    return List.copyOf(keys.values());
  }

  private static String[] split(String rest, int limit) {
    List<String> fields = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < rest.length() && fields.size() < limit - 1; i++) {
      if (rest.charAt(i) == FIELD) {
        fields.add(rest.substring(start, i));
        start = i + 1;
      }
    }
    fields.add(rest.substring(start));
    return fields.toArray(String[]::new);
  }

  private static String approvedContentHash(StageSnapshot snapshot) {
    if (snapshot == null
        || snapshot.approvedArtifactId() == null
        || snapshot.approvedArtifactId().isBlank()) {
      return "";
    }
    String approvedId = snapshot.approvedArtifactId();
    if (snapshot.outputRefs() != null) {
      for (Reference ref : snapshot.outputRefs()) {
        if (ref != null && approvedId.equals(ref.artifactId()) && ref.contentHash() != null) {
          return ref.contentHash();
        }
      }
    }
    if (snapshot.candidateReferences() != null) {
      for (Reference ref : snapshot.candidateReferences()) {
        if (ref != null && approvedId.equals(ref.artifactId()) && ref.contentHash() != null) {
          return ref.contentHash();
        }
      }
    }
    if (snapshot.approvableReference() != null
        && approvedId.equals(snapshot.approvableReference().artifactId())
        && snapshot.approvableReference().contentHash() != null) {
      return snapshot.approvableReference().contentHash();
    }
    return approvedId;
  }

  private static String nvl(String value) {
    return value == null ? "" : value;
  }

  private static String sha256Hex(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for evidence identity", e);
    }
  }

  private record Parsed(
      boolean reopen,
      String ownerStageId,
      RecoveryCauseCode causeCode,
      String evidenceIdentity,
      String artifactIdentity,
      int correctionEpoch,
      List<RecoveryAttemptKey> findingKeys) {}
}

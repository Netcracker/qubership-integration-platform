package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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

  /**
   * Absolute per-run backstop. Same default as {@code
   * qip.ai.create.failure-narrative.max-calls-per-run}; a working conversation does not reach it.
   */
  public static final int DEFAULT_PER_RUN_CEILING = 12;

  /** Who sent the reopen command. The automatic budget counts only {@link #AUTOMATIC}. */
  public enum ReopenInitiator {
    AUTHOR,
    AUTOMATIC
  }

  /**
   * Per-key limits and the absolute per-run ceiling. Values come from {@code qip.ai.create.*};
   * {@link #defaults()} matches the constants already in the runtime.
   */
  public record Limits(int maxSemanticRepairs, int maxCausalReopens, int perRunCeiling) {

    public Limits {
      maxSemanticRepairs = Math.max(0, maxSemanticRepairs);
      maxCausalReopens = Math.max(0, maxCausalReopens);
      perRunCeiling = Math.max(0, perRunCeiling);
    }

    public static Limits defaults() {
      return new Limits(
          ProductPipelineStageExecutor.MAX_SEMANTIC_REPAIRS,
          ProductPipelineRunSupport.MAX_CAUSAL_REOPENS,
          DEFAULT_PER_RUN_CEILING);
    }
  }

  private static final char FIELD = '\u0000';

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
   * Identity of the structured findings, not of author text and not of a formatted exception. Does
   * not mask identifiers: two findings that name different properties are two defects.
   */
  public static String evidenceIdentity(RecoveryCause cause) {
    RecoveryCause typed = cause == null ? RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER) : cause;
    StringBuilder canonical = new StringBuilder();
    List<PlanValidationFinding> findings = new ArrayList<>(typed.findings());
    findings.sort(
        Comparator.comparing((PlanValidationFinding f) -> nvl(f.code()))
            .thenComparing(f -> nvl(f.message())));
    for (PlanValidationFinding finding : findings) {
      canonical.append(nvl(finding.code())).append(FIELD).append(nvl(finding.message())).append('\n');
    }
    canonical.append(FIELD).append(nvl(typed.requestedFact()));
    return sha256Hex(canonical.toString());
  }

  /**
   * Content hashes of approved artifacts the owner and its predecessors produced. Excludes user
   * input, so a rephrasing that leaves those artifacts identical does not change the identity.
   */
  public static String inputArtifactIdentity(ProductPipelineRunDocument doc, String ownerStageId) {
    if (doc == null || doc.run() == null || doc.run().stages() == null) {
      return "";
    }
    String owner = ownerStageId == null ? "" : ownerStageId;
    StringBuilder identity = new StringBuilder();
    for (StageSnapshot snapshot : doc.run().stages()) {
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
    return new RecoveryAttemptKey(ownerStageId, typed.causeCode(), evidenceId, epoch);
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
    return repairsUsed(transitions, key, origin) < limits.maxSemanticRepairs();
  }

  public String recordRepair(RecoveryAttemptKey key, String inputArtifactIdentity) {
    if (key == null) {
      return ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX;
    }
    return ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX
        + payload(key, nvl(inputArtifactIdentity));
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
    if (ownerAlreadyReopened(transitions, key, legacyFailureSignature)) {
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
          && key.ownerStageId().equals(parsed.ownerStageId)
          && key.causeCode() == parsed.causeCode
          && key.evidenceIdentity().equals(parsed.evidenceIdentity)) {
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
        Math.max(0, limits.maxSemanticRepairs() - repairsUsed(transitions, key, origin)),
        Math.max(0, limits.maxCausalReopens() - automaticReopensUsed(transitions, key, origin)));
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
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null
          || !reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)) {
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
      if (trusted && parsed.correctionEpoch != key.correctionEpoch()) {
        continue;
      }
      used++;
    }
    return used;
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
      if (trusted && parsed.correctionEpoch != key.correctionEpoch()) {
        continue;
      }
      used++;
    }
    return used;
  }

  private boolean ceilingReached(List<RunTransition> transitions) {
    if (transitions == null || limits.perRunCeiling() <= 0) {
      return true;
    }
    int spent = 0;
    for (RunTransition transition : transitions) {
      String reason = transition == null ? null : transition.reason();
      if (reason == null) {
        continue;
      }
      if (reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)
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
    for (RunTransition transition : transitions) {
      Parsed parsed = transition == null ? null : parse(transition.reason());
      if (parsed == null
          || parsed.artifactIdentity.isEmpty()
          || !owner.equals(parsed.ownerStageId)
          || causeCode != parsed.causeCode
          || !evidenceIdentity.equals(parsed.evidenceIdentity)) {
        continue;
      }
      if (!seen.contains(parsed.artifactIdentity)) {
        seen.add(parsed.artifactIdentity);
      }
    }
    return seen;
  }

  private static boolean matchesDefect(Parsed parsed, RecoveryAttemptKey key) {
    return key.ownerStageId().equals(parsed.ownerStageId)
        && key.causeCode() == parsed.causeCode
        && key.evidenceIdentity().equals(parsed.evidenceIdentity);
  }

  private String payload(RecoveryAttemptKey key, String artifactIdentity) {
    return key.ownerStageId()
        + FIELD
        + key.causeCode().name()
        + FIELD
        + key.evidenceIdentity()
        + FIELD
        + artifactToken(artifactIdentity)
        + FIELD
        + key.correctionEpoch();
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
    } else if (reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)) {
      rest = reason.substring(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX.length());
    } else {
      return null;
    }
    if (rest.indexOf(FIELD) < 0) {
      return null;
    }
    String[] fields = split(rest, 5);
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
    return new Parsed(reopen, fields[0], causeCode, fields[2], fields[3], epoch);
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
      int correctionEpoch) {}
}

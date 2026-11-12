package org.qubership.integration.platform.ai.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore.CaptureFactProtection;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore.RequirementCaptureAttempt;

/**
 * The only reader of stored requirement-capture protection.
 *
 * <p>A rejected attempt that records a recovery strategy does not make its facts or values
 * repair authority. An older attempt that omits the strategy still exposes the protection
 * snapshot written for that attempt.
 */
public final class LegacyCaptureProtectionAdapter {

  private LegacyCaptureProtectionAdapter() {}

  public static Optional<CaptureFactProtection> latest(CompilationArtifacts artifacts, String compilationId) {
    return latestRevision(artifacts, compilationId).map(revision -> payload(artifacts, revision));
  }

  public static Optional<Revision> latestRevision(CompilationArtifacts artifacts, String compilationId) {
    return artifacts.latest(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION);
  }

  public static CaptureFactProtection payload(CompilationArtifacts artifacts, Revision revision) {
    return artifacts.payload(revision, CaptureFactProtection.class);
  }

  /**
   * Protection that may constrain a later candidate. Empty when the latest matching capture attempt
   * is accepted or already carries a recovery strategy.
   */
  public static Optional<CaptureFactProtection> authority(CompilationArtifacts artifacts, String compilationId,
      CaptureFactProtection scope) {
    var latest = latestMatchingAttempt(artifacts, compilationId, scope);
    if (latest != null && (latest.accepted() || latest.recoveryStrategy() != null)) {
      return Optional.empty();
    }
    return latest(artifacts, compilationId).filter(protection -> matches(protection, scope));
  }

  /**
   * Protection written before {@code attemptRevision}. Empty when that attempt carries a recovery
   * strategy.
   */
  public static Optional<Revision> protectionBefore(CompilationArtifacts artifacts, Revision attemptRevision,
      RequirementCaptureAttempt attempt) {
    if (attempt == null || attempt.recoveryStrategy() != null || attemptRevision == null) {
      return Optional.empty();
    }
    return artifacts.history(attemptRevision.compilationId(), Kind.REQUIREMENT_CAPTURE_PROTECTION).stream()
        .filter(revision -> revision.sequence() < attemptRevision.sequence())
        .filter(revision -> {
          var protection = payload(artifacts, revision);
          return Objects.equals(protection.sourceGeneration(), attempt.sourceGeneration())
              && Objects.equals(protection.sourceTurnId(), attempt.sourceTurnId());
        })
        .reduce((first, last) -> last);
  }

  private static RequirementCaptureAttempt latestMatchingAttempt(CompilationArtifacts artifacts,
      String compilationId, CaptureFactProtection scope) {
    var history = artifacts.history(compilationId, Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    for (int index = history.size() - 1; index >= 0; index--) {
      var attempt = artifacts.payload(history.get(index), RequirementCaptureAttempt.class);
      if (!List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())) {
        continue;
      }
      if (new CaptureFactProtection(attempt.sourceGeneration(), attempt.sourceTurnId(), attempt.authorText(),
          List.of()).sameSource(scope)) {
        return attempt;
      }
    }
    return null;
  }

  private static boolean matches(CaptureFactProtection protection, CaptureFactProtection scope) {
    return protection.sameSource(scope) && Objects.equals(protection.baseIdentity(), scope.baseIdentity());
  }
}

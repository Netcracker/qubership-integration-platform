package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;

/**
 * Decides whether a requirement candidate may replace the current projection.
 *
 * <p>A rejected initial projection may change structural representation. Text and polarity of an
 * accepted revision stay fixed unless an amendment or a server repair path proves the change. A
 * focused envelope with no repair alternative inside the editable scope becomes a projection
 * rebuild and is not focused repair.
 */
public final class RequirementRepairPolicy {

  private RequirementRepairPolicy() {}

  /** Recovery step this decision is allowed to return to the model. */
  public enum Strategy {
    FOCUSED_REPAIR,
    PROJECTION_REBUILD,
    ASK_AUTHOR,
    INTERNAL_FAILURE
  }

  /**
   * Accepted revision, source-backed claims, and the paths a repair may change.
   *
   * @param acceptedRevision saved draft, or null while the initial projection is still rejected
   * @param semanticBaseline claims persisted from quoted source evidence, or null when none exist
   * @param authorPaths paths granted by a valid {@code AmendmentInput}
   * @param serverRepairPaths paths issued by the server for this repair
   * @param recoveryStrategy step the caller intended before the feasibility check
   */
  public record RequirementRepairContext(
      DraftInput acceptedRevision,
      RequirementSemanticBaseline semanticBaseline,
      Set<String> authorPaths,
      Set<String> serverRepairPaths,
      Strategy recoveryStrategy) {

    public RequirementRepairContext {
      authorPaths = authorPaths == null ? Set.of() : Set.copyOf(authorPaths);
      serverRepairPaths = serverRepairPaths == null ? Set.of() : Set.copyOf(serverRepairPaths);
    }
  }

  /** Feasible strategy and the findings that still block admission. */
  public record RequirementRepairDecision(Strategy strategy, List<RequirementFinding> findings) {

    public RequirementRepairDecision {
      findings = findings == null ? List.of() : List.copyOf(findings);
    }
  }

  public static RequirementRepairDecision evaluate(
      RequirementRepairContext context, DraftInput candidate, List<RequirementFinding> findings) {
    var kept = new ArrayList<RequirementFinding>();
    if (findings != null) {
      for (var finding : findings) {
        if (finding != null && !suppressed(context, candidate, finding)) {
          kept.add(finding);
        }
      }
    }
    return new RequirementRepairDecision(feasibleStrategy(context, kept), List.copyOf(kept));
  }

  private static boolean suppressed(
      RequirementRepairContext context, DraftInput candidate, RequirementFinding finding) {
    return representationOnly(context, candidate, finding) || rejectedProjectionLoss(context, finding);
  }

  /**
   * A baseline claim may change representation while its text, polarity, and policy conflict stay
   * put. That filter lives here so the draft store does not apply a second repair rule.
   */
  private static boolean representationOnly(
      RequirementRepairContext context, DraftInput candidate, RequirementFinding finding) {
    var baseline = context.semanticBaseline();
    if (baseline == null || !"REPAIR_FACT_LOSS".equals(finding.code())) {
      return false;
    }
    String factId = finding.entityId();
    var claim = baseline.claims().stream().filter(value -> Objects.equals(factId, value.sourceFactId()))
        .findFirst().orElse(null);
    var facts = candidate == null || candidate.facts() == null ? List.<FactInput>of() : candidate.facts();
    var fact = facts.stream().filter(value -> value != null && Objects.equals(factId, value.sourceFactId()))
        .findFirst().orElse(null);
    if (claim == null || fact == null
        || !Objects.equals(claim.text(), fact.text()) || claim.polarity() != fact.polarity()) {
      return false;
    }
    var accepted = context.acceptedRevision();
    var prior = accepted == null || accepted.facts() == null ? null : accepted.facts().stream()
        .filter(value -> Objects.equals(factId, value.sourceFactId())).findFirst().orElse(null);
    return prior == null || Objects.equals(prior.policyConflict(), fact.policyConflict());
  }

  /**
   * Structural loss on a rejected projection is not an obligation. Claim text and polarity stay
   * with the claim validator.
   */
  private static boolean rejectedProjectionLoss(RequirementRepairContext context, RequirementFinding finding) {
    if (context.acceptedRevision() != null) {
      return false;
    }
    if ("REPAIR_FACT_LOSS".equals(finding.code())) {
      return true;
    }
    return "AUTHOR_AMENDMENT_REQUIRED".equals(finding.code()) && structural(finding.path());
  }

  private static Strategy feasibleStrategy(RequirementRepairContext context, List<RequirementFinding> findings) {
    Strategy current = context.recoveryStrategy() == null
        ? Strategy.FOCUSED_REPAIR : context.recoveryStrategy();
    if (current != Strategy.FOCUSED_REPAIR || findings.isEmpty()) {
      return current;
    }
    return feasible(context, findings) ? Strategy.FOCUSED_REPAIR : Strategy.PROJECTION_REBUILD;
  }

  private static boolean feasible(RequirementRepairContext context, List<RequirementFinding> findings) {
    return findings.stream().anyMatch(finding -> finding.repairAlternatives().stream()
        .anyMatch(alternative -> inScope(context, alternative)));
  }

  private static boolean inScope(RequirementRepairContext context, String alternative) {
    if (alternative == null || alternative.isBlank()) {
      return false;
    }
    if (matches(context.authorPaths(), alternative) || matches(context.serverRepairPaths(), alternative)) {
      return true;
    }
    return context.acceptedRevision() == null && structural(alternative);
  }

  private static boolean matches(Set<String> paths, String alternative) {
    String candidate = canonical(alternative);
    if (candidate.isEmpty()) {
      return false;
    }
    return paths.stream().map(RequirementRepairPolicy::canonical)
        .anyMatch(path -> !path.isEmpty() && (path.equals(candidate) || candidate.startsWith(path + "/")));
  }

  private static boolean structural(String path) {
    String value = canonical(path);
    return value.contains("/elementIntent")
        || value.contains("/fieldMapping")
        || value.startsWith("/interactions")
        || value.startsWith("/transitions")
        || value.startsWith("/capabilities")
        || value.startsWith("/settings")
        || value.startsWith("/flow");
  }

  private static String canonical(String path) {
    if (path == null) {
      return "";
    }
    String value = path;
    if (value.startsWith("/draft")) {
      value = value.substring("/draft".length());
    } else if (value.startsWith("/changes")) {
      value = value.substring("/changes".length());
    }
    if (value.startsWith("/flow/interactions/") || value.startsWith("/flow/transitions/")) {
      value = value.substring("/flow".length());
    }
    return value;
  }
}

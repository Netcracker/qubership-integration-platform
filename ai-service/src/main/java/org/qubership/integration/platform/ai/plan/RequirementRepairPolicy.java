package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.RequiredLiteralBindings;

/**
 * Decides whether a requirement candidate may replace the current projection.
 *
 * <p>A rejected initial projection may change structural representation. Shape, reference, and
 * literal findings on that projection stay focused repair when their path is editable. A literal
 * obligation there is a short source span: the saved claim quote, or the claim text when that
 * claim has no quote. While no baseline exists, the obligation is the current candidate's
 * evidence quote when that quote is an exact substring of the named user turn, the fact has an
 * element intent, and the quote is a shorter span than the claim text. On an accepted
 * draft, kind, interaction IDs, mapping, element intent, placement, exact values, text, and
 * polarity stay fixed unless an amendment or a server repair path covers the change. A focused
 * envelope with no repair alternative inside that scope becomes a projection rebuild.
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
   * @param authorSource named user turns, used when no semantic baseline exists yet
   */
  public record RequirementRepairContext(
      DraftInput acceptedRevision,
      RequirementSemanticBaseline semanticBaseline,
      Set<String> authorPaths,
      Set<String> serverRepairPaths,
      Strategy recoveryStrategy,
      AuthorSourceEvidence.Packet authorSource) {

    public RequirementRepairContext(DraftInput acceptedRevision, RequirementSemanticBaseline semanticBaseline,
        Set<String> authorPaths, Set<String> serverRepairPaths, Strategy recoveryStrategy) {
      this(acceptedRevision, semanticBaseline, authorPaths, serverRepairPaths, recoveryStrategy, null);
    }

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
        if (finding != null && !suppressed(context, finding)) {
          kept.add(withEditableAlternative(context, finding));
        }
      }
    }
    if (context.acceptedRevision() == null) {
      var grounded = hasClaims(context.semanticBaseline())
          ? missingGroundedLiterals(context.semanticBaseline(), candidate)
          : missingCandidateQuoteLiterals(context.authorSource(), candidate);
      for (var finding : grounded) {
        if (kept.stream().noneMatch(existing -> sameFinding(existing, finding))) {
          kept.add(withEditableAlternative(context, finding));
        }
      }
    }
    return new RequirementRepairDecision(feasibleStrategy(context, kept), List.copyOf(kept));
  }

  private static boolean hasClaims(RequirementSemanticBaseline baseline) {
    return baseline != null && baseline.claims() != null && !baseline.claims().isEmpty();
  }

  /**
   * A rejected candidate does not create a literal obligation. The saved quote is the required
   * span, and the claim text is that span when the claim has no quote.
   */
  private static List<RequirementFinding> missingGroundedLiterals(
      RequirementSemanticBaseline baseline, DraftInput candidate) {
    if (baseline == null || baseline.claims() == null || candidate == null) {
      return List.of();
    }
    var findings = new ArrayList<RequirementFinding>();
    for (var claim : baseline.claims()) {
      if (claim == null) {
        continue;
      }
      var fact = fact(candidate, claim.sourceFactId());
      for (String literal : groundedLiterals(claim)) {
        if (RequiredLiteralBindings.covered(fact == null ? null : fact.elementIntent(), literal, mappingTexts(fact))) {
          continue;
        }
        findings.add(literalFinding(claim.sourceFactId(), literal));
      }
    }
    return findings;
  }

  /** The saved quote is the span. Claim text is the span only when the claim has no quote. */
  private static List<String> groundedLiterals(RequirementSemanticClaim claim) {
    if (claim == null) {
      return List.of();
    }
    String quote = claim.evidence() == null ? null : claim.evidence().quote();
    if (quote != null && !quote.isBlank()) {
      return List.of(quote);
    }
    if (claim.text() != null && !claim.text().isBlank()) {
      return List.of(claim.text());
    }
    return List.of();
  }

  /**
   * While no baseline exists, a candidate evidence quote is a literal obligation when it is an
   * exact substring of the named user turn, the fact has an element intent, and the quote is a
   * shorter span than the claim text.
   */
  private static List<RequirementFinding> missingCandidateQuoteLiterals(
      AuthorSourceEvidence.Packet source, DraftInput candidate) {
    if (source == null || source.inputs() == null || candidate == null || candidate.facts() == null) {
      return List.of();
    }
    var findings = new ArrayList<RequirementFinding>();
    for (var fact : candidate.facts()) {
      if (fact == null || fact.evidence() == null || fact.sourceFactId() == null || fact.elementIntent() == null) {
        continue;
      }
      String quote = fact.evidence().quote();
      if (quote == null || quote.equals(fact.text())
          || !exactUserTurnQuote(source, fact.evidence().sourceTurnId(), quote)
          || RequiredLiteralBindings.covered(fact.elementIntent(), quote, mappingTexts(fact))) {
        continue;
      }
      findings.add(literalFinding(fact.sourceFactId(), quote));
    }
    return findings;
  }

  private static RequirementFinding literalFinding(String sourceFactId, String literal) {
    return new RequirementFinding("UNBOUND_REQUIRED_LITERAL",
        "/draft/facts/" + sourceFactId + "/elementIntent", sourceFactId, List.of(), List.of(),
        List.of(), "Keep source-backed literal [\"" + literal
            + "\"] in the requested name, a property, the body, or a mapping.");
  }

  private static boolean exactUserTurnQuote(AuthorSourceEvidence.Packet source, String turnId, String quote) {
    if (turnId == null || turnId.isBlank() || quote == null || quote.isBlank()) {
      return false;
    }
    return source.inputs().stream().anyMatch(input -> turnId.equals(input.sourceTurnId())
        && input.text() != null && input.text().contains(quote));
  }

  private static FactInput fact(DraftInput candidate, String sourceFactId) {
    if (candidate.facts() == null || sourceFactId == null) {
      return null;
    }
    return candidate.facts().stream()
        .filter(fact -> fact != null && sourceFactId.equals(fact.sourceFactId()))
        .findFirst().orElse(null);
  }

  private static List<String> mappingTexts(FactInput fact) {
    if (fact == null || fact.fieldMapping() == null) {
      return List.of();
    }
    var mapping = fact.fieldMapping();
    var texts = new ArrayList<String>();
    if (mapping.sourcePath() != null) {
      texts.add(mapping.sourcePath());
    }
    if (mapping.targetPath() != null) {
      texts.add(mapping.targetPath());
    }
    if (mapping.expression() != null) {
      texts.add(mapping.expression());
    }
    return texts;
  }

  private static boolean sameFinding(RequirementFinding left, RequirementFinding right) {
    return Objects.equals(left.code(), right.code()) && Objects.equals(left.path(), right.path())
        && Objects.equals(left.entityId(), right.entityId()) && Objects.equals(left.message(), right.message());
  }

  private static boolean suppressed(RequirementRepairContext context, RequirementFinding finding) {
    return rejectedProjectionLoss(context, finding);
  }

  /** A model can repair a finding only by editing a path that is already in scope. */
  private static RequirementFinding withEditableAlternative(
      RequirementRepairContext context, RequirementFinding finding) {
    if (finding.repairAlternatives().stream().anyMatch(alternative -> inScope(context, alternative))
        || !inScope(context, finding.path())) {
      return finding;
    }
    var alternatives = new ArrayList<String>(finding.repairAlternatives());
    alternatives.add(finding.path());
    return new RequirementFinding(finding.code(), finding.path(), finding.entityId(), finding.relatedPaths(),
        finding.relatedEntityIds(), alternatives, finding.message());
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
    return context.acceptedRevision() == null && (structural(alternative) || shape(alternative));
  }

  private static boolean shape(String path) {
    return "/draft".equals(path) || "/changes".equals(path)
        || path.startsWith("/draft/") || path.startsWith("/changes/");
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

package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.AmendmentInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;

/** Checks that a new or changed claim cites an exact substring of the named user turn. */
public final class RequirementClaimEvidenceValidator {

  private RequirementClaimEvidenceValidator() {}

  public static List<Issue> validate(RequirementSemanticBaseline baseline, List<FactInput> facts,
      AuthorSourceEvidence.Packet source, List<AmendmentInput> amendments) {
    var issues = new ArrayList<Issue>();
    var present = facts == null ? List.<FactInput>of() : facts.stream().filter(Objects::nonNull).toList();
    for (var fact : present) {
      var prior = claim(baseline, fact.sourceFactId());
      if (prior == null) {
        requireEvidence(issues, fact, source);
      } else if (sameClaim(prior, fact)) {
        if (fact.evidence() != null && !validEvidence(fact.evidence(), source)) {
          issues.add(invalidQuote(fact.sourceFactId()));
        }
      } else if (!changeCovered(amendments, fact.sourceFactId(),
          !Objects.equals(prior.text(), fact.text()), prior.polarity() != fact.polarity(), source)) {
        issues.add(amendmentRequired(fact.sourceFactId()));
      }
    }
    if (baseline != null) {
      for (var claim : baseline.claims()) {
        boolean kept = present.stream().anyMatch(fact -> Objects.equals(claim.sourceFactId(), fact.sourceFactId()));
        if (!kept && !deletionCovered(amendments, claim.sourceFactId(), source)) {
          issues.add(amendmentRequired(claim.sourceFactId()));
        }
      }
    }
    return List.copyOf(issues);
  }

  public static RequirementSemanticBaseline reconcile(RequirementSemanticBaseline previous, List<FactInput> facts,
      AuthorSourceEvidence.Packet source, List<AmendmentInput> amendments) {
    var packet = source == null ? new AuthorSourceEvidence.Packet(null, List.of()) : source;
    var claims = new ArrayList<RequirementSemanticClaim>();
    if (facts != null) {
      for (var fact : facts) {
        if (fact == null) {
          continue;
        }
        var prior = claim(previous, fact.sourceFactId());
        claims.add(new RequirementSemanticClaim(fact.sourceFactId(), fact.text(), fact.polarity(),
            evidenceFor(prior, fact, amendments, source)));
      }
    }
    return new RequirementSemanticBaseline(packet.generation(), CaptureProposal.digest(packet), claims);
  }

  private static ClaimEvidenceInput evidenceFor(RequirementSemanticClaim prior, FactInput fact,
      List<AmendmentInput> amendments, AuthorSourceEvidence.Packet source) {
    if (prior != null && sameClaim(prior, fact)) {
      return fact.evidence() != null && validEvidence(fact.evidence(), source) ? fact.evidence() : prior.evidence();
    }
    if (prior != null) {
      var amendment = authority(amendments, fact.sourceFactId(), !Objects.equals(prior.text(), fact.text()),
          prior.polarity() != fact.polarity(), source);
      if (amendment != null) {
        return new ClaimEvidenceInput(amendment.sourceTurnId(), amendment.quote());
      }
    }
    return fact.evidence();
  }

  private static void requireEvidence(List<Issue> issues, FactInput fact, AuthorSourceEvidence.Packet source) {
    if (fact.evidence() == null || blank(fact.evidence().sourceTurnId()) || blank(fact.evidence().quote())) {
      issues.add(new Issue("SOURCE_EVIDENCE_REQUIRED", "/draft/facts/" + fact.sourceFactId(), fact.sourceFactId(),
          "Claim '" + fact.sourceFactId() + "' requires sourceTurnId and an exact quote from that user turn."));
    } else if (!validEvidence(fact.evidence(), source)) {
      issues.add(invalidQuote(fact.sourceFactId()));
    }
  }

  private static Issue invalidQuote(String factId) {
    return new Issue("SOURCE_EVIDENCE_REQUIRED", "/draft/facts/" + factId, factId,
        "The quote must be an exact substring of the named user turn.");
  }

  private static Issue amendmentRequired(String factId) {
    return new Issue("AUTHOR_AMENDMENT_REQUIRED", "/draft/facts/" + factId, factId,
        "Deleting or changing this claim requires a new source-linked amendment.");
  }

  private static boolean validEvidence(ClaimEvidenceInput evidence, AuthorSourceEvidence.Packet source) {
    if (evidence == null || blank(evidence.sourceTurnId()) || blank(evidence.quote())
        || source == null || source.inputs() == null) {
      return false;
    }
    return source.inputs().stream().anyMatch(input -> evidence.sourceTurnId().equals(input.sourceTurnId())
        && input.text() != null && input.text().contains(evidence.quote()));
  }

  private static boolean changeCovered(List<AmendmentInput> amendments, String factId, boolean textChanged,
      boolean polarityChanged, AuthorSourceEvidence.Packet source) {
    if (rooted(amendments, factId, source)) {
      return true;
    }
    return (!textChanged || covered(amendments, factId, "text", source))
        && (!polarityChanged || covered(amendments, factId, "polarity", source));
  }

  private static boolean deletionCovered(List<AmendmentInput> amendments, String factId,
      AuthorSourceEvidence.Packet source) {
    return rooted(amendments, factId, source);
  }

  private static boolean rooted(List<AmendmentInput> amendments, String factId, AuthorSourceEvidence.Packet source) {
    return valid(amendments, source).stream().anyMatch(amendment -> canonical(amendment.path()).equals("/facts/" + factId));
  }

  private static boolean covered(List<AmendmentInput> amendments, String factId, String field,
      AuthorSourceEvidence.Packet source) {
    return valid(amendments, source).stream().anyMatch(amendment -> {
      String path = canonical(amendment.path());
      String fieldPath = "/facts/" + factId + "/" + field;
      return path.equals(fieldPath) || path.startsWith(fieldPath + "/");
    });
  }

  private static AmendmentInput authority(List<AmendmentInput> amendments, String factId, boolean textChanged,
      boolean polarityChanged, AuthorSourceEvidence.Packet source) {
    var valid = valid(amendments, source);
    var root = valid.stream().filter(amendment -> canonical(amendment.path()).equals("/facts/" + factId)).findFirst();
    if (root.isPresent()) {
      return root.get();
    }
    if (textChanged) {
      var text = valid.stream().filter(amendment -> covered(List.of(amendment), factId, "text", source)).findFirst();
      if (text.isPresent()) {
        return text.get();
      }
    }
    if (polarityChanged) {
      return valid.stream().filter(amendment -> covered(List.of(amendment), factId, "polarity", source))
          .findFirst().orElse(null);
    }
    return null;
  }

  private static List<AmendmentInput> valid(List<AmendmentInput> amendments, AuthorSourceEvidence.Packet source) {
    if (amendments == null) {
      return List.of();
    }
    return amendments.stream().filter(amendment -> amendment != null
        && validEvidence(new ClaimEvidenceInput(amendment.sourceTurnId(), amendment.quote()), source)).toList();
  }

  private static RequirementSemanticClaim claim(RequirementSemanticBaseline baseline, String sourceFactId) {
    if (baseline == null || sourceFactId == null) {
      return null;
    }
    return baseline.claims().stream().filter(claim -> sourceFactId.equals(claim.sourceFactId())).findFirst().orElse(null);
  }

  private static boolean sameClaim(RequirementSemanticClaim claim, FactInput fact) {
    return Objects.equals(claim.text(), fact.text()) && claim.polarity() == fact.polarity();
  }

  private static String canonical(String path) {
    if (path == null) {
      return "";
    }
    if (path.startsWith("/draft")) {
      return path.substring("/draft".length());
    }
    if (path.startsWith("/changes")) {
      return path.substring("/changes".length());
    }
    return path;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

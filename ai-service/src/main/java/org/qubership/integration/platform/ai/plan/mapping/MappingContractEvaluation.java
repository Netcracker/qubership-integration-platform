package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/** Classified mapping intent plus the structured findings from the same evaluation. */
public record MappingContractEvaluation(
    Optional<MappingIntent> intent, List<MappingRuleFinding> findings) {

  public MappingContractEvaluation {
    intent = intent == null ? Optional.empty() : intent;
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  public static MappingContractEvaluation passThrough() {
    return new MappingContractEvaluation(Optional.empty(), List.of());
  }

  public boolean blocked() {
    for (MappingRuleFinding finding : findings) {
      if (finding != null && finding.blocker()) {
        return true;
      }
    }
    return false;
  }

  public List<MappingRuleFinding> blockerFindings() {
    List<MappingRuleFinding> blockers = new ArrayList<>();
    for (MappingRuleFinding finding : findings) {
      if (finding != null && finding.blocker()) {
        blockers.add(finding);
      }
    }
    return List.copyOf(blockers);
  }

  public String blockedMessage() {
    List<MappingRuleFinding> blockers = blockerFindings();
    if (blockers.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (MappingRuleFinding finding : blockers) {
      if (text.length() > 0) {
        text.append('\n');
      }
      text.append(finding.message());
    }
    return text.toString();
  }

  public static List<MappingRuleFinding> sorted(List<MappingRuleFinding> findings) {
    if (findings == null || findings.isEmpty()) {
      return List.of();
    }
    List<MappingRuleFinding> sorted = new ArrayList<>();
    for (MappingRuleFinding finding : findings) {
      if (finding != null) {
        sorted.add(finding);
      }
    }
    sorted.sort(
        Comparator.comparing(MappingRuleFinding::mappingIntentId)
            .thenComparing(MappingRuleFinding::targetPath)
            .thenComparing(finding -> finding.code().name()));
    return List.copyOf(sorted);
  }
}

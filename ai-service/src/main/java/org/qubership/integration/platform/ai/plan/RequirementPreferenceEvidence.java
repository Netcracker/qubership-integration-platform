package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;

/** Checks that new author preferences have evidence in the current author request. */
final class RequirementPreferenceEvidence {

  // ponytail: Conservative phrase evidence; use a source-anchored intent record when more languages are required.
  private static final Pattern IDS = Pattern.compile(
      "(?iu)(\\bIDS\\b|integration design specification|\\b\u0418\u0414\u0421\\b|"
          + "\u0438\u043d\u0442\u0435\u0433\u0440\u0430\u0446\u0438\u043e\u043d\u043d\\S*\\s+\u0441\u043f\u0435\u0446\u0438\u0444\u0438\u043a\u0430\u0446\\S*)");
  private static final Pattern SYSTEM_TYPE = Pattern.compile(
      "(?iu)(\\b(?:internal|external)\\s+(?:integration\\s+)?system\\b|"
          + "\\bsystem\\s+(?:is\\s+)?(?:internal|external)\\b|"
          + "\\bpreferredSystemType\\b|"
          + "(?:\u0432\u043d\u0443\u0442\u0440\u0435\u043d\u043d|\u0432\u043d\u0435\u0448\u043d)\\S*\\s+\u0441\u0438\u0441\u0442\u0435\u043c\\S*|"
          + "\u0441\u0438\u0441\u0442\u0435\u043c\\S*\\s+(?:\u0432\u043d\u0443\u0442\u0440\u0435\u043d\u043d|\u0432\u043d\u0435\u0448\u043d)\\S*)");

  private RequirementPreferenceEvidence() {}

  static List<Issue> validate(
      DraftInput candidate, RequirementDraft previous, String authorText, String root) {
    if (authorText == null) {
      return List.of();
    }
    DraftInput prior = previous == null ? null : previous.authoredDraft();
    List<Issue> issues = new ArrayList<>();
    Boolean ids = candidate.settings().idsRequested();
    if (ids != null
        && !Objects.equals(ids, prior == null ? null : prior.settings().idsRequested())
        && !IDS.matcher(authorText).find()) {
      issues.add(new Issue("REQUIREMENT_COVERAGE_GAP", root + "/settings/idsRequested", null,
          "Keep IDS preference null until the author explicitly chooses it."));
    }
    RequirementCaptureInput.SystemType systemType = candidate.settings().preferredSystemType();
    if (systemType != null
        && !Objects.equals(systemType,
            prior == null ? null : prior.settings().preferredSystemType())
        && !SYSTEM_TYPE.matcher(authorText).find()) {
      issues.add(new Issue("REQUIREMENT_COVERAGE_GAP",
          root + "/settings/preferredSystemType", null,
          "Keep system visibility null until the author explicitly chooses it."));
    }
    return List.copyOf(issues);
  }
}

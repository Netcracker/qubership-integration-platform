package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldMirrorRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;

/**
 * Mirrors {@code elementSkeleton.selectedPatternId} onto blank top-level {@code patternId} when the
 * nested value matches {@code GP-0[1-7]}.
 */
@ApplicationScoped
public class SelectedPatternCaptureFillRules implements CaptureFieldMirrorRule {

  private static final Pattern VALID_PATTERN_ID = Pattern.compile("^GP-0[1-7]$");

  @Override
  public boolean supports(Object capture) {
    return capture instanceof SelectedPatternCapture;
  }

  @Override
  public Object apply(Object capture) {
    SelectedPatternCapture selected = (SelectedPatternCapture) capture;
    String nested = nestedSelectedPatternId(selected);
    if (!hasText(selected.patternId()) && hasText(nested) && VALID_PATTERN_ID.matcher(nested).matches()) {
      return new SelectedPatternCapture(
          nested,
          selected.name(),
          selected.reason(),
          selected.summary(),
          selected.requiredCapabilities(),
          selected.elementSkeleton());
    }
    return capture;
  }

  @Override
  public List<CaptureFieldHint> hintsWhenStillBlank(Object capture) {
    SelectedPatternCapture selected = (SelectedPatternCapture) capture;
    String nested = nestedSelectedPatternId(selected);
    // Same predicate as apply — never hint copying an invalid nested id.
    if (!hasText(selected.patternId())
        && hasText(nested)
        && VALID_PATTERN_ID.matcher(nested).matches()) {
      return List.of(
          new CaptureFieldHint("patternId", "elementSkeleton.selectedPatternId", nested));
    }
    return List.of();
  }

  private static String nestedSelectedPatternId(SelectedPatternCapture capture) {
    ElementSkeleton skeleton = capture.elementSkeleton();
    return skeleton == null ? null : skeleton.selectedPatternId();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}

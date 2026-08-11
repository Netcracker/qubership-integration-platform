package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.SelectedPatternCapture;
import org.qubership.integration.platform.ai.plan.SelectedPatternCaptureFillRules;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;

class CaptureFieldFillPolicyTest {

  private CaptureFieldFillPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new CaptureFieldFillPolicy(List.of(new SelectedPatternCaptureFillRules()));
  }

  @Test
  void fillsBlankTopLevelPatternIdFromValidNestedSkeleton() {
    SelectedPatternCapture input = capture(null, "GP-01");

    SelectedPatternCapture filled = (SelectedPatternCapture) policy.apply(input);

    assertEquals("GP-01", filled.patternId());
    assertEquals("GP-01", filled.elementSkeleton().selectedPatternId());
  }

  @Test
  void leavesBothBlankUnchangedAndDoesNotInvent() {
    SelectedPatternCapture input = capture(null, null);

    Object result = policy.apply(input);

    assertSame(input, result);
    assertTrue(policy.hintsWhenStillBlank(input).isEmpty());
  }

  @Test
  void leavesNonBlankConflictUnchanged() {
    SelectedPatternCapture input = capture("GP-02", "GP-01");

    Object result = policy.apply(input);

    assertSame(input, result);
    assertEquals("GP-02", ((SelectedPatternCapture) result).patternId());
  }

  @Test
  void leavesInvalidNestedUnchangedAndEmitsNoHints() {
    SelectedPatternCapture input = capture(null, "GP-99");

    Object result = policy.apply(input);

    assertSame(input, result);
    assertTrue(policy.hintsWhenStillBlank(input).isEmpty());
  }

  @Test
  void leavesGarbageNestedUnchangedAndEmitsNoHints() {
    SelectedPatternCapture input = capture("", "not-a-pattern");

    Object result = policy.apply(input);

    assertSame(input, result);
    assertTrue(policy.hintsWhenStillBlank(input).isEmpty());
  }

  @Test
  void hintsWhenStillBlankEmitPreviewForValidNestedTwin() {
    // Call hints on a still-blank top (as after a path that did not fill) while nested remains
    // a valid GP-0[1-7] value — same predicate as apply.
    SelectedPatternCapture stillBlank = capture(null, "GP-01");

    List<CaptureFieldHint> hints = policy.hintsWhenStillBlank(stillBlank);

    assertEquals(1, hints.size());
    CaptureFieldHint hint = hints.get(0);
    assertEquals("patternId", hint.missingTopPath());
    assertEquals("elementSkeleton.selectedPatternId", hint.nestedSourcePath());
    assertEquals("GP-01", hint.nestedPreview());
  }

  private static SelectedPatternCapture capture(String patternId, String nestedSelectedPatternId) {
    ElementSkeleton skeleton =
        nestedSelectedPatternId == null
            ? null
            : new ElementSkeleton(
                1,
                nestedSelectedPatternId,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    return new SelectedPatternCapture(
        patternId, "Protected Request-Response", "reason", "summary", List.of(), skeleton);
  }
}

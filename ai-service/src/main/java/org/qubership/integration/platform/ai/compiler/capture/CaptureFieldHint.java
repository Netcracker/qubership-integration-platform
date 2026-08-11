package org.qubership.integration.platform.ai.compiler.capture;

/**
 * Actionable hint for a blank top-level capture field that already has a nested twin value.
 *
 * @param missingTopPath dotted path of the blank top-level field (for example {@code patternId})
 * @param nestedSourcePath dotted path of the nested source (for example {@code
 *     elementSkeleton.selectedPatternId})
 * @param nestedPreview short preview of the nested value to copy
 */
public record CaptureFieldHint(String missingTopPath, String nestedSourcePath, String nestedPreview) {}

package org.qubership.integration.platform.ai.plan;

/**
 * Read-only selector for a mapping query. The runtime answers from the stored requirement brief.
 */
public record MappingQuerySelector(
    String mappingIntentId,
    String sourceRef,
    String targetRef,
    String sourcePath,
    String targetPath,
    boolean unresolvedOnly,
    Coverage coverage) {

  public MappingQuerySelector {
    mappingIntentId = blankToNull(mappingIntentId);
    sourceRef = blankToNull(sourceRef);
    targetRef = blankToNull(targetRef);
    sourcePath = blankToNull(sourcePath);
    targetPath = blankToNull(targetPath);
    coverage = coverage == null ? Coverage.ANY : coverage;
  }

  public static MappingQuerySelector unresolvedTargets() {
    return new MappingQuerySelector(null, null, null, null, null, true, Coverage.ANY);
  }

  public enum Coverage {
    ANY,
    MAPPED,
    PASS_THROUGH
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}

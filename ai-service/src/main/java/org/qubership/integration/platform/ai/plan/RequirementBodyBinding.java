package org.qubership.integration.platform.ai.plan;

/** Native body authority, checked against a complete value in the retained author source. */
public record RequirementBodyBinding(
    Authority authority,
    String elementRef,
    String propertyKey,
    NativeBodyKind bodyKind,
    @NullableCaptureValue String sourceGeneration,
    @NullableCaptureValue String sourceTurnId,
    @NullableCaptureValue Integer startOffset,
    @NullableCaptureValue Integer endOffset,
    @NullableCaptureValue Encoding encoding) {
  public enum Authority { GENERATED_CANDIDATE, VERBATIM_SOURCE_BODY }
  public enum NativeBodyKind { GROOVY_SCRIPT }
  public enum Encoding { RAW, JSON_STRING }
}

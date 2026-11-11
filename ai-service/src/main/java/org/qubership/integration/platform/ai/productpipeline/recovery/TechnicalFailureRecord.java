package org.qubership.integration.platform.ai.productpipeline.recovery;

/** Structured dependency or infrastructure failure observed during recovery. */
public record TechnicalFailureRecord(
    boolean retryable,
    int attemptCount,
    String dependencyName,
    String operation,
    String timeout,
    String correlationId,
    String exceptionType,
    String exceptionMessage,
    String responseStatus,
    String sanitizedTarget) {}

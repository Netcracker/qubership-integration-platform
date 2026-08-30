package org.qubership.integration.platform.ai.integration.catalog.materialize;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record UploadedSpecImportOutcome(
    String s3Key,
    String systemId,
    String specificationGroupId,
    String specificationId,
    boolean reused) {
}

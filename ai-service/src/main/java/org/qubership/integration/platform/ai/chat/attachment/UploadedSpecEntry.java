package org.qubership.integration.platform.ai.chat.attachment;

public record UploadedSpecEntry(
    String s3Key,
    String originalFilename,
    SpecType specType,
    String title,
    String version,
    String operationsSummary) {}

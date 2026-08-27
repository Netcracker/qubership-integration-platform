package org.qubership.integration.platform.ai.plan;

import org.qubership.integration.platform.ai.chat.attachment.SpecType;

public record UploadedSpecCandidate(String s3Key, String title, SpecType specType) {}

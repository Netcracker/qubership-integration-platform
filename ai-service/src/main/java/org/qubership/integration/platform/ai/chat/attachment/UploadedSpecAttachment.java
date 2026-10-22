package org.qubership.integration.platform.ai.chat.attachment;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record UploadedSpecAttachment(String s3Key, String filename) {
}

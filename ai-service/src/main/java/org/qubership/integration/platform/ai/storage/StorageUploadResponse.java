package org.qubership.integration.platform.ai.storage;

/** JSON returned after a successful upload to object storage. */
public record StorageUploadResponse(String objectKey, long size, String contentType) {}

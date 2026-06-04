package org.qubership.integration.platform.ai.integration.apihub;

/** Full APIHUB source document bytes ready for catalog multipart import. */
public record ApiHubDocumentPayload(byte[] content, String fileName) {}

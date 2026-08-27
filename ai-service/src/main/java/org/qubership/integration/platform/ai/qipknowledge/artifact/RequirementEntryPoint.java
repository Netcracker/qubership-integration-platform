package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configured trigger that starts chain execution. Entry role comes from catalog capability
 * metadata, not from {@code RequirementFactKind}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementEntryPoint(
    String entryPointId,
    String sourceFactId,
    String capabilityKey,
    String topic,
    String httpMethod,
    String path,
    String operation) {

  public RequirementEntryPoint {
    entryPointId = blankToEmpty(entryPointId);
    sourceFactId = blankToEmpty(sourceFactId);
    capabilityKey = blankToEmpty(capabilityKey);
    topic = blankToEmpty(topic);
    httpMethod = blankToEmpty(httpMethod);
    path = blankToEmpty(path);
    operation = blankToEmpty(operation);
  }

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value.trim();
  }
}

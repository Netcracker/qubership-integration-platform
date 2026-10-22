package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Catalog identity recorded by Java. The model cannot supply this record. Protocol, method, and
 * path come from the resolved operation. Port content hashes are the schema bodies committed with
 * that binding.
 */
public record ResolvedWorkBinding(
    String catalogId,
    String version,
    String operationId,
    String protocol,
    String method,
    String path,
    List<String> contractReferences,
    List<String> exposedPorts,
    List<PortContentHash> portContentHashes) {

  public ResolvedWorkBinding {
    protocol = protocol == null ? "" : protocol;
    method = method == null ? "" : method;
    path = path == null ? "" : path;
    contractReferences = Lists.copy(contractReferences);
    exposedPorts = Lists.copy(exposedPorts);
    portContentHashes = Lists.copy(portContentHashes);
  }

  public ResolvedWorkBinding(
      String catalogId,
      String version,
      String operationId,
      String protocol,
      String method,
      String path,
      List<String> contractReferences,
      List<String> exposedPorts) {
    this(
        catalogId,
        version,
        operationId,
        protocol,
        method,
        path,
        contractReferences,
        exposedPorts,
        List.of());
  }

  public ResolvedWorkBinding(
      String catalogId,
      String version,
      String operationId,
      List<String> contractReferences,
      List<String> exposedPorts) {
    this(catalogId, version, operationId, "", "", "", contractReferences, exposedPorts, List.of());
  }

  /**
   * Returns a binding that stores the schema hashes. An empty list is not a schema hash, so it
   * leaves the current hashes in place.
   */
  public ResolvedWorkBinding withPortContentHashes(List<PortContentHash> hashes) {
    if (hashes == null || hashes.isEmpty()) {
      return this;
    }
    return new ResolvedWorkBinding(
        catalogId,
        version,
        operationId,
        protocol,
        method,
        path,
        contractReferences,
        exposedPorts,
        hashes);
  }

  /**
   * Older documents omit port content hashes. A missing property stays an empty list so schema
   * version 2 still loads.
   */
  @JsonCreator
  public static ResolvedWorkBinding fromJson(
      @JsonProperty("catalogId") String catalogId,
      @JsonProperty("version") String version,
      @JsonProperty("operationId") String operationId,
      @JsonProperty("protocol") String protocol,
      @JsonProperty("method") String method,
      @JsonProperty("path") String path,
      @JsonProperty("contractReferences") List<String> contractReferences,
      @JsonProperty("exposedPorts") List<String> exposedPorts,
      @JsonProperty(value = "portContentHashes", required = false) List<PortContentHash> portContentHashes) {
    return new ResolvedWorkBinding(
        catalogId,
        version,
        operationId,
        protocol,
        method,
        path,
        contractReferences,
        exposedPorts,
        portContentHashes);
  }

  /** SHA-256 of one port schema body, stored with the binding that exposed the port. */
  public record PortContentHash(String port, String contentHash) {

    public PortContentHash {
      port = port == null ? "" : port;
      contentHash = contentHash == null ? "" : contentHash;
    }
  }
}

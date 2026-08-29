package org.qubership.integration.platform.ai.catalog.binding;

import java.util.Locale;
import java.util.Objects;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

/** Projects catalog DTOs into a protocol-valid {@link ResolvedServiceCallBinding}. */
public final class CatalogOperationProjector {

  private CatalogOperationProjector() {}

  public static ResolvedServiceCallBinding project(
      String targetNodeId,
      String serviceCallId,
      CatalogRestClient.SystemDto system,
      String specificationGroupId,
      String specificationId,
      CatalogRestClient.OperationDto operation,
      ResolvedServiceCallBinding.Source source,
      String release,
      String evidenceRef,
      String packageId) {
    Objects.requireNonNull(system, "system");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(source, "source");

    String catalogProtocol = normalizeProtocol(system.protocol());
    boolean soapProjection = "soap".equals(catalogProtocol);
    String graphProtocol = soapProjection ? "http" : catalogProtocol;

    rejectUnsupportedProtocols(graphProtocol);

    String method = normalizeMethod(operation.method(), graphProtocol);
    String path = normalizePath(operation.path(), catalogProtocol, soapProjection);

    return new ResolvedServiceCallBinding(
        targetNodeId,
        serviceCallId,
        requireText(system.type(), "systemType"),
        requireText(system.id(), "systemId"),
        requireText(specificationGroupId, "specificationGroupId"),
        requireText(specificationId, "specificationId"),
        requireText(operation.id(), "operationId"),
        graphProtocol,
        method,
        path,
        operation.name() == null ? "" : operation.name().trim(),
        source,
        release,
        evidenceRef,
        packageId);
  }

  private static void rejectUnsupportedProtocols(String graphProtocol) {
    if ("grpc".equals(graphProtocol)) {
      throw new IllegalArgumentException(
          "grpc catalog binding is missing synchronousGrpcCall");
    }
    if ("graphql".equals(graphProtocol)) {
      throw new IllegalArgumentException(
          "graphql catalog binding is missing integrationGqlQuery");
    }
    if (!"http".equals(graphProtocol)
        && !"kafka".equals(graphProtocol)
        && !"amqp".equals(graphProtocol)) {
      throw new IllegalArgumentException(
          "Unsupported catalog binding protocol: " + graphProtocol);
    }
  }

  private static String normalizeProtocol(String protocol) {
    if (protocol == null || protocol.isBlank()) {
      throw new IllegalArgumentException("protocol is required");
    }
    return protocol.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeMethod(String method, String graphProtocol) {
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException(
          graphProtocol + " catalog binding is missing integrationOperationMethod");
    }
    return method.trim();
  }

  private static String normalizePath(
      String path, String catalogProtocol, boolean soapProjection) {
    if (soapProjection) {
      return path == null ? "" : path;
    }
    if ("http".equals(catalogProtocol)) {
      if (path == null) {
        throw new IllegalArgumentException(
            "http catalog binding is missing integrationOperationPath");
      }
      return path;
    }
    return path == null ? "" : path;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}

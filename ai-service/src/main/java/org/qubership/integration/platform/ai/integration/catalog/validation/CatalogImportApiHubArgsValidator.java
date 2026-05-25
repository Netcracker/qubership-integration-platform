package org.qubership.integration.platform.ai.integration.catalog.validation;

import org.qubership.integration.platform.ai.chat.chainplan.PlanServiceBindingRules;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;

import java.util.Optional;

/** Validates arguments for {@link CatalogSystemToolNames#IMPORT_APIHUB}. */
public final class CatalogImportApiHubArgsValidator {

  private CatalogImportApiHubArgsValidator() {}

  public static Optional<CatalogToolResult.ErrorSpec> validate(
      String packageId, String version, String operationId) {
    if (packageId == null || packageId.isBlank()) {
      return Optional.of(
          new CatalogToolResult.ErrorSpec(
              CatalogToolResult.CODE_INVALID_ARGUMENT, "packageId is required", null));
    }
    if (version == null || version.isBlank()) {
      return Optional.of(
          new CatalogToolResult.ErrorSpec(
              CatalogToolResult.CODE_INVALID_ARGUMENT, "version is required", null));
    }
    if (operationId == null || operationId.isBlank()) {
      return Optional.of(
          new CatalogToolResult.ErrorSpec(
              CatalogToolResult.CODE_INVALID_ARGUMENT, "operationId is required", null));
    }
    if (PlanServiceBindingRules.looksLikePlaceholderOperationId(operationId)) {
      return Optional.of(
          new CatalogToolResult.ErrorSpec(
              CatalogToolResult.CODE_INVALID_ARGUMENT,
              "operationId looks like a placeholder (" + operationId + ")",
              "Resolve operation binding in CREATE_CHAIN_PLAN ("
                  + CatalogSystemToolNames.OPS
                  + ")."));
    }
    return Optional.empty();
  }
}

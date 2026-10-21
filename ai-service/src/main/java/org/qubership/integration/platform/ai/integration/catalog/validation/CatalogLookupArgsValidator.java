package org.qubership.integration.platform.ai.integration.catalog.validation;

import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogIdPatterns;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.Locale;
import java.util.Optional;

/** Heuristics for catalog read-tool arguments (specificationId vs systemId confusion). */
public final class CatalogLookupArgsValidator {

  private CatalogLookupArgsValidator() {}

  private static Optional<CatalogToolResult.ErrorSpec> invalidArgument(String message, String hint) {
    return Optional.of(
        new CatalogToolResult.ErrorSpec(
            CatalogToolResult.CODE_INVALID_ARGUMENT, message, hint));
  }

  public static Optional<CatalogToolResult.ErrorSpec> validateSystemIdForSpecifications(
      String systemId) {
    return validateSystemIdForSpecifications(CatalogSystemToolNames.SPECS, systemId);
  }

  public static Optional<CatalogToolResult.ErrorSpec> validateSystemIdForSpecifications(
      String toolName, String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid == null) {
      return invalidArgument(
          "systemId is required",
          "Call "
              + CatalogSystemToolNames.SEARCH
              + " first and pass the catalog system \"id\" from the match.");
    }
    if (CatalogIdPatterns.isUuidLike(sid) || !looksLikeIdsBusinessSystemId(sid)) {
      return Optional.empty();
    }
    return invalidArgument(
        "systemId \""
            + sid
            + "\" is not a catalog UUID. Call "
            + CatalogSystemToolNames.SEARCH
            + " first, then pass the \"id\" field from the match (prefer type IMPLEMENTED)."
            + " Do not use the design document system label as systemId.",
        null);
  }

  public static Optional<CatalogToolResult.ErrorSpec> validateSystemIdForSpecifications(
      String systemId,
      ConversationCatalogCache cache,
      String conversationId) {
    Optional<CatalogToolResult.ErrorSpec> heuristic =
        validateSystemIdForSpecifications(CatalogSystemToolNames.SPECS, systemId);
    if (heuristic.isPresent()) {
      return heuristic;
    }
    String sid = CatalogStrings.blankToNull(systemId);
    String conv = CatalogStrings.blankToNull(conversationId);
    if (cache != null
        && conv != null
        && cache.hasRememberedSystems(conv)
        && cache.findSystem(conv, sid).isEmpty()) {
      return invalidArgument(
          "systemId \""
              + sid
              + "\" was not returned by "
              + CatalogSystemToolNames.SEARCH
              + " in this conversation.",
          "Call "
              + CatalogSystemToolNames.SEARCH
              + " and pass the \"id\" field from the match.");
    }
    return Optional.empty();
  }

  public static Optional<CatalogToolResult.ErrorSpec> validateSpecificationIdForOperations(
      String specificationId) {
    return validateSpecificationIdForOperations(
        CatalogSystemToolNames.OPS, specificationId, null, null);
  }

  public static Optional<CatalogToolResult.ErrorSpec> validateSpecificationIdForOperations(
      String toolName,
      String specificationId,
      ConversationCatalogCache cache,
      String conversationId) {
    String sid = CatalogStrings.blankToNull(specificationId);
    if (sid == null) {
      return invalidArgument(
          "specificationId is required",
          "Call "
              + CatalogSystemToolNames.SPECS
              + " and pass a specification \"id\" as specificationId.");
    }

    String conv = CatalogStrings.blankToNull(conversationId);
    if (cache != null && conv != null && cache.hasRememberedSpecifications(conv)) {
      if (cache.isKnownSpecificationId(conv, sid)) {
        return Optional.empty();
      }
      if (cache.isRememberedSpecificationGroupIdOnly(conv, sid)) {
        return invalidArgument(
            "specificationId \"" + sid + "\" is incomplete.",
            "Use the specification \"id\" from "
                + CatalogSystemToolNames.SPECS
                + " data[], not a shorter id from another field on the same item.");
      }
      return invalidArgument(
          "specificationId \""
              + sid
              + "\" was not returned by "
              + CatalogSystemToolNames.SPECS
              + " in this conversation.",
          "Call "
              + CatalogSystemToolNames.SPECS
              + " and pass an \"id\" from data[].");
    }

    if (looksLikeIdsBusinessSystemId(sid)) {
      return invalidArgument(
          "specificationId \""
              + sid
              + "\" looks like a design document system label, not a catalog specification id.",
          "Use "
              + CatalogSystemToolNames.SPECS
              + " after "
              + CatalogSystemToolNames.SEARCH
              + " and pass an \"id\" from data[].");
    }
    if (CatalogIdPatterns.isUuidLike(sid)) {
      return invalidArgument(
          "specificationId looks like a catalog systemId.",
          "Use the specification \"id\" from "
              + CatalogSystemToolNames.SPECS
              + ", not the id from "
              + CatalogSystemToolNames.SEARCH
              + ".");
    }
    if (looksLikeOperationName(sid)) {
      return invalidArgument(
          "specificationId \""
              + sid
              + "\" looks like an operation name, not a specification id.",
          "Call "
              + CatalogSystemToolNames.SPECS
              + ", then pass data[].id. Use searchFilter on "
              + CatalogSystemToolNames.OPS
              + " to find operations by name.");
    }
    return Optional.empty();
  }

  /**
   * Hint when specifications list is empty (for success envelope {@code message}).
   */
  public static Optional<String> emptySpecificationsHint(String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid == null) {
      return Optional.empty();
    }
    if (looksLikeIdsBusinessSystemId(sid)) {
      return Optional.of(
          "No specifications for systemId="
              + sid
              + ". This is not a catalog UUID — use the \"id\" from "
              + CatalogSystemToolNames.SEARCH
              + ", not the design document system label. Then "
              + CatalogSystemToolNames.OPS
              + " uses the specification \"id\" from "
              + CatalogSystemToolNames.SPECS
              + ".");
    }
    return Optional.of(
        "No specifications for systemId="
            + sid
            + ". Call "
            + CatalogSystemToolNames.SEARCH
            + " and use the returned system \"id\" for "
            + CatalogSystemToolNames.SPECS
            + "; for "
            + CatalogSystemToolNames.OPS
            + " use a specification \"id\" from that response.");
  }

  /**
   * Hint when operations list is empty (for success envelope {@code message}).
   */
  public static Optional<String> emptyOperationsHint(String specificationId) {
    if (specificationId == null || specificationId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        "No operations for specificationId="
            + specificationId
            + ". Confirm specificationId is the \"id\" from "
            + CatalogSystemToolNames.SPECS
            + "; use searchFilter to match operation name or method/path.");
  }

  /** Uppercase design-document system label — not a catalog UUID from searchCatalogSystems. */
  static boolean looksLikeIdsBusinessSystemId(String systemId) {
    if (systemId == null || systemId.isBlank()) {
      return false;
    }
    String trimmed = systemId.trim();
    if (trimmed.length() < 2 || trimmed.length() > 64) {
      return false;
    }
    if (!trimmed.equals(trimmed.toUpperCase(Locale.ROOT))) {
      return false;
    }
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') {
        return false;
      }
    }
    return true;
  }

  /** Short camelCase token without path/version segments — likely an operation name. */
  static boolean looksLikeOperationName(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      return false;
    }
    if (value.indexOf('/') >= 0 || value.indexOf('-') >= 0) {
      return false;
    }
    if (!Character.isJavaIdentifierStart(value.charAt(0))) {
      return false;
    }
    for (int i = 1; i < value.length(); i++) {
      if (!Character.isJavaIdentifierPart(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}

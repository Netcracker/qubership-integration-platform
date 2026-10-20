package org.qubership.integration.platform.ai.integration.apihub;

import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.List;
import java.util.Locale;

/** API Hub identifiers parsed from {@code RequirementBrief.inputs}. */
public record ApiHubRequirementRefs(
    String packageId,
    String version,
    String operationId,
    String documentId,
    String apiType,
    String packageName,
    String specificationName) {

  public static final String DEFAULT_API_TYPE = "rest";
  public static final String DEFAULT_SYSTEM_TYPE = "INTERNAL";
  public static final String DEFAULT_DOCUMENT_SLUG = "api";

  public static ApiHubRequirementRefs parse(List<String> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new IllegalArgumentException("Requirement brief has no API Hub refs in inputs");
    }
    String packageId = null;
    String version = null;
    String operationId = null;
    String documentId = null;
    String apiType = null;
    String packageName = null;
    String specificationName = null;

    for (String line : inputs) {
      if (line == null || line.isBlank()) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if (value.isEmpty()) {
        continue;
      }
      switch (key) {
        case "packageid" -> packageId = value;
        case "operationid" -> operationId = value;
        case "version" -> version = value;
        case "documentid" -> documentId = value;
        case "apitype" -> apiType = value;
        case "packagename" -> packageName = value;
        case "specificationname" -> specificationName = value;
        default -> { }
      }
    }

    return new ApiHubRequirementRefs(
        packageId, version, operationId, documentId, apiType, packageName, specificationName);
  }

  public boolean hasImportableRefs() {
    return CatalogStrings.blankToNull(packageId) != null
        && CatalogStrings.blankToNull(version) != null
        && (CatalogStrings.blankToNull(operationId) != null
            || CatalogStrings.blankToNull(documentId) != null);
  }

  public String resolvedApiType() {
    String resolved = CatalogStrings.blankToNull(apiType);
    return resolved != null ? resolved.toLowerCase(Locale.ROOT) : DEFAULT_API_TYPE;
  }

  public String catalogSystemName() {
    String fromPackageName = CatalogStrings.blankToNull(packageName);
    if (fromPackageName != null) {
      return fromPackageName;
    }
    return deriveReadableName(packageId);
  }

  public String specificationGroupName() {
    String fromSpecName = CatalogStrings.blankToNull(specificationName);
    if (fromSpecName != null) {
      return fromSpecName;
    }
    String fromPackageName = CatalogStrings.blankToNull(packageName);
    if (fromPackageName != null) {
      return fromPackageName;
    }
    String fromVersion = CatalogStrings.blankToNull(version);
    if (fromVersion != null) {
      return fromVersion;
    }
    return catalogSystemName();
  }

  public String documentSlug() {
    String resolved = CatalogStrings.blankToNull(documentId);
    return resolved != null ? resolved : DEFAULT_DOCUMENT_SLUG;
  }

  static String deriveReadableName(String raw) {
    if (CatalogStrings.blankToNull(raw) == null) {
      return "";
    }
    return raw.trim().replace('.', ' ');
  }
}

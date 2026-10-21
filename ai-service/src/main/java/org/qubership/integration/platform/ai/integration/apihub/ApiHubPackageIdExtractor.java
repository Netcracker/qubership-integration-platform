package org.qubership.integration.platform.ai.integration.apihub;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/**
 * Extracts an API Hub package id (for example {@code S.CustParty.Care.GeoSite}) from free text.
 */
public final class ApiHubPackageIdExtractor {

  private static final Pattern PACKAGE_ID =
      Pattern.compile("\\b(S\\.[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+)\\b");

  private ApiHubPackageIdExtractor() {}

  public static String extract(String text) {
    String source = CatalogStrings.blankToNull(text);
    if (source == null) {
      return null;
    }
    Matcher matcher = PACKAGE_ID.matcher(source);
    return matcher.find() ? matcher.group(1) : null;
  }
}

package org.qubership.integration.platform.ai.plan.workdocument.source;

import java.util.Locale;

/** Classifies an attachment name before any importer runs. */
public final class AttachmentRoles {

  private AttachmentRoles() {}

  public static boolean importsAsSpecification(String storageKey) {
    String extension = extension(storageKey);
    return extension.equals("json") || extension.equals("yaml") || extension.equals("yml");
  }

  public static boolean isMappingName(String name) {
    String extension = extension(name);
    return extension.equals("md") || extension.equals("txt");
  }

  public static boolean isUnsupportedName(String name) {
    return !importsAsSpecification(name) && !isMappingName(name);
  }

  public static String fileName(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      return "";
    }
    int slash = storageKey.lastIndexOf('/');
    return slash >= 0 ? storageKey.substring(slash + 1) : storageKey;
  }

  private static String extension(String name) {
    String fileName = fileName(name).toLowerCase(Locale.ROOT);
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dot + 1);
  }
}

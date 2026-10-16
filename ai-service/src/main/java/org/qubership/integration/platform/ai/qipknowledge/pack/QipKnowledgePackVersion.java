package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed version identifier for a CIP QIP knowledge pack directory. */
public record QipKnowledgePackVersion(String raw, String normalized) {

  private static final Pattern VERSION_DIR = Pattern.compile("cip_compiler_(v\\d+_\\d+_\\d+)", Pattern.CASE_INSENSITIVE);

  public static QipKnowledgePackVersion fromPath(Path packRoot) {
    if (packRoot == null) {
      throw new IllegalArgumentException("packRoot is required");
    }
    String dirName = packRoot.getFileName() != null ? packRoot.getFileName().toString() : packRoot.toString();
    Matcher matcher = VERSION_DIR.matcher(dirName);
    if (matcher.find()) {
      String versionToken = matcher.group(1);
      return new QipKnowledgePackVersion(versionToken, versionToken);
    }
    return new QipKnowledgePackVersion(dirName, dirName);
  }
}

package org.qubership.integration.platform.ai.qipknowledge.skill;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackFileKind;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedQipKnowledgeFile;

/** Parses QIP knowledge skill files into {@link SkillDescriptor} records. */
public class SkillParser {

  private static final Pattern HEADING = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

  private final CapabilityClassifier classifier = new CapabilityClassifier();

  public SkillDescriptor parse(ScannedQipKnowledgeFile file) {
    if (file.kind() != QipKnowledgePackFileKind.SKILL) {
      throw new IllegalArgumentException("Expected SKILL file, got " + file.kind());
    }
    String skillId = deriveSkillId(file.relativePath());
    return new SkillDescriptor(
        skillId,
        extractTitle(file.content()),
        file.relativePath(),
        classifier.classifyPhase(skillId),
        detectFileTransport(file.content()),
        firstNonBlankLine(file.content()));
  }

  private static String deriveSkillId(String relativePath) {
    String normalized = relativePath.replace('\\', '/');
    int skillsIdx = normalized.indexOf("skills/");
    if (skillsIdx >= 0) {
      String tail = normalized.substring(skillsIdx + "skills/".length());
      int slash = tail.indexOf('/');
      if (slash > 0) {
        return tail.substring(0, slash);
      }
    }
    String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
    if (fileName.endsWith(".md")) {
      return fileName.substring(0, fileName.length() - 3);
    }
    return fileName;
  }

  private static String extractTitle(String content) {
    Matcher matcher = HEADING.matcher(content);
    if (matcher.find()) {
      return matcher.group(1).trim();
    }
    return "Unknown skill";
  }

  static boolean detectFileTransport(String content) {
    // Match path/layout markers only. Do not match peer skill names such as
    // cip-folder-organizer — generators often name those skills as consumers.
    String lower = content.toLowerCase(Locale.ROOT);
    return lower.contains("design-input.yaml")
        || lower.contains("implementation-plan.yaml")
        || lower.contains("chain.yaml")
        || lower.contains("scripts/");
  }

  private static String firstNonBlankLine(String content) {
    for (String line : content.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
      }
    }
    return "";
  }
}

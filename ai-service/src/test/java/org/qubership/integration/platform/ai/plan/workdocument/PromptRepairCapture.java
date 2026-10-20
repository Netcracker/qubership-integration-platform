package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds one outline repair capture from the model request. Fields that the prompt and schema do
 * not name stay empty.
 */
public final class PromptRepairCapture {

  public enum Link {
    DECLARE,
    OMIT,
    UNLINKED
  }

  private PromptRepairCapture() {}

  public static String outline(String prompt, String schema, Link link) {
    return outline(prompt, schema, link, List.of());
  }

  public static String outline(String prompt, String schema, Link link, List<String> namedUses) {
    return outline(prompt, schema, link, namedUses, List.of());
  }

  public static String outline(
      String prompt,
      String schema,
      Link link,
      List<String> namedUses,
      List<String> existingRetainedIds) {
    String passage = firstToken(prompt, "passage ");
    List<String> requirements = new ArrayList<>();
    String producer = "";
    List<String> updates = new ArrayList<>();
    for (String line : lines(prompt)) {
      if (line.startsWith("allowed-update ")) {
        updates.add(line.substring("allowed-update ".length()).trim());
      }
      if (line.startsWith("allowed-create ") && line.contains("RETAINED_VALUE")) {
        producer = lastToken(line);
      }
      if (line.startsWith("requirement ")) {
        String id = tokenAfter(line, "requirement ");
        if (!id.isBlank() && !requirements.contains(id)) {
          requirements.add(id);
        }
      }
    }
    boolean missing = prompt != null && prompt.contains("MISSING_RETAINED");
    boolean reuse = existingRetainedIds != null && !existingRetainedIds.isEmpty();
    List<String> aliases = new ArrayList<>();
    String placeholder = "";
    if (!reuse && missing && link != Link.OMIT && !producer.isBlank() && !passage.isBlank()) {
      if (namedUses != null) {
        for (String use : namedUses) {
          if (use != null && !use.isBlank() && prompt.contains(use)) {
            aliases.add("keep-" + use);
          }
        }
      }
      if (aliases.isEmpty()) {
        aliases.add("keep-process");
      }
      placeholder = placeholders(aliases, namedUses, producer, passage);
    }
    StringBuilder transfers = new StringBuilder();
    Set<String> assigned = new LinkedHashSet<>();
    for (String id : updates) {
      if (schema == null || !schema.contains(id)) {
        continue;
      }
      String line = transferLine(prompt, id);
      String source = tokenAfter(line, " source ");
      int slash = source.indexOf('/');
      String sourceStep = slash < 0 ? "" : source.substring(0, slash);
      String sourcePort = slash < 0 ? "" : source.substring(slash + 1);
      String targetPort = tokenAfter(line, " target ");
      String outcome = tokenAfter(line, " outcome ");
      List<String> listed = tokensAfter(line, " requirements ");
      assigned.addAll(listed);
      String retained = "";
      if (missing && link == Link.DECLARE && "success".equals(sourcePort) && reuse) {
        retained = quoted(existingRetainedIds);
      } else if (missing && link == Link.DECLARE && "success".equals(sourcePort) && !aliases.isEmpty()) {
        retained = quoted(aliases);
      }
      if (transfers.length() > 0) {
        transfers.append(',');
      }
      transfers
          .append("{\"existingId\":\"")
          .append(id)
          .append("\",\"alias\":\"\",\"sourceStepId\":\"")
          .append(sourceStep)
          .append("\",\"sourcePort\":\"")
          .append(sourcePort)
          .append("\",\"targetPort\":\"")
          .append(targetPort)
          .append("\",\"outcome\":\"")
          .append(outcome)
          .append("\",\"requirementIds\":[")
          .append(quoted(listed))
          .append("],\"requiredRetainedIds\":[")
          .append(retained)
          .append("],\"decision\":\"\"}");
    }
    return """
        {"outcome":"PREPARED","transfers":[%s],"retainedPlaceholders":[%s],"coverage":[%s]}
        """
        .formatted(transfers, placeholder, coverage(requirements, assigned, passage));
  }

  private static String placeholders(
      List<String> aliases, List<String> namedUses, String producer, String passage) {
    StringBuilder body = new StringBuilder();
    for (String alias : aliases) {
      String use = "process id";
      if (alias.startsWith("keep-") && namedUses != null) {
        String candidate = alias.substring("keep-".length());
        if (namedUses.contains(candidate)) {
          use = candidate;
        }
      }
      if (body.length() > 0) {
        body.append(',');
      }
      body.append("{\"existingId\":\"\",\"alias\":\"")
          .append(alias)
          .append("\",\"producerStepId\":\"")
          .append(producer)
          .append("\",\"intendedUse\":\"")
          .append(use)
          .append("\",\"evidenceRefs\":[\"")
          .append(passage)
          .append("\"]}");
    }
    return body.toString();
  }

  private static String coverage(List<String> requirements, Set<String> assigned, String passage) {
    StringBuilder body = new StringBuilder();
    for (String id : requirements) {
      if (body.length() > 0) {
        body.append(',');
      }
      String disposition = assigned.contains(id) ? "ASSIGNED" : "NO_MAPPING";
      body.append("{\"requirementId\":\"")
          .append(id)
          .append("\",\"passageId\":\"")
          .append(passage)
          .append("\",\"disposition\":\"")
          .append(disposition)
          .append("\"}");
    }
    return body.toString();
  }

  private static String quoted(List<String> ids) {
    StringBuilder body = new StringBuilder();
    for (String id : ids) {
      if (id.isBlank()) {
        continue;
      }
      if (body.length() > 0) {
        body.append(',');
      }
      body.append('"').append(id).append('"');
    }
    return body.toString();
  }

  private static List<String> tokensAfter(String line, String label) {
    List<String> tokens = new ArrayList<>();
    if (line == null) {
      return tokens;
    }
    int at = line.indexOf(label);
    if (at < 0) {
      return tokens;
    }
    String rest = line.substring(at + label.length()).trim();
    int start = 0;
    for (int index = 0; index <= rest.length(); index++) {
      if (index == rest.length() || rest.charAt(index) == ' ') {
        if (index > start) {
          tokens.add(rest.substring(start, index));
        }
        start = index + 1;
      }
    }
    return tokens;
  }

  private static String transferLine(String prompt, String id) {
    String prefix = "transfer " + id + " ";
    for (String line : lines(prompt)) {
      if (line.startsWith(prefix) || line.equals("transfer " + id)) {
        return line;
      }
    }
    return "";
  }

  private static String firstToken(String prompt, String label) {
    for (String line : lines(prompt)) {
      if (line.startsWith(label)) {
        return tokenAfter(line, label);
      }
    }
    return "";
  }

  private static String tokenAfter(String line, String label) {
    if (line == null) {
      return "";
    }
    int at = line.indexOf(label);
    if (at < 0) {
      return "";
    }
    int start = at + label.length();
    int end = line.indexOf(' ', start);
    if (end < 0) {
      end = line.length();
    }
    return line.substring(start, end).trim();
  }

  private static String lastToken(String line) {
    int space = line.lastIndexOf(' ');
    if (space < 0 || space == line.length() - 1) {
      return "";
    }
    return line.substring(space + 1).trim();
  }

  private static List<String> lines(String prompt) {
    List<String> lines = new ArrayList<>();
    if (prompt == null || prompt.isBlank()) {
      return lines;
    }
    int start = 0;
    for (int index = 0; index <= prompt.length(); index++) {
      if (index == prompt.length() || prompt.charAt(index) == '\n') {
        if (index > start) {
          lines.add(prompt.substring(start, index).trim());
        }
        start = index + 1;
      }
    }
    return lines;
  }
}

package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Parses the first CIP IDS integration-flow section and its Mermaid {@code sequenceDiagram}.
 * Rejects unsupported diagram types. A second integration-flow heading is ignored.
 *
 * <p>Step kinds are inferred from participants and message text: HTTP entry onto a CIP/chain
 * participant becomes the trigger (not an outbound service-call); script/response messages become
 * {@code script}; only calls targeting an external non-CIP participant remain {@code
 * service-call}.
 */
public final class IdsDocumentParser {

  private static final Pattern FLOW_HEADING =
      Pattern.compile(
          "(?m)^###\\s+Integration flow for CIP Chain\\s*-\\s*(.+?)\\s*$");
  private static final Pattern MERMAID_BLOCK =
      Pattern.compile("(?s)```mermaid\\s*(.*?)```");
  private static final Pattern UNSUPPORTED_DIAGRAM =
      Pattern.compile(
          "(?i)\\b(flowchart|graph|statediagram|statediagram-v2|classDiagram|erDiagram|journey|"
              + "gantt|pie|gitGraph|mindmap|timeline|quadrantChart|xychart-beta|block-beta|"
              + "digraph|strict\\s+graph|strict\\s+digraph)\\b");
  private static final Pattern ASCII_FLOW =
      Pattern.compile("(?m)^\\s*[┌└│├─┬┴┼+|]{3,}|^\\s*-->\\s*$");
  private static final Pattern PARTICIPANT =
      Pattern.compile(
          "(?m)^\\s*participant\\s+(\\w+)(?:\\s+as\\s+(.+?))?\\s*$");
  private static final Pattern MESSAGE =
      Pattern.compile(
          "(?m)^\\s*(\\w+)\\s*(-->>|--x|->>|-->|->)\\s*(\\w+)\\s*:\\s*(.+?)\\s*$");
  private static final Pattern HTTP_IDENTITY =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/\\S+)");
  private static final Pattern SCRIPT_HINT =
      Pattern.compile("(?i)\\bscript\\b|\\bhello\\b|\\bgreeting\\b|\\breturn\\b");

  public NormalizedDesignFlow parseFirstFlow(String markdown) {
    Objects.requireNonNull(markdown, "markdown");
    // A document may hold several flows, so each heading opens a section that ends at the next
    // one. An author that repeats the heading — writing it once from the template and once from
    // the instruction that quotes it — therefore opens a section with nothing inside, and reading
    // that first empty one would reject a document whose diagram is right below. Take the first
    // section that actually carries a diagram.
    List<int[]> headings = new ArrayList<>();
    List<String> names = new ArrayList<>();
    Matcher heading = FLOW_HEADING.matcher(markdown);
    while (heading.find()) {
      headings.add(new int[] {heading.start(), heading.end()});
      names.add(heading.group(1).trim());
    }
    if (headings.isEmpty()) {
      throw new IllegalArgumentException(
          "IDS must contain an 'Integration flow for CIP Chain - <name>' section");
    }
    String chainName = null;
    Matcher mermaid = null;
    for (int i = 0; i < headings.size(); i++) {
      int start = headings.get(i)[0];
      int end = i + 1 < headings.size() ? headings.get(i + 1)[0] : markdown.length();
      Matcher candidate = MERMAID_BLOCK.matcher(markdown.substring(start, end));
      if (candidate.find()) {
        chainName = names.get(i);
        mermaid = candidate;
        break;
      }
    }
    if (mermaid == null) {
      throw new IllegalArgumentException("IDS flow must contain one Mermaid sequenceDiagram block");
    }
    String diagram = mermaid.group(1).trim();
    rejectUnsupported(diagram);
    if (!diagram.toLowerCase(Locale.ROOT).startsWith("sequencediagram")) {
      throw new IllegalArgumentException("IDS Mermaid diagram must be a sequenceDiagram");
    }
    if (!diagram.toLowerCase(Locale.ROOT).contains("autonumber")) {
      throw new IllegalArgumentException("IDS sequenceDiagram must include autonumber");
    }

    Map<String, NormalizedDesignFlow.Participant> participants = new LinkedHashMap<>();
    Matcher participantMatcher = PARTICIPANT.matcher(diagram);
    while (participantMatcher.find()) {
      String id = participantMatcher.group(1).trim();
      String display =
          participantMatcher.group(2) == null || participantMatcher.group(2).isBlank()
              ? id
              : participantMatcher.group(2).trim();
      String participantId = normalizeParticipantId(id);
      String systemType = isCipParticipant(participantId, display) ? "INTERNAL" : "EXTERNAL";
      participants.putIfAbsent(
          participantId,
          new NormalizedDesignFlow.Participant(
              participantId, display, systemType, List.of("ids:" + participantId)));
    }

    List<NormalizedDesignFlow.Step> steps = new ArrayList<>();
    Matcher messageMatcher = MESSAGE.matcher(diagram);
    int stepIndex = 1;
    String triggerParticipant = null;
    String triggerPath = null;
    String triggerOperation = null;
    boolean triggerCaptured = false;
    while (messageMatcher.find()) {
      String arrow = messageMatcher.group(2);
      String fromRaw = messageMatcher.group(1).trim();
      String toRaw = messageMatcher.group(3).trim();
      String description = messageMatcher.group(4).trim();
      String from = normalizeParticipantId(fromRaw);
      String to = normalizeParticipantId(toRaw);
      participants.putIfAbsent(
          from,
          new NormalizedDesignFlow.Participant(
              from,
              fromRaw,
              isCipParticipant(from, fromRaw) ? "INTERNAL" : "EXTERNAL",
              List.of("ids:" + from)));
      participants.putIfAbsent(
          to,
          new NormalizedDesignFlow.Participant(
              to,
              toRaw,
              isCipParticipant(to, toRaw) ? "INTERNAL" : "EXTERNAL",
              List.of("ids:" + to)));
      if (triggerParticipant == null) {
        triggerParticipant = from;
      }

      Matcher http = HTTP_IDENTITY.matcher(description);
      boolean httpShaped = http.find();
      String httpMethod = httpShaped ? http.group(1).toUpperCase(Locale.ROOT) : null;
      String httpPath = httpShaped ? stripTrailingPunctuation(http.group(2)) : null;
      boolean toCip = isCipParticipant(to, participants.get(to).displayName());
      boolean fromCip = isCipParticipant(from, participants.get(from).displayName());
      boolean returnArrow = arrow != null && arrow.contains("--");

      // Only inbound HTTP onto a CIP/chain participant is the chain trigger — never an
      // outbound service-call binding.
      if (!triggerCaptured && httpShaped && toCip) {
        triggerParticipant = from;
        triggerPath = httpPath;
        triggerOperation = httpMethod;
        triggerCaptured = true;
        continue;
      }

      if (returnArrow && (triggerCaptured || fromCip)) {
        String stepId = "step-" + stepIndex++;
        steps.add(
            new NormalizedDesignFlow.Step(
                stepId,
                "script",
                from,
                to,
                description,
                description,
                List.of("ids:" + stepId)));
        continue;
      }

      String kind = classifyKind(fromCip, toCip, description, httpShaped, returnArrow);
      String stepId = "step-" + stepIndex++;
      String operationQuery =
          "service-call".equals(kind) && httpShaped
              ? httpMethod + " " + httpPath
              : description;
      steps.add(
          new NormalizedDesignFlow.Step(
              stepId,
              kind,
              from,
              to,
              operationQuery,
              description,
              List.of("ids:" + stepId)));
    }
    if (steps.isEmpty()) {
      String stepId = "step-1";
      String cipId =
          participants.values().stream()
              .filter(p -> isCipParticipant(p.participantId(), p.displayName()))
              .map(NormalizedDesignFlow.Participant::participantId)
              .findFirst()
              .orElse(triggerParticipant);
      steps.add(
          new NormalizedDesignFlow.Step(
              stepId,
              "script",
              triggerParticipant,
              cipId,
              "Return response from script",
              "Return response from script",
              List.of("ids:" + stepId)));
    }
    if (triggerParticipant == null) {
      triggerParticipant = steps.getFirst().fromParticipantId();
    }

    NormalizedDesignFlow.Trigger trigger =
        new NormalizedDesignFlow.Trigger(
            "http",
            triggerParticipant,
            null,
            triggerPath,
            triggerOperation,
            List.of("ids:trigger"));

    return new NormalizedDesignFlow(
        "1",
        "flow-1",
        chainName,
        "",
        trigger,
        List.copyOf(participants.values()),
        steps,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static String classifyKind(
      boolean fromCip,
      boolean toCip,
      String description,
      boolean httpShaped,
      boolean returnArrow) {
    if (returnArrow || SCRIPT_HINT.matcher(description).find()) {
      return "script";
    }
    if (toCip) {
      return "script";
    }
    // Non-CIP target is an outbound integration call (service-call binding).
    return "service-call";
  }

  private static boolean isCipParticipant(String participantId, String displayName) {
    String id = participantId == null ? "" : participantId.toLowerCase(Locale.ROOT);
    String display = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
    return id.contains("cip")
        || id.contains("chain")
        || display.contains("cip")
        || display.contains("chain");
  }

  private static String stripTrailingPunctuation(String path) {
    if (path == null) {
      return null;
    }
    return path.replaceAll("[\"',.;]+$", "");
  }

  private static void rejectUnsupported(String diagram) {
    if (UNSUPPORTED_DIAGRAM.matcher(diagram).find()) {
      throw new IllegalArgumentException(
          "Unsupported Mermaid diagram type; only sequenceDiagram is allowed");
    }
    if (ASCII_FLOW.matcher(diagram).find()) {
      throw new IllegalArgumentException("ASCII flow diagrams are not allowed in IDS");
    }
  }

  /**
   * Diagram alias to flow participant id, for example {@code CIP} to {@code p-cip}.
   *
   * <p>Public because a reader of the flow meets the alias, not the id: anything quoting the
   * diagram has to normalize the same way to recognize the participant it names.
   */
  public static String normalizeParticipantId(String raw) {
    String trimmed = raw.trim();
    if (trimmed.startsWith("p_") || trimmed.startsWith("p-")) {
      return trimmed.replace('_', '-');
    }
    return "p-" + trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }
}

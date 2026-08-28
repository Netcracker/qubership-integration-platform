package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Encodes and parses the mapping-gap wait reason. The short question is shown on the card; readable
 * edge lines are stored after a delimiter so durable clarify can restore both fields.
 */
public final class MappingGapWait {

  /**
   * Durable delimiter between the short mapping-gap question and readable edge lines stored in the
   * wait reason. Parsed back into {@code missingEvidence} for the decision card; not shown as
   * prose above the card.
   */
  static final String EDGES_MARKER = "__MAPPING_EDGES__";

  /**
   * Short card question when mappings are missing. Edges and actions live on the decision card;
   * do not instruct the reader to type {@code PASS_THROUGH}.
   */
  static final String FALLBACK_QUESTION =
      "Some data mappings are still missing before design can continue. "
          + "Pass through the payload as-is, or describe the field mappings.";

  private MappingGapWait() {}

  public static String encode(String question, List<String> readableEdges) {
    String q = question == null || question.isBlank() ? FALLBACK_QUESTION : question.strip();
    List<String> edges =
        readableEdges == null
            ? List.of()
            : readableEdges.stream()
                .filter(edge -> edge != null && !edge.isBlank())
                .map(String::strip)
                .toList();
    if (edges.isEmpty()) {
      return q;
    }
    return q + "\n\n" + EDGES_MARKER + "\n" + String.join("\n", edges);
  }

  /** Short question plus readable edge lines recovered from an encoded wait reason. */
  public record View(String question, List<String> missingEdges) {
    public View {
      question = question == null ? "" : question.strip();
      missingEdges = missingEdges == null ? List.of() : List.copyOf(missingEdges);
    }
  }

  public static View parse(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return new View(FALLBACK_QUESTION, List.of());
    }
    String trimmed = prompt.strip();
    int marker = trimmed.indexOf(EDGES_MARKER);
    if (marker < 0) {
      return new View(trimmed, List.of());
    }
    String question = trimmed.substring(0, marker).strip();
    String edgesBlock = trimmed.substring(marker + EDGES_MARKER.length()).strip();
    List<String> edges =
        edgesBlock.isBlank()
            ? List.of()
            : edgesBlock
                .lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .map(line -> line.startsWith("- ") ? line.substring(2).strip() : line)
                .toList();
    return new View(question.isBlank() ? FALLBACK_QUESTION : question, edges);
  }

  public static String languageReference(RequirementBrief brief, String... referenceTexts) {
    StringBuilder sample = new StringBuilder();
    if (brief != null) {
      if (brief.summary() != null && !brief.summary().isBlank()) {
        sample.append(brief.summary().trim());
      } else if (brief.goal() != null && !brief.goal().isBlank()) {
        sample.append(brief.goal().trim());
      }
      if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
        if (!sample.isEmpty()) {
          sample.append('\n');
        }
        sample.append(brief.approvedDraftText().trim());
      }
    }
    if (referenceTexts != null) {
      for (String text : referenceTexts) {
        if (text != null && !text.isBlank()) {
          if (!sample.isEmpty()) {
            sample.append('\n');
          }
          sample.append(text.trim());
        }
      }
    }
    return sample.isEmpty() ? "Create an integration chain." : sample.toString();
  }
}

package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Process adapter around immutable {@code cip-design-generator}. Rejects invented operation
 * bindings and non-sequence Mermaid diagrams.
 */
public final class GeneratedIdsAuthoringAdapter {

  private static final Pattern OPERATION_BINDING =
      Pattern.compile(
          "(?i)\\b(operationId|packageId|specificationId|integrationOperationId)\\s*[:=]");
  private static final Pattern UNSUPPORTED_DIAGRAM =
      Pattern.compile(
          "(?i)```mermaid\\s*(?!sequenceDiagram)(flowchart|graph|stateDiagram|classDiagram|"
              + "erDiagram|digraph)");

  private final BiFunction<RequirementBrief, String, String> generator;

  public GeneratedIdsAuthoringAdapter(BiFunction<RequirementBrief, String, String> generator) {
    this.generator = Objects.requireNonNull(generator, "generator");
  }

  public String generate(RequirementBrief brief) {
    return generate(brief, null);
  }

  /**
   * @param repairNote what the previous attempt got wrong, or null on the first try
   */
  public String generate(RequirementBrief brief, String repairNote) {
    Objects.requireNonNull(brief, "brief");
    String markdown = generator.apply(brief, repairNote);
    if (markdown == null || markdown.isBlank()) {
      throw new IllegalArgumentException("cip-design-generator returned an empty IDS");
    }
    rejectInventedBindings(markdown);
    rejectUnsupportedDiagrams(markdown);
    return markdown;
  }

  private static void rejectInventedBindings(String markdown) {
    if (OPERATION_BINDING.matcher(markdown).find()) {
      throw new IllegalArgumentException(
          "generated IDS must not invent operationId/packageId/specification bindings");
    }
  }

  private static void rejectUnsupportedDiagrams(String markdown) {
    if (UNSUPPORTED_DIAGRAM.matcher(markdown).find()
        || markdown.toLowerCase(Locale.ROOT).contains("flowchart")
            && markdown.toLowerCase(Locale.ROOT).contains("```mermaid")) {
      // IdsDocumentParser performs the authoritative diagram check; this is a fast fail for
      // obviously non-sequence blocks emitted by the generator.
      if (!markdown.toLowerCase(Locale.ROOT).contains("sequencediagram")) {
        throw new IllegalArgumentException(
            "generated IDS must use Mermaid sequenceDiagram only");
      }
    }
  }
}

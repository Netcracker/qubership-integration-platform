package org.qubership.integration.platform.ai.chat.chainplan;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Extracts {@code ChainImplementationPlan} JSON from assistant markdown (fenced blocks). */
public final class ChainImplementationPlanCapture {

  private static final Parser MARKDOWN_PARSER = Parser.builder().build();
  private static final ObjectMapper PLAN_JSON_PROBE = new ObjectMapper();

  private ChainImplementationPlanCapture() {}

  /**
   * Returns the latest fenced block that looks like a chain plan. Walks fences from the end only
   * to skip trailing unrelated ``` blocks; parses at most one candidate (the latest plan-shaped).
   */
  public static Optional<String> findLatestPlanJson(String assistantText) {
    if (assistantText == null || assistantText.isBlank()) {
      return Optional.empty();
    }
    List<String> blocks = collectFencedCodeBlocks(assistantText);
    for (int i = blocks.size() - 1; i >= 0; i--) {
      String block = blocks.get(i);
      if (!looksLikeChainPlan(block)) {
        continue;
      }
      return isParsablePlanJson(block) ? Optional.of(block) : Optional.empty();
    }
    return Optional.empty();
  }

  /**
   * True when the fenced block is syntactically complete JSON (streaming may emit plan-shaped but
   * truncated blocks before the model finishes).
   */
  static boolean isParsablePlanJson(String json) {
    if (json == null || json.isBlank()) {
      return false;
    }
    try {
      JsonFactory jsonFactory = PLAN_JSON_PROBE.getFactory();
      try (JsonParser parser = jsonFactory.createParser(json.trim())) {
        parser.enable(JsonParser.Feature.ALLOW_COMMENTS);
        PLAN_JSON_PROBE.readTree(parser);
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static List<String> collectFencedCodeBlocks(String markdown) {
    Node document = MARKDOWN_PARSER.parse(markdown);
    FencedBlockCollector collector = new FencedBlockCollector();
    document.accept(collector);
    return collector.blocks;
  }

  static boolean looksLikeChainPlan(String json) {
    String trimmed = json.trim();
    if (!trimmed.startsWith("{") || !trimmed.contains("\"elements\"")) {
      return false;
    }
    return trimmed.contains("\"chain\"") || trimmed.contains("\"name\"");
  }

  private static final class FencedBlockCollector extends AbstractVisitor {

    private final List<String> blocks = new ArrayList<>();

    @Override
    public void visit(FencedCodeBlock fencedCodeBlock) {
      String literal = fencedCodeBlock.getLiteral();
      if (literal != null) {
        blocks.add(literal.trim());
      }
      visitChildren(fencedCodeBlock);
    }
  }
}

package org.qubership.integration.platform.ai.presentation;

import java.util.regex.Pattern;

/** Deterministic classifier for plan and catalog chain review questions. */
public final class QuestionIntentClassifier {

  private static final Pattern GRAPH =
      Pattern.compile(
          "(?isU)\\b(show\\s+(the\\s+)?graph|display\\s+graph|how\\s+does\\s+the\\s+graph\\s+look)\\b");

  private static final Pattern TREE =
      Pattern.compile("(?isU)\\b(show\\s+(the\\s+)?tree|display\\s+tree)\\b");

  private static final Pattern JSON =
      Pattern.compile("(?isU)\\b(show\\s+(the\\s+)?json|display\\s+json)\\b");

  private static final Pattern SCRIPT =
      Pattern.compile("(?isU)\\b(show\\s+(the\\s+)?script|display\\s+script|what\\s+script)\\b");

  private QuestionIntentClassifier() {}

  public static QuestionIntent classify(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) {
      return QuestionIntent.EXPLAIN;
    }
    String msg = userMessage.trim();
    if (GRAPH.matcher(msg).find()) {
      return QuestionIntent.GRAPH;
    }
    if (TREE.matcher(msg).find()) {
      return QuestionIntent.TREE;
    }
    if (JSON.matcher(msg).find()) {
      return QuestionIntent.JSON;
    }
    if (SCRIPT.matcher(msg).find()) {
      return QuestionIntent.SCRIPT;
    }
    return QuestionIntent.EXPLAIN;
  }
}

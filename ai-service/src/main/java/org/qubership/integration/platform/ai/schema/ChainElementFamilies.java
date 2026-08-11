package org.qubership.integration.platform.ai.schema;

import java.util.Set;

/** Semantic element families not expressible by schema metadata alone. */
public final class ChainElementFamilies {

  public static final Set<String> TRIGGERS = Set.of(
      "http-trigger",
      "chain-trigger-2",
      "async-api-trigger",
      "kafka-trigger-2",
      "quartz-scheduler",
      "rabbitmq-trigger-2");

  public static final Set<String> ROUTING = Set.of("condition", "choice", "if", "else", "when", "otherwise");
  public static final Set<String> ROUTING_MODERN = Set.of("condition", "if", "else");
  public static final Set<String> ROUTING_DEPRECATED = Set.of("choice", "when", "otherwise");
  public static final Set<String> ROUTING_BRANCH_CHILDREN = Set.of("if", "else", "when", "otherwise");

  public static final Set<String> TRY_CATCH_WRAPPER = Set.of("try-catch-finally-2");
  public static final Set<String> TRY_CATCH_SHELL = Set.of("try-2", "catch-2", "finally-2");
  public static final Set<String> TRY_CATCH = Set.of("try-catch-finally-2", "try-2", "catch-2", "finally-2");
  public static final Set<String> TRY_CATCH_DEPRECATED = Set.of("try", "catch", "finally");

  public static final Set<String> LOOP = Set.of("loop-2");
  public static final Set<String> PARALLEL = Set.of("split-2", "split-async-2", "main-split-element-2");
  public static final Set<String> CHAIN_CALL = Set.of("chain-call-2", "reuse", "reuse-reference");

  private ChainElementFamilies() {
  }

  public static boolean isTrigger(String type) {
    return contains(TRIGGERS, type);
  }

  public static boolean isTryCatchShell(String type) {
    return contains(TRY_CATCH_SHELL, type);
  }

  public static boolean isTryCatch(String type) {
    return contains(TRY_CATCH, type);
  }

  private static boolean contains(Set<String> types, String type) {
    return type != null && types.contains(type.trim());
  }
}

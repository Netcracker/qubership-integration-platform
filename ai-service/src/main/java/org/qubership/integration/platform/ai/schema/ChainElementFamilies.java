package org.qubership.integration.platform.ai.schema;

import java.util.HashSet;
import java.util.Set;

/** Semantic element families not expressible by schema metadata alone. */
public final class ChainElementFamilies {

  public enum BindingMode {
    DIRECT,
    CATALOG_REQUIRED,
    HTTP_TRIGGER_DUAL_MODE,
    UNSUPPORTED_IN_CREATE
  }

  public static final Set<String> TRIGGERS = Set.of(
      "http-trigger",
      "chain-trigger-2",
      "async-api-trigger",
      "jms-trigger",
      "kafka-trigger-2",
      "mcp-trigger",
      "pubsub-trigger",
      "quartz-scheduler",
      "rabbitmq-trigger-2",
      "sds-trigger",
      "sftp-trigger-2");

  public static final Set<String> SENDERS = Set.of(
      "graphql-sender",
      "http-sender",
      "jms-sender",
      "kafka-sender-2",
      "mail-sender",
      "pubsub-sender",
      "rabbitmq-sender-2",
      "scs-sender");

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
  public static final Set<String> PARALLEL_BRANCH_CHILDREN =
      Set.of("split-element-2", "async-split-element-2", "main-split-element-2");
  public static final Set<String> CHAIN_CALL = Set.of("chain-call-2", "reuse", "reuse-reference");

  private ChainElementFamilies() {
  }

  public static boolean isTrigger(String type) {
    return contains(TRIGGERS, type);
  }

  public static boolean isSender(String type) {
    return contains(SENDERS, type);
  }

  public static BindingMode bindingMode(String elementType) {
    if (elementType == null) {
      throw new IllegalArgumentException("null");
    }
    String type = elementType.trim();
    if ("http-trigger".equals(type)) {
      return BindingMode.HTTP_TRIGGER_DUAL_MODE;
    }
    if ("async-api-trigger".equals(type)) {
      return BindingMode.CATALOG_REQUIRED;
    }
    if (isSender(type) || isTrigger(type)) {
      return BindingMode.DIRECT;
    }
    throw new IllegalArgumentException(type);
  }

  public static Set<String> classifiedTriggerAndSenderTypes() {
    Set<String> classified = new HashSet<>(TRIGGERS);
    classified.addAll(SENDERS);
    return Set.copyOf(classified);
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

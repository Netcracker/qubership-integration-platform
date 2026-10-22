package org.qubership.integration.platform.ai.chain.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;

class DeploymentMessagesTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ConversationService conversations = new ConversationService();

  @Test
  void translatedTemplatesAreCachedAndNeverReceiveCatalogValues() {
    AtomicInteger detects = new AtomicInteger();
    AtomicInteger translates = new AtomicInteger();
    AtomicReference<String> submitted = new AtomicReference<>();
    DeploymentMessages messages = new DeploymentMessages(conversations,
        prompt -> { detects.incrementAndGet(); return "de"; },
        (locale, input) -> { translates.incrementAndGet(); submitted.set(input); return translated(input); },
        mapper, Duration.ofHours(1));
    conversations.getOrCreate("one");
    messages.prepare("one", prose("Bitte bereitstellen"));
    String name = "O'Reilly {1}";
    assertEquals("DE: Deploy chain " + name + " to domain default?",
        messages.text("one", "deployQuestion", name, "default"));
    messages.prepare("one", answer());
    conversations.getOrCreate("two");
    messages.prepare("two", prose("Bereitstellen"));

    assertEquals(2, detects.get());
    assertEquals(1, translates.get());
    assertFalse(submitted.get().contains(name));
    ChatEvent.Decision original = (ChatEvent.Decision) ChatEvent.sessionLoggingDecision("operation", "Level?");
    ChatEvent.Decision localized = messages.withLabels("one", original);
    assertEquals(original.id(), localized.id());
    assertEquals(original.artifactHash(), localized.artifactHash());
    assertEquals(original.actions(), localized.actions());
    assertEquals("DE: Info", localized.actionLabels().get(ChatEvent.SESSION_LOGGING_INFO_ACTION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-key", "missing-placeholder", "extra-placeholder", "markup", "invalid-json"})
  void invalidTranslationsFallBackWithoutBlockingDeploymentAndCanBeRetried(String defect) {
    AtomicInteger calls = new AtomicInteger();
    DeploymentMessages messages = new DeploymentMessages(conversations, prompt -> "de",
        (locale, input) -> {
          if (calls.incrementAndGet() == 1) return invalid(input, defect);
          return translated(input);
        }, mapper, Duration.ofHours(1));
    conversations.getOrCreate("one");
    messages.prepare("one", prose("Bereitstellen"));
    assertEquals("Deploy chain Orders to domain default?", messages.text("one", "deployQuestion", "Orders", "default"));
    assertTrue(messages.withLabels("one", (ChatEvent.Decision) ChatEvent.deployDecision("op", "Deploy?"))
        .actionLabels().isEmpty());
    messages.prepare("one", prose("Noch einmal bereitstellen"));
    assertEquals("DE: Deploy chain Orders to domain default?", messages.text("one", "deployQuestion", "Orders", "default"));
    assertEquals(2, calls.get());
  }

  @Test
  void typedAnswersRecoverLanguageFromProseAndSkipEnglishCommandMarkers() {
    var first = conversations.archive().beginInput("one", "Bitte bereitstellen", mapper.createObjectNode());
    conversations.archive().resolveInput("one", first, "Deploy this chain with catalog attachment");
    var second = conversations.archive().beginInput("one", "Redeploy chain", mapper.createObjectNode());
    conversations.archive().resolveInput("one", second, "Redeploy chain", mapper.valueToTree(Map.of("action", "redeploy-chain")));
    AtomicReference<String> detected = new AtomicReference<>();
    DeploymentMessages messages = new DeploymentMessages(conversations,
        prompt -> { detected.set(prompt); return "de"; }, (locale, input) -> translated(input),
        mapper, Duration.ofHours(1));
    messages.prepare("one", answer());
    assertEquals("Bitte bereitstellen", detected.get());
    conversations.clearMessages("one");
    messages.prepare("one", answer());
    assertEquals("", detected.get());
  }

  @Test
  void placeholderOrderMayChangeButInsertedValuesAreLiteral() {
    assertEquals(DeploymentMessages.placeholders("{0}, {1}, {0}"), DeploymentMessages.placeholders("{1}, {0}, {0}"));
    assertEquals("Couldn't create Kafka MaaS topic Orders in default.", DeploymentMessages.english("topicCreateFailed", "Orders", "default"));
    assertEquals("Deploy chain {1} to domain default?", DeploymentMessages.english("deployQuestion", "{1}", "default"));
  }

  private String translated(String input) {
    try {
      ObjectNode source = (ObjectNode) mapper.readTree(input);
      source.fieldNames().forEachRemaining(key -> source.put(key, "DE: " + source.path(key).asText()));
      return source.toString();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private String invalid(String input, String defect) {
    try {
      ObjectNode source = (ObjectNode) mapper.readTree(translated(input));
      switch (defect) {
        case "missing-key" -> source.remove("deployQuestion");
        case "missing-placeholder" -> source.put("deployQuestion", "Deploy {0}?");
        case "extra-placeholder" -> source.put("deployQuestion", "Deploy {0} to {1} using {2}?");
        case "markup" -> source.put("action.deploy-chain", "**Deploy**");
        case "invalid-json" -> { return "not JSON"; }
        default -> throw new IllegalArgumentException(defect);
      }
      return source.toString();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static ChatRequest prose(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
  }

  private static ChatRequest answer() {
    ChatRequest request = new ChatRequest();
    request.setDecision(new ChatDecisionCommand());
    return request;
  }
}

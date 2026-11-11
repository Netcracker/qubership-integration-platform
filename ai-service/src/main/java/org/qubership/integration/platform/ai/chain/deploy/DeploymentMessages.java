package org.qubership.integration.platform.ai.chain.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.agent.DeploymentTranslationAgent;
import org.qubership.integration.platform.ai.productpipeline.create.ResponseLocaleResolver;

/** Translates validated templates once per locale and substitutes catalog values afterward. */
@ApplicationScoped
public class DeploymentMessages {
  private static final Logger LOG = Logger.getLogger(DeploymentMessages.class);
  private static final Map<String, String> ENGLISH = loadEnglish();
  private record SessionText(String generation, Map<String, String> templates) {}

  private final ConversationService conversations;
  private final Function<String, String> localeResolver;
  private final BiFunction<String, String, String> translator;
  private final ObjectMapper mapper;
  private final Cache<String, SessionText> sessions;
  private final Cache<String, Map<String, String>> translations = Caffeine.newBuilder()
      .maximumSize(64).expireAfterAccess(Duration.ofHours(12)).build();

  @Inject
  public DeploymentMessages(ConversationService conversations, ResponseLocaleResolver localeResolver,
      DeploymentTranslationAgent translator, ObjectMapper mapper, AppConfig config) {
    this(conversations, localeResolver::resolve, translator::translate, mapper,
        config.conversation().idleTimeout());
  }

  DeploymentMessages(ConversationService conversations, Function<String, String> localeResolver,
      BiFunction<String, String, String> translator, ObjectMapper mapper, Duration idleTimeout) {
    this.conversations = conversations;
    this.localeResolver = localeResolver;
    this.translator = translator;
    this.mapper = mapper;
    sessions = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(idleTimeout).build();
  }

  public void prepare(String conversationId, ChatRequest request) {
    ConversationArchive.Document document = conversations.archive().read(conversationId);
    SessionText prior = sessions.getIfPresent(conversationId);
    if (request != null && request.getDecision() != null
        && prior != null && prior.generation().equals(document.generation())) {
      return;
    }
    String prompt = request != null && request.getDecision() == null ? request.getMessage() : null;
    if (prompt == null || prompt.isBlank()) {
      prompt = latestProse(document);
    }
    Map<String, String> templates = ENGLISH;
    try {
      String locale = localeResolver.apply(prompt);
      if (locale != null && !Locale.forLanguageTag(locale).getLanguage().equals("en")) {
        Map<String, String> translated = translations.get(locale, this::translate);
        if (translated != null) {
          templates = translated;
        }
      }
    } catch (RuntimeException error) {
      LOG.warnf(error, "Deployment locale resolution failed; using English");
    }
    sessions.put(conversationId, new SessionText(document.generation(), templates));
  }

  public String text(String conversationId, String key, Object... values) {
    SessionText session = sessions.getIfPresent(conversationId);
    return format(session == null ? ENGLISH : session.templates(), key, values);
  }

  public static String english(String key, Object... values) {
    return format(ENGLISH, key, values);
  }

  public ChatEvent.Decision withLabels(String conversationId, ChatEvent.Decision decision) {
    SessionText session = sessions.getIfPresent(conversationId);
    if (session == null || session.templates() == ENGLISH) {
      return decision;
    }
    Map<String, String> labels = new LinkedHashMap<>();
    for (String action : decision.actions()) {
      String label = session.templates().get("action." + action);
      if (label != null) {
        labels.put(action, label);
      }
    }
    return new ChatEvent.Decision(decision.id(), decision.kind(), decision.question(),
        decision.artifactType(), decision.artifactHash(), decision.revision(), decision.reason(),
        decision.missingEvidence(), decision.actions(), decision.recovery(), decision.specs(),
        decision.chainIds(), labels);
  }

  private Map<String, String> translate(String locale) {
    try {
      JsonNode result = mapper.readTree(translator.apply(locale, mapper.writeValueAsString(ENGLISH)));
      if (result == null || !result.isObject() || result.size() != ENGLISH.size()) {
        throw new IllegalArgumentException("Deployment translation must contain every template");
      }
      Map<String, String> translated = new LinkedHashMap<>();
      for (var entry : ENGLISH.entrySet()) {
        JsonNode value = result.get(entry.getKey());
        if (value == null || !value.isTextual() || value.asText().isBlank()
            || value.asText().length() > 2048
            || !placeholders(entry.getValue()).equals(placeholders(value.asText()))) {
          throw new IllegalArgumentException("Invalid deployment translation: " + entry.getKey());
        }
        String text = value.asText();
        if (text.contains("*") || text.contains("`") || text.contains("<") || text.contains(">")
            || text.contains("[") || text.contains("]")) {
          throw new IllegalArgumentException("Unexpected markup in deployment translation: " + entry.getKey());
        }
        if (entry.getKey().startsWith("action.") && (text.length() > 80 || text.contains("\n"))) {
          throw new IllegalArgumentException("Invalid deployment action label: " + entry.getKey());
        }
        translated.put(entry.getKey(), text);
      }
      return Map.copyOf(translated);
    } catch (IOException | RuntimeException error) {
      LOG.warnf(error, "Deployment translation failed locale=%s; using English", locale);
      return null;
    }
  }

  static Map<String, Integer> placeholders(String template) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (int index = 0; index < template.length(); index++) {
      char ch = template.charAt(index);
      if (ch == '}') {
        throw new IllegalArgumentException("Unmatched template placeholder");
      }
      if (ch != '{') continue;
      int end = template.indexOf('}', index + 1);
      if (end == -1 || end == index + 1) {
        throw new IllegalArgumentException("Unmatched template placeholder");
      }
      String key = template.substring(index + 1, end);
      if (!key.chars().allMatch(Character::isDigit)) {
        throw new IllegalArgumentException("Invalid template placeholder: " + key);
      }
      counts.merge(key, 1, Integer::sum);
      index = end;
    }
    return counts;
  }

  private static String format(Map<String, String> templates, String key, Object[] values) {
    String template = templates.get(key);
    if (template == null) throw new IllegalArgumentException("Unknown deployment message: " + key);
    return new MessageFormat(template.replace("'", "''"), Locale.ROOT).format(values);
  }

  private static String latestProse(ConversationArchive.Document document) {
    String prompt = "";
    for (var turn : document.activeTurns()) {
      JsonNode metadata = turn.metadata();
      if (turn.message().role() == ConversationMessage.Role.USER
          && turn.rawText() != null && !turn.rawText().isBlank()
          && (metadata == null || (!metadata.hasNonNull("decision")
              && !metadata.hasNonNull("resolvedDecision")))) {
        prompt = turn.rawText();
      }
    }
    return prompt;
  }

  private static Map<String, String> loadEnglish() {
    Properties properties = new Properties();
    try (var stream = DeploymentMessages.class.getResourceAsStream("/presentation/deployment-messages.properties")) {
      if (stream == null) throw new IllegalStateException("Deployment templates are missing");
      properties.load(stream);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot load deployment templates", error);
    }
    Map<String, String> templates = new LinkedHashMap<>();
    for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
      templates.put(key, properties.getProperty(key));
    }
    return Map.copyOf(templates);
  }
}

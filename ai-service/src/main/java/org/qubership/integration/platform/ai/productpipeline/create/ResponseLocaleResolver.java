package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.ResponseLocaleAgent;

/** Resolves and validates the response locale once, when a CREATE run is first bound. */
@ApplicationScoped
public class ResponseLocaleResolver {

  public static final String DEFAULT_LOCALE = "en";

  private static final Logger LOG = Logger.getLogger(ResponseLocaleResolver.class);

  private final Function<String, String> classifier;

  @Inject
  public ResponseLocaleResolver(ResponseLocaleAgent agent) {
    this.classifier = Objects.requireNonNull(agent, "agent")::detect;
  }

  ResponseLocaleResolver(Function<String, String> classifier) {
    this.classifier = Objects.requireNonNull(classifier, "classifier");
  }

  public String resolve(String firstPrompt) {
    if (firstPrompt == null || firstPrompt.isBlank()) {
      return DEFAULT_LOCALE;
    }
    try {
      return normalize(classifier.apply(firstPrompt));
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Response locale detection failed; using %s", DEFAULT_LOCALE);
      return DEFAULT_LOCALE;
    }
  }

  static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_LOCALE;
    }
    String candidate = raw.strip().lines().findFirst().orElse("").replace("`", "").strip();
    if (!candidate.matches("(?i)[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) {
      return DEFAULT_LOCALE;
    }
    Locale locale = Locale.forLanguageTag(candidate);
    return locale.getLanguage().isBlank() ? DEFAULT_LOCALE : locale.toLanguageTag();
  }
}

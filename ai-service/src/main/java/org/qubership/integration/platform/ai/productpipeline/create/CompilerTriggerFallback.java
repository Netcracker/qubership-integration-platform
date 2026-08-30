package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Deterministic fallback for trigger generator failures. Builds a basic HTTP trigger from the
 * approved requirement brief so downstream structure generation can continue.
 */
final class CompilerTriggerFallback {

  private static final Pattern HTTP_METHOD_PATH =
      Pattern.compile(
          "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(?:to\\s+)?(/?\\S+)",
          Pattern.CASE_INSENSITIVE);

  private CompilerTriggerFallback() {
    // Utility class.
  }

  static Optional<ConfiguredTriggerSet> fromBrief(RequirementBrief brief) {
    if (brief == null || brief.facts() == null) {
      return Optional.empty();
    }
    for (RequirementFact fact : brief.facts()) {
      if (fact == null
          || fact.polarity() != RequirementFactPolarity.POSITIVE
          || fact.kind() != RequirementFactKind.ENDPOINT) {
        continue;
      }
      Optional<HttpEndpoint> endpoint = parseEndpoint(fact.text());
      if (endpoint.isPresent()) {
        HttpEndpoint ep = endpoint.get();
        List<PlanProperty> properties = new ArrayList<>();
        properties.add(new PlanProperty("contextPath", ep.path()));
        properties.add(new PlanProperty("httpMethodRestrict", ep.method().toUpperCase(Locale.ROOT)));
        properties.add(new PlanProperty("externalRoute", "true"));
        properties.add(new PlanProperty("accessControlType", "RBAC"));
        properties.add(new PlanProperty("handleChainFailureAction", "default"));
        ConfiguredTrigger trigger =
            new ConfiguredTrigger(
                "http-entry",
                "http-trigger-hello",
                "http-trigger",
                ep.method().toUpperCase(Locale.ROOT) + " " + ep.path(),
                properties);
        return Optional.of(
            new ConfiguredTriggerSet(1, List.of(trigger), List.of(fact.sourceFactId()), List.of()));
      }
    }
    return Optional.empty();
  }

  private static Optional<HttpEndpoint> parseEndpoint(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = HTTP_METHOD_PATH.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }
    String method = matcher.group(1).toUpperCase(Locale.ROOT);
    String path = matcher.group(2);
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    return Optional.of(new HttpEndpoint(method, path));
  }

  private record HttpEndpoint(String method, String path) {}
}

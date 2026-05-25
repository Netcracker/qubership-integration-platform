package org.qubership.integration.platform.ai.llm.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.TranscriptBalancing;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Classifies intent by cosine similarity against curated route examples (separate embedding store
 * from RAG).
 */
@ApplicationScoped
public class EmbeddingScenarioRouter {

  private static final Logger LOG = Logger.getLogger(EmbeddingScenarioRouter.class);

  private static final String ROUTE_EXAMPLES_RESOURCE = "routing/route-examples.yaml";

  private static final String META_SCENARIO = "scenario";

  /** Wrapped phase tokens for substring matching in filters, e.g. {@code ;COLD;PLAN_DRAFT;}. */
  private static final String META_PHASE_TAGS = "phaseTags";

  @Inject AppConfig appConfig;

  @Inject EmbeddingModel embeddingModel;

  @Inject @RouteEmbedding EmbeddingStore<TextSegment> routeEmbeddingStore;

  void ingestOnStartup(@Observes StartupEvent event) {
    if (!appConfig.router().embedding().enabled()) {
      LOG.infof("Route embedding ingest skipped (qip.ai.router.embedding.enabled=false)");
      return;
    }
    try {
      int n = ingestRouteExamplesFromClasspath();
      LOG.infof("Route embedding ingest complete: segments=%d", n);
    } catch (Exception e) {
      LOG.error("Route embedding ingest failed — embedding router will fall back to LLM", e);
    }
  }

  /**
   * @param transcriptTail optional last lines of transcript for short-reply context (e.g. IDS
   *     checklist)
   */
  public Optional<RoutingMatch> classify(
      String userMessage, ConversationPhase phase, String transcriptTail) {
    if (!appConfig.router().embedding().enabled()) {
      return Optional.empty();
    }
    if (userMessage == null || userMessage.isBlank()) {
      return Optional.empty();
    }
    String queryText = buildQueryText(phase, userMessage.trim(), transcriptTail);
    Embedding queryEmbedding = embeddingModel.embed(queryText).content();
    var filter =
        MetadataFilterBuilder.metadataKey(META_PHASE_TAGS)
            .containsString(";" + phase.name() + ";")
            .or(MetadataFilterBuilder.metadataKey(META_PHASE_TAGS).containsString(";*;"));
    int maxFetch = Math.max(10, appConfig.router().embedding().maxResults() * 2);
    EmbeddingSearchRequest request =
        EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(maxFetch)
            .minScore(0.0)
            .filter(filter)
            .build();
    List<EmbeddingMatch<TextSegment>> matches = routeEmbeddingStore.search(request).matches();
    return decideFromMatches(
        matches,
        appConfig.router().embedding().minScore(),
        appConfig.router().embedding().minMargin());
  }

  static String buildQueryText(ConversationPhase phase, String userMessage, String transcriptTail) {
    StringBuilder sb = new StringBuilder();
    sb.append("phase=").append(phase.name()).append('\n');
    if (transcriptTail != null && !transcriptTail.isBlank()) {
      String tail = TranscriptBalancing.tailOnly(transcriptTail, 500);
      if (!tail.isEmpty()) {
        sb.append("context_tail=\n").append(tail).append('\n');
      }
    }
    sb.append("message=\n").append(userMessage);
    return sb.toString();
  }

  /** Package-private for unit tests: apply score and margin gates to store search results. */
  static Optional<RoutingMatch> decideFromMatches(
      List<EmbeddingMatch<TextSegment>> matches, double minScore, double minMargin) {
    if (matches == null || matches.isEmpty()) {
      return Optional.empty();
    }
    List<EmbeddingMatch<TextSegment>> sorted = new ArrayList<>(matches);
    sorted.sort(Comparator.comparing((EmbeddingMatch<TextSegment> m) -> m.score()).reversed());
    List<EmbeddingMatch<TextSegment>> above = new ArrayList<>();
    for (EmbeddingMatch<TextSegment> m : sorted) {
      if (m.score() != null && m.score() >= minScore) {
        above.add(m);
      }
    }
    if (above.isEmpty()) {
      return Optional.empty();
    }
    EmbeddingMatch<TextSegment> best = above.get(0);
    double s1 = best.score();
    double s2 = above.size() > 1 && above.get(1).score() != null ? above.get(1).score() : 0.0;
    double margin = s1 - s2;
    if (margin < minMargin) {
      return Optional.empty();
    }
    ScenarioType scenario = parseScenario(best.embedded());
    if (scenario == null) {
      return Optional.empty();
    }
    return Optional.of(new RoutingMatch(scenario, s1, margin));
  }

  private static ScenarioType parseScenario(TextSegment segment) {
    if (segment == null || segment.metadata() == null) {
      return null;
    }
    String raw = segment.metadata().getString(META_SCENARIO);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ScenarioType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private int ingestRouteExamplesFromClasspath() throws Exception {
    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    RouteExamplesRoot root;
    try (InputStream in =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(ROUTE_EXAMPLES_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource: " + ROUTE_EXAMPLES_RESOURCE);
      }
      root = yamlMapper.readValue(in, RouteExamplesRoot.class);
    }
    if (root.routes == null || root.routes.isEmpty()) {
      LOG.warnf("No routes in %s", ROUTE_EXAMPLES_RESOURCE);
      return 0;
    }
    int count = 0;
    for (RouteExampleRoute route : root.routes) {
      if (route.scenario == null || route.utterances == null) {
        continue;
      }
      ScenarioType scenarioType;
      try {
        scenarioType = ScenarioType.valueOf(route.scenario.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        LOG.warnf("Skipping unknown scenario in route examples: %s", route.scenario);
        continue;
      }
      String phaseTags = phaseTagsFromYaml(route.phases);
      for (String utterance : route.utterances) {
        if (utterance == null || utterance.isBlank()) {
          continue;
        }
        Metadata meta =
            Metadata.from(META_SCENARIO, scenarioType.name()).put(META_PHASE_TAGS, phaseTags);
        TextSegment segment = TextSegment.textSegment(utterance.trim(), meta);
        Embedding emb = embeddingModel.embed(segment.text()).content();
        routeEmbeddingStore.add(emb, segment);
        count++;
      }
    }
    return count;
  }

  static String phaseTagsFromYaml(List<String> phases) {
    if (phases == null || phases.isEmpty()) {
      return ";*;";
    }
    boolean allPhases = phases.stream().anyMatch(p -> p != null && "*".equals(p.trim()));
    if (allPhases) {
      return ";*;";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(';');
    for (String p : phases) {
      if (p == null || p.isBlank()) {
        continue;
      }
      sb.append(p.trim().toUpperCase(Locale.ROOT)).append(';');
    }
    return sb.length() > 1 ? sb.toString() : ";*;";
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class RouteExamplesRoot {
    public List<RouteExampleRoute> routes;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class RouteExampleRoute {
    public String scenario;
    public List<String> phases;
    public List<String> utterances;
  }
}

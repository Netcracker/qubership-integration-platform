package org.qubership.integration.platform.ai.llm.routing;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingScenarioRouterTest {

  @Test
  void buildQueryTextIncludesPhaseAndMessage() {
    String q =
        EmbeddingScenarioRouter.buildQueryText(ConversationPhase.PLAN_DRAFT, "hello", "tail");
    assertTrue(q.contains("phase=PLAN_DRAFT"));
    assertTrue(q.contains("message=\nhello"));
    assertTrue(q.contains("context_tail=\ntail"));
  }

  @Test
  void phaseTagsFromYamlStar() {
    assertEquals(";*;", EmbeddingScenarioRouter.phaseTagsFromYaml(List.of("*", "COLD")));
  }

  @Test
  void phaseTagsFromYamlList() {
    assertEquals(
        ";COLD;PLAN_DRAFT;",
        EmbeddingScenarioRouter.phaseTagsFromYaml(List.of("COLD", "PLAN_DRAFT")));
  }

  @Test
  void decideFromMatchesRequiresMargin() {
    TextSegment a = TextSegment.from("x", Metadata.from("scenario", "CREATE_CHAIN_PLAN"));
    TextSegment b = TextSegment.from("y", Metadata.from("scenario", "IMPLEMENT_CHAIN"));
    List<EmbeddingMatch<TextSegment>> matches =
        List.of(new EmbeddingMatch<>(0.80, "1", null, a), new EmbeddingMatch<>(0.78, "2", null, b));
    assertEquals(Optional.empty(), EmbeddingScenarioRouter.decideFromMatches(matches, 0.72, 0.04));
  }

  @Test
  void decideFromMatchesAcceptsClearWinner() {
    TextSegment a = TextSegment.from("x", Metadata.from("scenario", "CREATE_CHAIN_PLAN"));
    TextSegment b = TextSegment.from("y", Metadata.from("scenario", "IMPLEMENT_CHAIN"));
    List<EmbeddingMatch<TextSegment>> matches =
        List.of(new EmbeddingMatch<>(0.90, "1", null, a), new EmbeddingMatch<>(0.70, "2", null, b));
    Optional<RoutingMatch> out = EmbeddingScenarioRouter.decideFromMatches(matches, 0.72, 0.04);
    assertTrue(out.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, out.get().scenario());
    assertEquals(0.90, out.get().margin(), 0.001);
  }
}

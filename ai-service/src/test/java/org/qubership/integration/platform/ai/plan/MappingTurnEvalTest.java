package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.MappingTurnEvalRepairHarness;
import org.qubership.integration.platform.ai.llm.agent.MappingTurnAgent;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.Kind;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalCorpus.ConversationCase;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalScorer.CaseScore;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalScorer.RepairScore;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalScorer.Report;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Evaluation gate for the mapping conversation seam. Deterministic capture fixtures drive
 * {@link MappingTurnInterpreter} and {@link MappingTurnProcessor}. Compiler repair is scored
 * through {@code CaptureRepairRunner}, {@code ScriptBodyRepairTool}, and
 * {@code ProducerOwnedRecovery}.
 */
class MappingTurnEvalTest {

  private static final MappingTurnCapture NONE =
      new MappingTurnCapture(Kind.NONE, List.of(), List.of(), "", List.of());

  @Test
  void multilingualSeamMeetsEverySubsetThreshold() throws Exception {
    List<ConversationCase> corpus = MappingTurnEvalCorpus.conversationCases();
    Map<String, MappingTurnCapture> captures = MappingTurnEvalCorpus.captureIndex(corpus);
    MappingTurnInterpreter interpreter =
        new MappingTurnInterpreter(stubAgent(captures));
    List<CaseScore> scores = new ArrayList<>();
    for (ConversationCase conversationCase : corpus) {
      scores.add(MappingTurnEvalScorer.score(conversationCase, run(conversationCase, interpreter)));
    }
    List<RepairScore> repairs = MappingTurnEvalRepairHarness.run();
    Report report =
        MappingTurnEvalScorer.report(scores, repairs, liveModelSkipReason());
    String rendered = MappingTurnEvalScorer.render(report);
    Path reportFile = Path.of("target", "mapping-turn-eval-report.txt");
    Files.createDirectories(reportFile.getParent());
    Files.writeString(reportFile, rendered, StandardCharsets.UTF_8);
    System.err.println(rendered);
    assertTrue(report.failures().isEmpty(), rendered);
  }

  private static MappingTurnApplication run(
      ConversationCase conversationCase, MappingTurnAdapter adapter) {
    RequirementBrief brief = conversationCase.seed();
    MappingTurnApplication application = MappingTurnApplication.rejected(brief);
    for (MappingTurnEvalCorpus.Turn turn : conversationCase.turns()) {
      application = MappingTurnProcessor.process(brief, turn.message(), adapter);
      brief = application.brief();
    }
    return application;
  }

  private static MappingTurnAgent stubAgent(Map<String, MappingTurnCapture> captures) {
    return (flow, intents, message) -> captures.getOrDefault(message, NONE);
  }

  private static String liveModelSkipReason() {
    return "SKIPPED: Quarkus %test OpenAI base-url is http://localhost:0/v1/ and this module has"
        + " no model-backed MappingTurnAgent test. Semantic scoring uses MappingTurnInterpreter"
        + " at the agent boundary with pinned captures; live-model eval needs a reachable chat"
        + " model outside %test.";
  }
}

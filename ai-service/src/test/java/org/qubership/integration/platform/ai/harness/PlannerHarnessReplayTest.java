package org.qubership.integration.platform.ai.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerReportParser;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignProcessSkillRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.PlannerRequest;

class PlannerHarnessReplayTest {

  @Test
  @EnabledIfSystemProperty(named = "planner.replay.request", matches = ".+")
  void replaysRecordedModelResponsesWithoutCallingTheModel() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    PlannerHarnessRequest request =
        mapper.readValue(
            Path.of(System.getProperty("planner.replay.request")).toFile(),
            PlannerHarnessRequest.class);
    PlannerHarnessResponse recording =
        mapper.readValue(
            Path.of(System.getProperty("planner.replay.response")).toFile(),
            PlannerHarnessResponse.class);
    RecordedRunner runner = new RecordedRunner(recording.attempts());
    CipDesignPlannerAdapter adapter =
        new CipDesignPlannerAdapter(runner, new CipDesignPlannerReportParser());

    SkillHarnessStatus status;
    String message;
    try {
      DesignPlanReport report =
          adapter.plan(
              new PlannerRequest(
                  request.conversationId(),
                  request.input(),
                  recording.skillHash(),
                  request.repairEvidenceText()));
      status = SkillHarnessStatus.COMPLETED;
      message = report.markdown();
    } catch (RuntimeException e) {
      status = SkillHarnessStatus.FAILED;
      message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    assertEquals(
        SkillHarnessStatus.valueOf(System.getProperty("planner.replay.expectedStatus")), status);
    String outputMessage = message;
    requiredPatterns()
        .forEach(
            pattern -> assertTrue(pattern.matcher(outputMessage).find(), pattern.pattern()));
    forbiddenPatterns()
        .forEach(
            pattern -> assertFalse(pattern.matcher(outputMessage).find(), pattern.pattern()));
  }

  private static List<Pattern> requiredPatterns() {
    return patterns("planner.replay.requiredPatterns");
  }

  private static List<Pattern> forbiddenPatterns() {
    return patterns("planner.replay.forbiddenPatterns");
  }

  private static List<Pattern> patterns(String property) {
    String value = System.getProperty(property, "");
    if (value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split("\\R"))
        .filter(pattern -> !pattern.isBlank())
        .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
        .toList();
  }

  private static final class RecordedRunner implements DesignProcessSkillRunner {

    private final Deque<PlannerHarnessResponse.Attempt> attempts;

    private RecordedRunner(List<PlannerHarnessResponse.Attempt> attempts) {
      this.attempts = new ArrayDeque<>(attempts);
    }

    @Override
    public String runOnce(
        String conversationId,
        String skillId,
        String input,
        Optional<String> formatFailure,
        Optional<String> repairEvidence,
        String pinnedSkillHash) {
      if (attempts.isEmpty()) {
        throw new IllegalStateException("Recording has no response for the next planner attempt");
      }
      PlannerHarnessResponse.Attempt attempt = attempts.removeFirst();
      if (attempt.error() != null) {
        throw new IllegalStateException(attempt.error());
      }
      return attempt.response();
    }
  }
}

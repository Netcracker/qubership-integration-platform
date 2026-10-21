package org.qubership.integration.platform.ai.harness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.CipDesignPlannerReportParser;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignProcessSkillRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.PlannerRequest;

/** Runs the design planner without the surrounding CREATE conversation or catalog writes. */
@ApplicationScoped
public class PlannerHarnessService {

  private final CompilerSkillDocumentService documentService;
  private final DesignProcessSkillRunner runner;
  private final String modelName;

  @Inject
  public PlannerHarnessService(
      CompilerSkillDocumentService documentService,
      DesignProcessSkillRunner runner,
      @ConfigProperty(name = "qip.ai.llm.model-name")
          String modelName) {
    this.documentService = documentService;
    this.runner = runner;
    this.modelName = modelName;
  }

  public PlannerHarnessResponse run(PlannerHarnessRequest request) {
    String conversationId = resolveConversationId(request.conversationId());
    RecordingRunner recordingRunner = new RecordingRunner(runner);
    String skillHash = null;
    try {
      CompilerSkillDocument document =
          documentService.loadByCapabilityId(CipDesignPlannerAdapter.SKILL_ID);
      skillHash = sha256(document.markdown());
      CipDesignPlannerAdapter adapter =
          new CipDesignPlannerAdapter(recordingRunner, new CipDesignPlannerReportParser());
      DesignPlanReport report =
          adapter.plan(
              new PlannerRequest(
                  conversationId, request.input(), skillHash, request.repairEvidenceText()));
      return new PlannerHarnessResponse(
          conversationId,
          SkillHarnessStatus.COMPLETED,
          report.markdown(),
          skillHash,
          modelName,
          recordingRunner.attempts());
    } catch (Exception e) {
      return new PlannerHarnessResponse(
          conversationId,
          SkillHarnessStatus.FAILED,
          failureMessage(e),
          skillHash,
          modelName,
          recordingRunner.attempts());
    }
  }

  private static String resolveConversationId(String conversationId) {
    return conversationId == null || conversationId.isBlank()
        ? UUID.randomUUID().toString()
        : conversationId.trim();
  }

  private static String failureMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static final class RecordingRunner implements DesignProcessSkillRunner {

    private final DesignProcessSkillRunner delegate;
    private final List<PlannerHarnessResponse.Attempt> attempts = new ArrayList<>();

    private RecordingRunner(DesignProcessSkillRunner delegate) {
      this.delegate = delegate;
    }

    @Override
    public String runOnce(
        String conversationId,
        String skillId,
        String input,
        Optional<String> formatFailure,
        Optional<String> repairEvidence,
        String pinnedSkillHash) {
      long started = System.nanoTime();
      try {
        String response =
            delegate.runOnce(
                conversationId,
                skillId,
                input,
                formatFailure,
                repairEvidence,
                pinnedSkillHash);
        attempts.add(
            attempt(formatFailure.orElse(null), response, null, elapsedMillis(started)));
        return response;
      } catch (RuntimeException e) {
        attempts.add(
            attempt(formatFailure.orElse(null), null, failureMessage(e), elapsedMillis(started)));
        throw e;
      }
    }

    private PlannerHarnessResponse.Attempt attempt(
        String formatFailure, String response, String error, long durationMillis) {
      return new PlannerHarnessResponse.Attempt(
          attempts.size() + 1, formatFailure, response, error, durationMillis);
    }

    private List<PlannerHarnessResponse.Attempt> attempts() {
      return List.copyOf(attempts);
    }

    private static long elapsedMillis(long started) {
      return (System.nanoTime() - started) / 1_000_000;
    }
  }
}

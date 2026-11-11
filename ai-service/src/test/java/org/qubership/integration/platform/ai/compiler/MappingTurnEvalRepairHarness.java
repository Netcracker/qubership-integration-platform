package org.qubership.integration.platform.ai.compiler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.MappingTurnEvalScorer.RepairScore;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidate;
import org.qubership.integration.platform.ai.productpipeline.create.ProducerOwnedRecovery;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Scores compiler mapping repair through {@link CaptureRepairRunner}, {@link ScriptBodyRepairTool},
 * and {@link ProducerOwnedRecovery} on the existing repair budget.
 */
public final class MappingTurnEvalRepairHarness {

  private static final String CONVERSATION_ID = "mapping-eval-repair";
  private static final String CAPABILITY_ID = "custom-script-generator";
  private static final String MAPPING_INTENT_ID = "map-init";
  private static final String MAPPING_CONTEXT = "mappingIntentId: map-init\n";
  private static final List<OwnerCandidate> EXECUTION_CANDIDATES =
      List.of(
          new OwnerCandidate("design-execution", "plan-validation-result"),
          new OwnerCandidate("design-planning", "implementation-plan"),
          new OwnerCandidate("requirement-analysis", "requirement-brief"));

  private MappingTurnEvalRepairHarness() {}

  public static List<RepairScore> run() {
    return List.of(acceptValidReplacement(), exhaustWithIntentAndFindings());
  }

  private static RepairScore acceptValidReplacement() {
    RepairSession session = new RepairSession();
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean captured = new AtomicBoolean(false);
    AtomicReference<String> repairPrompt = new AtomicReference<>("");
    try {
      session.runner
          .runWithRepair(
              message -> {
                int call = calls.incrementAndGet();
                if (call > 1) {
                  repairPrompt.set(message);
                }
                ScriptBodyRepairCapture capture =
                    call == 1 ? unexpectedCoverage("script-map-bad") : validCoverage("script-map-ok");
                try {
                  session.tool.repairScriptBodies(capture);
                } catch (CaptureValidationException terminal) {
                  captured.set(true);
                }
                return Multi.createFrom().empty();
              },
              captured::get,
              () -> session.feedbackStore.lastPatchFailure(CONVERSATION_ID, CAPABILITY_ID),
              () -> {},
              CaptureTool.REPAIR_SCRIPT_BODIES.toolName(),
              "Fill mapping script",
              true,
              feedback ->
                  session.messageBuilder.scriptBodiesRepairMessage(
                      List.of("transform-map-init"), feedback, MAPPING_CONTEXT))
          .collect()
          .asList()
          .await()
          .indefinitely();
      ProducerOwnedRecovery.Route insideBudget = recovery(0);
      boolean capturedPatch =
          session
              .captureSession
              .get(
                  CaptureKey.capability(
                      CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, CAPABILITY_ID),
                  GraphPatch.class)
              .isPresent();
      boolean secondLoop = calls.get() > 2;
      boolean passed =
          captured.get()
              && capturedPatch
              && calls.get() == 2
              && !secondLoop
              && repairPrompt.get().contains(MAPPING_INTENT_ID)
              && repairPrompt.get().contains("Do not change mappingIntents")
              && insideBudget.action() == ProducerOwnedRecovery.Action.REPAIR_CURRENT
              && MAPPING_INTENT_ID.equals(
                  MappingExecutionSite.mappingIntentId(
                      session.planStore.get(CONVERSATION_ID).orElseThrow().nodes().getFirst()));
      return new RepairScore(
          "repair-accept-valid",
          passed,
          calls.get(),
          secondLoop,
          passed
              ? "accepted valid replacement inside CaptureRepairRunner budget"
              : "did not accept a valid replacement calls=" + calls.get());
    } finally {
      session.close();
    }
  }

  private static RepairScore exhaustWithIntentAndFindings() {
    RepairSession session = new RepairSession();
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean captured = new AtomicBoolean(false);
    try {
      session.runner
          .runWithRepair(
              message -> {
                calls.incrementAndGet();
                try {
                  session.tool.repairScriptBodies(unexpectedCoverage("script-map-extra-" + calls.get()));
                } catch (CaptureValidationException ignored) {
                  // Repeated identical coverage can terminate the tool call; the runner must stop.
                }
                return Multi.createFrom().empty();
              },
              captured::get,
              () -> session.feedbackStore.lastPatchFailure(CONVERSATION_ID, CAPABILITY_ID),
              () -> {},
              CaptureTool.REPAIR_SCRIPT_BODIES.toolName(),
              "Fill mapping script",
              true,
              feedback ->
                  session.messageBuilder.scriptBodiesRepairMessage(
                      List.of("transform-map-init"), feedback, MAPPING_CONTEXT))
          .collect()
          .asList()
          .await()
          .indefinitely();
      String findings =
          session
              .feedbackStore
              .lastPatchFailure(CONVERSATION_ID, CAPABILITY_ID)
              .map(CaptureAttemptFeedback::summary)
              .orElse("");
      String exhausted =
          CaptureRepairMessageBuilder.mappingCaptureExhaustedMessage(MAPPING_INTENT_ID, findings);
      ProducerOwnedRecovery.Route afterBudget = recovery(1);
      boolean secondLoop = calls.get() > 2;
      boolean passed =
          !captured.get()
              && calls.get() == 2
              && !secondLoop
              && exhausted.contains(MAPPING_INTENT_ID)
              && exhausted.contains("repair budget was exhausted")
              && findings.contains("unexpected")
              && afterBudget.action() == ProducerOwnedRecovery.Action.PARK
              && "design-execution".equals(afterBudget.producerStageId())
              && MAPPING_INTENT_ID.equals(
                  MappingExecutionSite.mappingIntentId(
                      session.planStore.get(CONVERSATION_ID).orElseThrow().nodes().getFirst()));
      return new RepairScore(
          "repair-exhaust-findings",
          passed,
          calls.get(),
          secondLoop,
          passed
              ? "failed with mapping intent id and findings after the repair budget"
              : "exhaust path missed intent or findings calls=" + calls.get());
    } finally {
      session.close();
    }
  }

  private static ProducerOwnedRecovery.Route recovery(int semanticRepairsUsed) {
    return ProducerOwnedRecovery.route(
        new ProducerOwnedRecovery.Request(
            "design-execution",
            StageOutcomeClass.CONTRACT_FAILURE,
            RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER),
            EXECUTION_CANDIDATES,
            false,
            semanticRepairsUsed,
            1,
            java.util.Optional.empty()));
  }

  private static ScriptBodyRepairCapture unexpectedCoverage(String patchId) {
    return new ScriptBodyRepairCapture(
        patchId,
        List.of(
            new ScriptBodyEntry(
                "transform-map-init",
                "target['orderId'] = source['orderId']\n",
                List.of("$.orderId", "$.extra"))),
        "Fill mapping script");
  }

  private static ScriptBodyRepairCapture validCoverage(String patchId) {
    return new ScriptBodyRepairCapture(
        patchId,
        List.of(
            new ScriptBodyEntry(
                "transform-map-init",
                "target['orderId'] = source['orderId']\n",
                List.of("$.orderId"))),
        "Fill mapping script");
  }

  private static final class RepairSession {
    private final CaptureSession captureSession = new CaptureSession();
    private final ChainPlanStore planStore = new ChainPlanStore();
    private final CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    private final GraphPatchExecutionContextStore executionContextStore =
        new GraphPatchExecutionContextStore();
    private final CaptureRepairMessageBuilder messageBuilder =
        new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class));
    private final CaptureRepairRunner runner;
    private final ScriptBodyRepairTool tool;

    private RepairSession() {
      AppConfig.CaptureConfig captureConfig = mock(AppConfig.CaptureConfig.class);
      when(captureConfig.maxRepairAttempts()).thenReturn(1);
      AppConfig appConfig = mock(AppConfig.class);
      when(appConfig.capture()).thenReturn(captureConfig);
      runner = new CaptureRepairRunner(messageBuilder, feedbackStore, appConfig);
      CaptureRouter captureRouter = mock(CaptureRouter.class);
      when(captureRouter.routeFor(CAPABILITY_ID))
          .thenReturn(new CaptureRoute(CAPABILITY_ID, CaptureTool.REPAIR_SCRIPT_BODIES));
      tool =
          new ScriptBodyRepairTool(
              captureRouter,
              captureSession,
              planStore,
              new GeneratorReadinessEvaluator(),
              new GraphPatchApplier(),
              feedbackStore,
              executionContextStore,
              mock(org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.class));
      ChainPlanGraph graph = mappingGraph();
      planStore.put(CONVERSATION_ID, graph);
      executionContextStore.set(
          CONVERSATION_ID,
          CAPABILITY_ID,
          new GraphPatchExecutionContext(
                  "map-run",
                  CAPABILITY_ID,
                  null,
                  null,
                  null,
                  null,
                  identityBrief(),
                  List.of(),
                  graph,
                  GraphPatchOwnershipPolicy.denyAll(),
                  "attempt-1")
              .withMappingGenerationContext(MAPPING_CONTEXT));
      MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
      MDC.put(CompilerSkillMdc.CAPABILITY_ID, CAPABILITY_ID);
    }

    private void close() {
      MDC.remove(ChatMdc.CONVERSATION_ID);
      MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
    }
  }

  private static RequirementBrief identityBrief() {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map orderId",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    MAPPING_INTENT_ID,
                    "trigger-http",
                    MappingPort.OUTPUT,
                    "call-1",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)),
                    "SCRIPT")));
  }

  private static ChainPlanGraph mappingGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "transform-map-init",
                "script",
                "Map",
                null,
                1,
                List.of(
                    new PlanProperty("mappingIntentId", MAPPING_INTENT_ID),
                    new PlanProperty("script", "")))),
        List.of());
  }
}

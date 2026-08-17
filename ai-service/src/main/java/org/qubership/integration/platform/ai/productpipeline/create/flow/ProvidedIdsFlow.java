package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static io.quarkiverse.flow.dsl.FlowDSL.caseDefault;
import static io.quarkiverse.flow.dsl.FlowDSL.caseOf;
import static io.quarkiverse.flow.dsl.FlowDSL.consumed;
import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowDSL.listen;
import static io.quarkiverse.flow.dsl.FlowDSL.switchCase;
import static io.quarkiverse.flow.dsl.FlowDSL.to;
import static io.quarkiverse.flow.dsl.FlowDSL.toOne;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import io.quarkiverse.flow.Flow;
import io.quarkiverse.flow.dsl.FlowDSL;
import io.quarkiverse.flow.dsl.FuncListenSpec;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;

/** One persisted create-chain Flow instance per product run. */
@ApplicationScoped
public class ProvidedIdsFlow extends Flow {

  static final String PROFILE_ID = "create-chain";
  static final String PROFILE_VERSION = "2";
  static final String INSTANCE_EXTENSION = "flowinstanceid";
  static final String TASK_ROUTE_DECISION = "routeDecision";
  public static final String INPUT_EVENT_TYPE = "org.qubership.qip.create-chain.input.v1";
  public static final String APPROVAL_EVENT_TYPE = "org.qubership.qip.create-chain.approval.v1";
  public static final String IMPLEMENT_EVENT_TYPE = "org.qubership.qip.create-chain.implement.v1";

  private final ProductPipelineProfile profile;
  private final ProvidedIdsFlowTasks tasks;

  @Inject
  public ProvidedIdsFlow(ProductPipelineProfileCatalog profiles, ProvidedIdsFlowTasks tasks) {
    this(profiles.require(PROFILE_ID, PROFILE_VERSION), tasks);
  }

  ProvidedIdsFlow(ProductPipelineProfile profile, ProvidedIdsFlowTasks tasks) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
  }

  @Override
  public Workflow descriptor() {
    return workflow("qip", "create-chain-provided-ids", "1.0.0")
        .tasks(
            function("executeStage", tasks::executeCurrentStage, RunContext.class),
            switchCase(
                TASK_ROUTE_DECISION,
                caseOf(RunContext::waitForInput, RunContext.class).then("waitForInput"),
                caseOf(RunContext::waitForRequirementApproval, RunContext.class)
                    .then("waitForRequirementApproval"),
                caseOf(RunContext::waitForIdsApproval, RunContext.class).then("waitForIdsApproval"),
                caseOf(RunContext::waitForPlanApproval, RunContext.class)
                    .then("waitForPlanApproval"),
                caseOf(RunContext::waitForImplementation, RunContext.class)
                    .then("waitForImplementation"),
                caseOf(RunContext::waitForRetry, RunContext.class).then("waitForRetry"),
                caseOf(RunContext::reenterStage, RunContext.class).then("executeStage"),
                caseDefault(FlowDirectiveEnum.END)),
            listen("waitForInput", correlatedOne(INPUT_EVENT_TYPE)),
            function("restoreAfterInput", tasks::restoreAfterInput, Object.class),
            switchCase(
                "afterInput",
                caseOf(RunContext::always, RunContext.class).then(TASK_ROUTE_DECISION),
                caseDefault(FlowDirectiveEnum.END)),
            listen(
                "waitForRequirementApproval", correlatedAny(APPROVAL_EVENT_TYPE, INPUT_EVENT_TYPE)),
            function("restoreAfterRequirementApproval", tasks::restoreAfterInput, Object.class),
            switchCase(
                "afterRequirementApproval",
                caseOf(RunContext::always, RunContext.class).then(TASK_ROUTE_DECISION),
                caseDefault(FlowDirectiveEnum.END)),
            listen("waitForIdsApproval", correlatedAny(APPROVAL_EVENT_TYPE, INPUT_EVENT_TYPE)),
            function("restoreAfterIdsApproval", tasks::restoreAfterInput, Object.class),
            switchCase(
                "afterIdsApproval",
                caseOf(RunContext::always, RunContext.class).then(TASK_ROUTE_DECISION),
                caseDefault(FlowDirectiveEnum.END)),
            listen("waitForPlanApproval", correlatedAny(APPROVAL_EVENT_TYPE, INPUT_EVENT_TYPE)),
            function("restoreAfterPlanApproval", tasks::restoreAfterInput, Object.class),
            switchCase(
                "afterPlanApproval",
                caseOf(RunContext::always, RunContext.class).then(TASK_ROUTE_DECISION),
                caseDefault(FlowDirectiveEnum.END)),
            listen("waitForImplementation", correlatedOne(IMPLEMENT_EVENT_TYPE)),
            function("restoreAfterImplementation", tasks::restoreAfterInput, Object.class),
            switchCase(
                "afterImplementation",
                caseOf(RunContext::always, RunContext.class).then(TASK_ROUTE_DECISION),
                caseDefault(FlowDirectiveEnum.END)),
            FlowDSL.wait("waitForRetry", "${.retryDelay}"),
            function("restoreAfterRetry", tasks::restoreAfterRetry, Object.class),
            switchCase(
                "afterRetry",
                caseOf(RunContext::always, RunContext.class).then("executeStage"),
                caseDefault(FlowDirectiveEnum.END)))
        .build();
  }

  boolean ownsStage(String stageId) {
    return profile.stages().stream().anyMatch(stage -> stage.stageId().equals(stageId));
  }

  private static FuncListenSpec correlatedOne(String eventType) {
    return toOne(consumed(eventType).extensionByInstanceId(INSTANCE_EXTENSION));
  }

  private static FuncListenSpec correlatedAny(String first, String second) {
    return to()
        .any(
            consumed(first).extensionByInstanceId(INSTANCE_EXTENSION),
            consumed(second).extensionByInstanceId(INSTANCE_EXTENSION));
  }

  /**
   * Durable Flow context for one product run. {@code decision} is the last stage-module outcome
   * Flow should apply.
   */
  public record RunContext(
      String runId,
      String profileId,
      String profileVersion,
      String runManifestDigest,
      String decision,
      Integer technicalRetriesUsed,
      String retryDelay) {

    public RunContext(
        String runId,
        String profileId,
        String profileVersion,
        String runManifestDigest,
        String decision) {
      this(runId, profileId, profileVersion, runManifestDigest, decision, null, null);
    }

    public boolean waitForInput() {
      return "WAIT_FOR_INPUT".equals(decision);
    }

    public boolean waitForRequirementApproval() {
      return "WAIT_FOR_REQUIREMENT_APPROVAL".equals(decision);
    }

    public boolean waitForIdsApproval() {
      return "WAIT_FOR_IDS_APPROVAL".equals(decision);
    }

    public boolean waitForPlanApproval() {
      return "WAIT_FOR_PLAN_APPROVAL".equals(decision);
    }

    public boolean waitForImplementation() {
      return "WAIT_FOR_IMPLEMENTATION".equals(decision);
    }

    public boolean waitForRetry() {
      return "RETRY".equals(decision);
    }

    public boolean retryOrReopen() {
      return waitForRetry() || "REOPEN".equals(decision);
    }

    public boolean reenterStage() {
      return "CONTINUE".equals(decision);
    }

    public boolean always() {
      return true;
    }

    RunContext withDecision(String nextDecision) {
      return new RunContext(
          runId, profileId, profileVersion, runManifestDigest, nextDecision, null, null);
    }

    RunContext withRetry(Duration delay, int used) {
      Duration safe = delay == null ? Duration.ZERO : delay;
      return new RunContext(
          runId,
          profileId,
          profileVersion,
          runManifestDigest,
          "RETRY",
          used,
          safe.toString());
    }

    RunContext withContinueKeepingRetries() {
      return new RunContext(
          runId,
          profileId,
          profileVersion,
          runManifestDigest,
          "CONTINUE",
          technicalRetriesUsed,
          retryDelay);
    }
  }
}

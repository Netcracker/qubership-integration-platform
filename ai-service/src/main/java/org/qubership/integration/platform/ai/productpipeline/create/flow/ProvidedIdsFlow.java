package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import io.quarkiverse.flow.Flow;
import io.quarkiverse.flow.dsl.configurers.FuncTaskConfigurer;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;

/** Profile-defined Flow route from {@code ids-entry} through implementation-plan review. */
@ApplicationScoped
public class ProvidedIdsFlow extends Flow {

  static final String PROFILE_ID = "create-chain";
  static final String PROFILE_VERSION = "2";

  private final ProductPipelineProfile profile;
  private final ProvidedIdsFlowTasks tasks;

  @Inject
  public ProvidedIdsFlow(
      ProductPipelineProfileCatalog profiles, ProvidedIdsFlowTasks tasks) {
    this(profiles.require(PROFILE_ID, PROFILE_VERSION), tasks);
  }

  ProvidedIdsFlow(ProductPipelineProfile profile, ProvidedIdsFlowTasks tasks) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
  }

  @Override
  public Workflow descriptor() {
    List<FuncTaskConfigurer> flowTasks =
        stagesThroughPlanning(profile).stream()
            .<FuncTaskConfigurer>map(
                stage ->
                    function(
                        stage.stageId(),
                        (Function<RunInput, CompletableFuture<RunInput>>)
                            input -> tasks.execute(input, stage.stageId()),
                        RunInput.class))
            .toList();
    return workflow("qip", "create-chain-provided-ids", "1.0.0")
        .tasks(flowTasks.toArray(FuncTaskConfigurer[]::new))
        .build();
  }

  static List<ProfileStage> stagesThroughPlanning(ProductPipelineProfile profile) {
    Objects.requireNonNull(profile, "profile");
    if (profile.implementationGate() == null) {
      throw new IllegalArgumentException("profile has no implementation gate");
    }
    String boundary = profile.implementationGate().afterStageId();
    List<ProfileStage> stages = new ArrayList<>();
    for (ProfileStage stage : profile.stages()) {
      stages.add(stage);
      if (boundary.equals(stage.stageId())) {
        return List.copyOf(stages);
      }
    }
    throw new IllegalArgumentException("implementation-gate stage is missing: " + boundary);
  }

  /** Minimal workflow context; durable pipeline state remains in the existing stores. */
  public record RunInput(String runId, String invocationId) {}
}

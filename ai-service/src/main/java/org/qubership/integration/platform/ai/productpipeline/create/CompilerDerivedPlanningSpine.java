package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/**
 * create-chain@1 planning spine. Builds a full-DAG {@link CompilerDagExecutionRequest}, delegates to
 * {@link CompilerDagExecutionEngine}, and maps the result back to {@link
 * CompilerPlanningRunner.PlanningSpineOutcome} without changing v1 status, artifact order, hashes,
 * messages, approval timing, retries, or producer versions.
 */
@ApplicationScoped
public class CompilerDerivedPlanningSpine implements CompilerPlanningSpine {

  private static final Logger LOG = Logger.getLogger(CompilerDerivedPlanningSpine.class);

  private final InMemorySkillWorkspaceStore workspaceStore;
  private final CreateRunBindingStore bindingStore;
  private final DefaultCompilerDagExecutionEngine engine;

  @Inject
  public CompilerDerivedPlanningSpine(
      InMemorySkillWorkspaceStore workspaceStore,
      CreateRunBindingStore bindingStore,
      DefaultCompilerDagExecutionEngine engine) {
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    this.engine = Objects.requireNonNull(engine, "engine");
  }

  @SuppressWarnings("java:S107")
  CompilerDerivedPlanningSpine(
      InMemorySkillWorkspaceStore workspaceStore,
      SkillExecutorRegistry skillRegistry,
      CompilerNodeExecutionAdapterRegistry javaAdapterRegistry,
      CreateRunBindingStore bindingStore,
      QipKnowledgePackRepository packRepository,
      GraphAssemblyService graphAssemblyService,
      CompilerValidationPipeline compilerValidationPipeline,
      ProductPipelineArtifactStore artifactStore) {
    this(
        workspaceStore,
        bindingStore,
        new DefaultCompilerDagExecutionEngine(
            workspaceStore,
            skillRegistry,
            javaAdapterRegistry,
            packRepository,
            graphAssemblyService,
            compilerValidationPipeline,
            artifactStore));
  }

  @SuppressWarnings("java:S107")
  CompilerDerivedPlanningSpine(
      InMemorySkillWorkspaceStore workspaceStore,
      SkillExecutorRegistry skillRegistry,
      CompilerNodeExecutionAdapterRegistry javaAdapterRegistry,
      CreateRunBindingStore bindingStore,
      QipKnowledgePackRepository packRepository,
      GraphAssemblyService graphAssemblyService,
      CompilerValidationPipeline compilerValidationPipeline) {
    this(
        workspaceStore,
        skillRegistry,
        javaAdapterRegistry,
        bindingStore,
        packRepository,
        graphAssemblyService,
        compilerValidationPipeline,
        new ProductPipelineArtifactStore(
            new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts(
                new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore(),
                new ObjectMapper(),
                java.time.Clock.systemUTC())));
  }

  @Override
  public Uni<CompilerPlanningRunner.PlanningSpineOutcome> execute(
      CompilerPlanningRequest request, BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(request, "request");
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    return Uni.createFrom()
        .item(
            () -> {
              PinnedRunContext pinned = resolvePinnedRun(request.conversationId());
              CompilerDagExecutionRequest engineRequest =
                  new CompilerDagExecutionRequest(
                      request.runId(),
                      request.conversationId(),
                      pinned.manifest(),
                      request.requirementBrief(),
                      null,
                      pinned.pin().resolvedDag(),
                      List.of(),
                      List.of());
              CompilerDagExecutionResult result =
                  engine
                      .execute(
                          engineRequest,
                          request.languageVersion(),
                          request.attemptId(),
                          progress)
                      .await()
                      .indefinitely();
              return toOutcome(request.conversationId(), result);
            });
  }

  static PlanningSchedulerState convergeAfterPatchArtifact(
      PlanningSchedulerState state,
      String ownerSkillId,
      org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact
          patchArtifact) {
    return DefaultCompilerDagExecutionEngine.convergeAfterPatchArtifact(
        state, ownerSkillId, patchArtifact);
  }

  private PinnedRunContext resolvePinnedRun(String conversationId) {
    CreateRunBinding binding =
        bindingStore
            .load(conversationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "contract failure: missing create run binding for conversation "
                            + conversationId));
    RunManifest manifest = binding.runManifest();
    if (manifest == null) {
      throw new IllegalStateException("contract failure: run manifest is required for pinned planning");
    }
    CompilerRunPin pin = manifest.compilerRunPin();
    if (pin == null || pin.resolvedDag() == null) {
      throw new IllegalStateException(
          "contract failure: compiler run pin with resolved DAG is required");
    }
    return new PinnedRunContext(binding, manifest, pin);
  }

  private CompilerPlanningRunner.PlanningSpineOutcome toOutcome(
      String conversationId, CompilerDagExecutionResult result) {
    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    ValidationResult validation =
        workspace
            .get(SkillArtifactType.PRE_BUILD_VALIDATION)
            .map(a -> ((SkillArtifactPayload.ValidationResultPayload) a.payload()).result())
            .orElse(null);
    SelectedPattern pattern =
        workspace
            .get(SkillArtifactType.SELECTED_PATTERN)
            .map(a -> ((SkillArtifactPayload.SelectedPatternPayload) a.payload()).pattern())
            .orElse(null);
    String patternId = pattern == null ? null : pattern.patternId();
    String patternSummary = pattern == null ? null : pattern.summary();
    LOG.infof(
        "Compiler-derived planning spine completed conversationId=%s executed=%s",
        conversationId, result.executedSkillIds());
    return new CompilerPlanningRunner.PlanningSpineOutcome(
        List.copyOf(result.executedSkillIds()),
        result.graph(),
        validation,
        patternId,
        patternSummary,
        List.of(),
        result.degradationFindings());
  }

  private record PinnedRunContext(
      CreateRunBinding binding, RunManifest manifest, CompilerRunPin pin) {
    private PinnedRunContext {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(pin, "pin");
    }
  }
}

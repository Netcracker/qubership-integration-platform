package org.qubership.integration.platform.ai.harness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultApprovedCompilerExecutionRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanProjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Runs the compiler executor from saved planner output without invoking the planner again. */
@ApplicationScoped
public class ExecutorHarnessService {

  private static final String PRODUCER_ID = "executor-harness";

  private final CreateRunSelectionService selectionService;
  private final CompilerRunPinResolver pinResolver;
  private final CompilerContractRepository contractRepository;
  private final ProductPipelineArtifactStore artifactStore;
  private final DefaultApprovedCompilerExecutionRunner runner;
  private final DesignPlanProjector projector;
  private final String modelName;

  @Inject
  public ExecutorHarnessService(
      CreateRunSelectionService selectionService,
      CompilerRunPinResolver pinResolver,
      CompilerContractRepository contractRepository,
      ProductPipelineArtifactStore artifactStore,
      DefaultApprovedCompilerExecutionRunner runner,
      @ConfigProperty(name = "qip.ai.llm.model-name")
          String modelName) {
    this(
        selectionService,
        pinResolver,
        contractRepository,
        artifactStore,
        runner,
        new DesignPlanProjector(),
        modelName);
  }

  ExecutorHarnessService(
      CreateRunSelectionService selectionService,
      CompilerRunPinResolver pinResolver,
      CompilerContractRepository contractRepository,
      ProductPipelineArtifactStore artifactStore,
      DefaultApprovedCompilerExecutionRunner runner,
      DesignPlanProjector projector,
      String modelName) {
    this.selectionService = Objects.requireNonNull(selectionService, "selectionService");
    this.pinResolver = Objects.requireNonNull(pinResolver, "pinResolver");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.modelName = modelName;
  }

  public ExecutorHarnessResponse run(ExecutorHarnessRequest request) {
    long started = System.nanoTime();
    String conversationId = resolveConversationId(request.conversationId());
    List<ExecutorHarnessResponse.SkillProgress> progress = new ArrayList<>();
    List<String> plannedSkillIds = List.of();
    try {
      var selection = selectionService.selectOrCreate(conversationId, request.requirementBrief().goal());
      RunManifest baseManifest = selection.runManifest();
      CompilerContract contract =
          contractRepository.require(request.semanticRevision().compilerContractVersion());
      CompilerRunPin semanticPin =
          pinResolver.resolve(baseManifest.runId(), request.semanticRevision(), contract);
      RunManifest manifest = withPin(baseManifest, baseManifest.compilerRunPin().withSemanticSubject(semanticPin));
      DesignPlanReport report = new DesignPlanReport("1", request.plannerResponse());
      DesignExecutionPlan plan =
          projector.project(report, request.semanticRevision(), manifest.compilerRunPin(), request.requirementBrief());
      plannedSkillIds = DefaultApprovedCompilerExecutionRunner.orderedOwningSkillIds(plan);
      Reference briefRef = appendBrief(manifest, request.requirementBrief());

      CompilerDagExecutionResult result =
          runner.executeHarness(
              plan,
              request.semanticRevision(),
              request.bindings(),
              manifest,
              conversationId,
              request.requirementBrief(),
              briefRef,
              (skillId, status) ->
                  progress.add(new ExecutorHarnessResponse.SkillProgress(skillId, status)));
      boolean successfulOutcome =
          result.outcomeClass() == StageOutcomeClass.SUCCEEDED
              || result.outcomeClass() == StageOutcomeClass.CANDIDATE;
      boolean valid =
          result.validationBundle() != null && result.validationBundle().approvalEligible();
      SkillHarnessStatus status =
          successfulOutcome && valid ? SkillHarnessStatus.COMPLETED : SkillHarnessStatus.FAILED;
      String message =
          result.message() == null && !valid ? "compiler validation failed" : result.message();
      return new ExecutorHarnessResponse(
          conversationId,
          status,
          message,
          false,
          modelName,
          plannedSkillIds,
          result.executedSkillIds(),
          progress,
          result.patchLedger(),
          result.graph(),
          result.assemblyResult(),
          result.validationBundle(),
          result.degradationFindings(),
          result.presentArtifactTypes(),
          elapsedMillis(started));
    } catch (RuntimeException e) {
      return new ExecutorHarnessResponse(
          conversationId,
          SkillHarnessStatus.FAILED,
          failureMessage(e),
          false,
          modelName,
          plannedSkillIds,
          List.of(),
          progress,
          null,
          null,
          null,
          null,
          List.of(),
          java.util.Set.of(),
          elapsedMillis(started));
    }
  }

  private Reference appendBrief(RunManifest manifest, RequirementBrief brief) {
    return artifactStore
        .append(
            new AppendCommand(
                manifest.runId(),
                Kind.REQUIREMENT_BRIEF,
                "1",
                PRODUCER_ID,
                "1",
                brief,
                List.of(),
                null,
                provenance(manifest)))
        .reference();
  }

  private static RunManifest withPin(RunManifest manifest, CompilerRunPin pin) {
    return new RunManifest(
        manifest.runId(),
        manifest.parentRunId(),
        manifest.sourceReferences(),
        manifest.runtimeSelection(),
        manifest.profileId(),
        manifest.profileVersion(),
        manifest.profileDigest(),
        manifest.referenceBaselineId(),
        manifest.referenceBaselineDigest(),
        manifest.dependencyClosure(),
        manifest.dependencyClosureDigest(),
        manifest.knowledgePackage(),
        manifest.languageVersion(),
        manifest.artifactSchemaVersions(),
        pin,
        manifest.responseLocale());
  }

  private static ArtifactProvenance provenance(RunManifest manifest) {
    return new ArtifactProvenance(
        manifest.runId(),
        PRODUCER_ID,
        manifest.profileId(),
        manifest.profileVersion(),
        manifest.profileDigest(),
        PRODUCER_ID,
        "1",
        manifest.dependencyClosureDigest());
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

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}

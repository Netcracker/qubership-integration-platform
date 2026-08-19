package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchRemovalClosure;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanningCapability;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanStatus;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerExecutionSeed;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * Prepares one edit of an existing chain, and never writes to the catalog.
 *
 * <p>An edit is a compilation whose starting graph is the imported chain. The reader's words are
 * resolved into a typed intent, anything the request names outside the chain is resolved against
 * the catalog, and the same structure and configuration owners CREATE uses compile the desired
 * graph. What comes back is diffed against the imported graph, so the reader approves one change
 * rather than a replay of the generators' working.
 *
 * <p>Every branch that is not a proposal says why: an ambiguous target or operation asks, a missing
 * one reports it plainly, and a compiler refusal names the refusal. None of them invent a catalog
 * identity to keep going.
 */
@ApplicationScoped
public class ChainEditCompiler {

  private static final Logger LOG = Logger.getLogger(ChainEditCompiler.class);
  private static final String STRUCTURE_GENERATOR = "cip-structure-generator";

  private final ChainEditIntentResolver intentResolver;
  private final ServiceCallBindingResolver bindingResolver;
  private final CompilerDagExecutionEngine engine;
  private final CompilerRunPinResolver runPinResolver;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final KnowledgeContextProvider knowledgeContextProvider;
  private final CatalogMutationGateway catalogMutationGateway;
  private final CaptureSession captureSession;

  @Inject
  @SuppressWarnings("java:S107")
  public ChainEditCompiler(
      ChainEditIntentResolver intentResolver,
      ServiceCallBindingResolver bindingResolver,
      CompilerDagExecutionEngine engine,
      CompilerRunPinResolver runPinResolver,
      ProductPipelineProfileCatalog profileCatalog,
      KnowledgeContextProvider knowledgeContextProvider,
      CatalogMutationGateway catalogMutationGateway,
      CaptureSession captureSession) {
    this.intentResolver = Objects.requireNonNull(intentResolver, "intentResolver");
    this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.runPinResolver = Objects.requireNonNull(runPinResolver, "runPinResolver");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.knowledgeContextProvider =
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider");
    this.catalogMutationGateway = catalogMutationGateway;
    this.captureSession = Objects.requireNonNull(captureSession, "captureSession");
  }

  /**
   * Imports the specification the reader approved, then continues the edit they were shown.
   *
   * <p>The release comes from the chain's own language version through the same mapping CREATE
   * uses, so an edit does not pull in a release the chain cannot run against. An import that comes
   * back without a complete operation is reported rather than patched over.
   */
  public ChainEditOutcome resumeAfterImport(
      ChainEditRequest request, ChainEditIntent intent, ApiHubRequirementRefs refs) {
    if (catalogMutationGateway == null) {
      return new ChainEditOutcome.ResolutionFailure(
          "Importing a specification is not available here, so nothing was changed.");
    }
    ApiHubSpecificationImportResult imported;
    try {
      imported =
          catalogMutationGateway
              .importApiHubSpecification(request.conversationId(), refs)
              .await()
              .indefinitely();
    } catch (RuntimeException e) {
      LOG.errorf(e, "APIHub import failed conversationId=%s", request.conversationId());
      return new ChainEditOutcome.ResolutionFailure(
          "The specification could not be imported, so the chain is unchanged: " + e.getMessage());
    }
    ServiceCallBindingOutcome bound =
        bindingResolver.fromImport(
            intent.targetNodeIds().get(0),
            imported,
            DesignPlanningCapability.toApiRelease(request.languageVersion()));
    if (bound instanceof ServiceCallBindingOutcome.Resolved(ResolvedServiceCallBinding binding)) {
      return compile(request, intent, binding);
    }
    return new ChainEditOutcome.ResolutionFailure(
        bound instanceof ServiceCallBindingOutcome.NotFound(String message)
            ? message
            : "The imported specification did not resolve to one operation.");
  }

  public ChainEditOutcome compile(ChainEditRequest request) {
    Objects.requireNonNull(request, "request");
    ImportedChainPlan imported = request.imported();
    ChainEditIntent intent = intentResolver.resolve(imported.graph(), request.userRequest());
    ChainEditOutcome noChange = noChangeOutcome(intent);
    if (noChange != null) {
      return noChange;
    }
    if (!intent.resolved()) {
      return new ChainEditOutcome.Clarification(
          "I need one more thing before I change anything.", intent.unresolvedAmbiguities());
    }
    return compile(request, intent, null);
  }

  /**
   * Compiles an already-resolved intent, optionally with a binding resolved elsewhere.
   *
   * <p>An edit held for an APIHub import resumes here: the imported chain, the intent and the
   * target are the ones the reader saw, so approving the import continues the same edit instead of
   * starting a new one from words that have since scrolled away.
   */
  public ChainEditOutcome compile(
      ChainEditRequest request, ChainEditIntent intent, ResolvedServiceCallBinding importedBinding) {
    ImportedChainPlan imported = request.imported();
    if (deterministic(intent.action())) {
      return transform(request, intent);
    }
    CompilerRunPin pin;
    try {
      pin = resolvePin(request.conversationId());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Compiler pin unavailable conversationId=%s", request.conversationId());
      return new ChainEditOutcome.CompilationFailure(
          "The compiler package this edit needs is unavailable: " + e.getMessage());
    }

    if (requiresStructureStage(intent)) {
      return compileStructuralAddition(request, intent, importedBinding, pin);
    }

    String generatorSkillId =
        ChainEditCapabilitySelection.owningSkillId(pin.resolvedDag(), intent).orElse(null);
    if (generatorSkillId == null) {
      return new ChainEditOutcome.Unsupported(intent.action());
    }

    List<String> scopedTargets =
        ChainEditCapabilitySelection.scopedTargets(
            pin.resolvedDag(), generatorSkillId, intent, imported.graph());
    ChainEditOutcome incomplete = incompleteAddition(intent, scopedTargets);
    if (incomplete != null) {
      return incomplete;
    }
    if (scopedTargets.isEmpty() && intent.action() != ChainEditAction.ADD_ELEMENTS) {
      return new ChainEditOutcome.ResolutionFailure(
          "No element in that request is one " + generatorSkillId + " may change.");
    }
    ChainEditIntent scoped = intent.withTargets(scopedTargets);

    List<ResolvedServiceCallBinding> bindings;
    if (importedBinding != null) {
      bindings = List.of(importedBinding);
    } else if (scoped.action() == ChainEditAction.REBIND_SERVICE_CALL
        || (scoped.action() == ChainEditAction.ADD_ELEMENTS
            && "service-call".equals(scoped.requestedElementType()))) {
      // A new service-call needs the same complete binding a rebind does -- the generator owns
      // the field set (id, method, path, protocol...) as one unit, and nothing here lets it invent
      // the values that describe a real catalog operation.
      ChainPlanNode target = node(imported.graph(), scopedTargets.get(0));
      ServiceCallBindingOutcome resolved =
          bindingResolver.resolve(
              target,
              scoped.externalBindingQuery() == null
                  ? scoped.requestedChange()
                  : scoped.externalBindingQuery());
      switch (resolved) {
        case ServiceCallBindingOutcome.Resolved(ResolvedServiceCallBinding binding) ->
            bindings = List.of(binding);
        case ServiceCallBindingOutcome.Ambiguous(String question, List<String> candidates) -> {
          return new ChainEditOutcome.Clarification(question, candidates);
        }
        case ServiceCallBindingOutcome.NotFound(String message) -> {
          return new ChainEditOutcome.ResolutionFailure(message);
        }
        case ServiceCallBindingOutcome.EscalationRequired(String message, ApiHubRequirementRefs refs) -> {
          return new ChainEditOutcome.Escalation(message, scoped, refs);
        }
      }
    } else {
      bindings = List.of();
    }

    PlacementAndIntent placed = placeAddition(imported.graph(), scoped, pin, generatorSkillId);
    GeneratorPlan plan = generatorPlan(generatorSkillId, placed.intent());
    return runCompiler(
        request,
        placed.intent(),
        bindings,
        List.of(plan),
        List.of(generatorSkillId),
        Set.of(),
        List.of(),
        pin,
        placed.graph());
  }

  private ChainEditOutcome compileStructuralAddition(
      ChainEditRequest request,
      ChainEditIntent intent,
      ResolvedServiceCallBinding importedBinding,
      CompilerRunPin pin) {
    ImportedChainPlan imported = request.imported();
    List<ResolvedServiceCallBinding> bindings =
        importedBinding == null ? List.of() : List.of(importedBinding);
    CompilerDagExecutionResult structureResult;
    try {
      structureResult = runStructureStage(request, intent, bindings, pin);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Chain edit structure generation failed runId=%s", request.editRunId());
      return new ChainEditOutcome.CompilationFailure(describeFailure(e));
    }
    if (structureResult.graph() == null) {
      return new ChainEditOutcome.CompilationFailure("The structure stage produced no graph.");
    }

    ChainPlanGraph structured;
    try {
      structured = ChainEditStructureMerge.merge(imported.graph(), structureResult.graph(), intent);
    } catch (IllegalArgumentException e) {
      return new ChainEditOutcome.CompilationFailure(
          "The captured structure was rejected: " + e.getMessage());
    }
    List<GeneratorPlan> plans =
        ChainEditCapabilitySelection.structuralGeneratorPlans(
            pin.resolvedDag(), imported.graph(), structured, intent);
    if (plans.isEmpty() && sameNodeIds(imported.graph(), structured)) {
      return new ChainEditOutcome.CompilationFailure(
          "The structure stage did not add the requested elements.");
    }

    List<String> skillIds = new ArrayList<>();
    skillIds.add(STRUCTURE_GENERATOR);
    skillIds.addAll(plans.stream().map(GeneratorPlan::skillId).toList());
    return runCompiler(
        request,
        intent,
        bindings,
        plans,
        List.copyOf(skillIds),
        Set.of(STRUCTURE_GENERATOR),
        structureResult.executedSkillIds(),
        pin,
        structured);
  }

  private CompilerDagExecutionResult runStructureStage(
      ChainEditRequest request,
      ChainEditIntent intent,
      List<ResolvedServiceCallBinding> bindings,
      CompilerRunPin pin) {
    ImportedChainPlan imported = request.imported();
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forEdit(
            request.editRunId(),
            request.userRequest(),
            imported.graph(),
            imported.materializationMap(),
            intent,
            bindings,
            Set.of());
    ResolvedCompilerDag dag =
        ChainEditCompilerDag.structureOnly(pin.resolvedDag(), seed.presentArtifactTypes());
    RunManifest manifest =
        ChainEditCompilerDag.pinnedManifest(
            baseManifest(request, pin), request.editRunId(), dag);
    // Published so the capture tool validates the merge of this capture onto the imported chain
    // rather than the capture alone, and can hand a rejected merge back as repairable feedback.
    CaptureKey structureBaseKey =
        CaptureKey.conversation(
            CaptureSlot.CHAIN_EDIT_STRUCTURE_BASE, request.conversationId());
    captureSession.set(structureBaseKey, new ChainEditStructureBase(imported.graph(), intent));
    try {
      return engine
          .execute(
              new CompilerDagExecutionRequest(
                  request.editRunId(),
                  request.conversationId(),
                  manifest,
                  null,
                  null,
                  dag,
                  List.of(STRUCTURE_GENERATOR),
                  List.of(),
                  List.of(),
                  seed),
              (skillId, status) -> {})
          .await()
          .indefinitely();
    } finally {
      captureSession.clear(structureBaseKey);
    }
  }

  private static boolean requiresStructureStage(ChainEditIntent intent) {
    return intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.placement() == ChainEditPlacement.GENERATOR;
  }

  private static boolean sameNodeIds(ChainPlanGraph left, ChainPlanGraph right) {
    Set<String> leftIds = new LinkedHashSet<>();
    Set<String> rightIds = new LinkedHashSet<>();
    if (left.nodes() != null) {
      left.nodes().forEach(node -> leftIds.add(node.nodeId()));
    }
    if (right.nodes() != null) {
      right.nodes().forEach(node -> rightIds.add(node.nodeId()));
    }
    return leftIds.equals(rightIds);
  }

  /**
   * Places simple additions that do not require the shared structure stage before configuration.
   */
  private static PlacementAndIntent placeAddition(
      ChainPlanGraph imported,
      ChainEditIntent scoped,
      CompilerRunPin pin,
      String generatorSkillId) {
    if (scoped.action() != ChainEditAction.ADD_ELEMENTS) {
      return new PlacementAndIntent(imported, scoped);
    }
    ChainEditNodePlacement.Placement placement;
    if (scoped.placement() == ChainEditPlacement.ROOT_TRIGGER) {
      placement =
          ChainEditNodePlacement.addTrigger(
              imported,
              scoped.targetNodeIds(),
              scoped.requestedElementType(),
              "New " + scoped.requestedElementType());
    } else if (scoped.placement() == ChainEditPlacement.AFTER_TARGET
        && "repairScriptBodies".equals(captureToolOf(pin, generatorSkillId))) {
      placement =
          ChainEditNodePlacement.insertAfter(
              imported,
              scoped.targetNodeIds(),
              scoped.requestedElementType(),
              "New " + scoped.requestedElementType());
    } else {
      return new PlacementAndIntent(imported, scoped);
    }
    return new PlacementAndIntent(placement.graph(), scoped.withTargets(List.of(placement.newNodeId())));
  }

  private record PlacementAndIntent(ChainPlanGraph graph, ChainEditIntent intent) {}

  private static ChainEditOutcome incompleteAddition(
      ChainEditIntent intent, List<String> scopedTargets) {
    if (intent.action() != ChainEditAction.ADD_ELEMENTS
        || intent.placement() == ChainEditPlacement.ROOT_TRIGGER
        || intent.placement() == ChainEditPlacement.GENERATOR
        || !scopedTargets.isEmpty()) {
      return null;
    }
    return new ChainEditOutcome.Clarification(
        "I need one more thing before I change anything.",
        List.of("Say where to place the new element."));
  }

  private static String captureToolOf(CompilerRunPin pin, String skillId) {
    return pin.resolvedDag().nodes().stream()
        .filter(node -> skillId.equals(node.skillId()))
        .map(node -> node.captureTool())
        .findFirst()
        .orElse(null);
  }

  private static ChainEditOutcome noChangeOutcome(ChainEditIntent intent) {
    if (intent.action() != ChainEditAction.NO_CHANGE) {
      return null;
    }
    return new ChainEditOutcome.ResolutionFailure(
        "No change was requested. Say what should be different.");
  }

  private static boolean deterministic(ChainEditAction action) {
    return action == ChainEditAction.DELETE
        || action == ChainEditAction.DISCONNECT
        || action == ChainEditAction.REORDER;
  }

  /**
   * The edits the platform already knows how to make, made without a model or a compiler run.
   *
   * <p>The removal closure runs here rather than in the caller, so the net patch a reader approves
   * already names every descendant, dependency and connection the catalog will take with it.
   */
  private ChainEditOutcome transform(ChainEditRequest request, ChainEditIntent intent) {
    ChainPlanGraph base = request.imported().graph();
    GraphPatch requested =
        switch (intent.action()) {
          case DELETE -> ChainEditDeterministicTransforms.delete(intent.targetNodeIds());
          case DISCONNECT ->
              ChainEditDeterministicTransforms.disconnect(base, intent.targetNodeIds());
          default -> ChainEditDeterministicTransforms.reorder(intent.targetNodeIds());
        };
    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(base, requested);
    if (!expansion.coherent()) {
      return new ChainEditOutcome.ResolutionFailure(
          "The change contradicts itself: " + String.join("; ", expansion.conflicts()));
    }
    GraphPatchApplyResult applied = new GraphPatchApplier().apply(base, expansion.patch());
    if (!applied.applied()) {
      return new ChainEditOutcome.CompilationFailure(
          "The change could not be applied: " + applied.validationResult().summary());
    }
    if (CanonicalGraphDiff.isEmpty(expansion.patch())) {
      return new ChainEditOutcome.ResolutionFailure("That request changes nothing in the chain.");
    }
    return new ChainEditOutcome.Proposal(
        expansion.patch(),
        base,
        applied.graph(),
        intent,
        List.of(),
        List.of(ChainEditDeterministicTransforms.class.getSimpleName()),
        null);
  }

  @SuppressWarnings("java:S107")
  private ChainEditOutcome runCompiler(
      ChainEditRequest request,
      ChainEditIntent intent,
      List<ResolvedServiceCallBinding> bindings,
      List<GeneratorPlan> generatorPlans,
      List<String> approvedSkillIds,
      Set<String> extraPreSatisfiedSkillIds,
      List<String> executedPrefix,
      CompilerRunPin pin,
      ChainPlanGraph seedGraph) {
    ImportedChainPlan imported = request.imported();
    String runId = request.editRunId();
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forEdit(
                runId,
                request.userRequest(),
                seedGraph,
                imported.materializationMap(),
                intent,
                bindings,
                extraPreSatisfiedSkillIds)
            .with(targetScopedPlan(generatorPlans));

    ResolvedCompilerDag dag;
    try {
      dag =
          ChainEditCompilerDag.cut(
              pin.resolvedDag(), Set.copyOf(approvedSkillIds), seed.presentArtifactTypes());
    } catch (RuntimeException e) {
      return new ChainEditOutcome.CompilationFailure(
          "The pinned compiler package is missing an owner required for this edit: "
              + e.getMessage());
    }
    RunManifest manifest =
        ChainEditCompilerDag.pinnedManifest(baseManifest(request, pin), runId, dag);

    CompilerDagExecutionResult result;
    try {
      result =
          engine
              .execute(
                  new CompilerDagExecutionRequest(
                      runId,
                      request.conversationId(),
                      manifest,
                      null,
                      null,
                      dag,
                      approvedSkillIds,
                      List.of(),
                      List.of(),
                      seed),
                  (skillId, status) -> {})
              .await()
              .indefinitely();
    } catch (RuntimeException e) {
      LOG.errorf(e, "Chain edit compilation failed runId=%s", runId);
      return new ChainEditOutcome.CompilationFailure(describeFailure(e));
    }

    if (result.graph() == null) {
      return new ChainEditOutcome.CompilationFailure("The compiler produced no graph.");
    }
    if (result.validationBundle() == null || !result.validationBundle().approvalEligible()) {
      return new ChainEditOutcome.CompilationFailure(
          "The compiled chain did not pass validation, so there is nothing to approve.");
    }

    GraphPatch netPatch =
        CanonicalGraphDiff.between(
            imported.graph(),
            result.graph(),
            "chain-edit-" + runId,
            String.join("+", approvedSkillIds),
            intent.requestedChange());
    if (CanonicalGraphDiff.isEmpty(netPatch)) {
      return new ChainEditOutcome.ResolutionFailure(
          "Compiling that request changed nothing in the chain.");
    }
    // Checked after the run, not before it: a package that changed underneath a running compilation
    // would otherwise produce a proposal pinned to content the runtime no longer has.
    try {
      runPinResolver.verifyAvailable(manifest);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Compiler pin mismatch runId=%s", runId);
      return new ChainEditOutcome.CompilationFailure(
          "The compiler package changed while this edit was compiling, so there is nothing to"
              + " approve. Ask for the change again.");
    }
    List<String> executedSkillIds = new ArrayList<>(executedPrefix);
    for (String skillId : result.executedSkillIds()) {
      if (!executedSkillIds.contains(skillId)) {
        executedSkillIds.add(skillId);
      }
    }
    return new ChainEditOutcome.Proposal(
        netPatch,
        imported.graph(),
        result.graph(),
        intent,
        bindings,
        List.copyOf(executedSkillIds),
        manifest);
  }

  /**
   * The generator plan the run executes against: this skill, these node ids. Without the scope, a
   * readiness signal such as "there is a service call here" selects every service call in the
   * chain, and a request about one element rewrites all of them.
   */
  private static GeneratorPlan generatorPlan(
      String generatorSkillId, ChainEditIntent intent) {
    return new GeneratorPlan(
        generatorSkillId,
        generatorSkillId,
        GeneratorPlanStatus.READY,
        List.of(intent.action().name()),
        intent.targetNodeIds());
  }

  private static SkillArtifact targetScopedPlan(List<GeneratorPlan> plans) {
    return SkillArtifact.of(
        SkillArtifactType.GENERATOR_PLAN_MANIFEST,
        "chain-edit-seed",
        new SkillArtifactPayload.GeneratorPlanManifestPayload(
            new GeneratorPlanManifest("edit", List.copyOf(plans))));
  }

  private CompilerRunPin resolvePin(String conversationId) {
    ProductPipelineProfile profile =
        profileCatalog.require(
            CreateRunSelectionService.CREATE_PROFILE_ID,
            CreateRunSelectionService.CREATE_PROFILE_VERSION);
    return runPinResolver.resolve(profile, knowledgeContextProvider.forConversation(conversationId));
  }

  private RunManifest baseManifest(ChainEditRequest request, CompilerRunPin pin) {
    KnowledgePackageRef packageRef =
        knowledgeContextProvider.forConversation(request.conversationId()).packageRef();
    return new RunManifest(
        request.editRunId(),
        null,
        List.of(),
        "product",
        CreateRunSelectionService.CREATE_PROFILE_ID,
        CreateRunSelectionService.CREATE_PROFILE_VERSION,
        CreateRunSelectionService.CREATE_PROFILE_ID
            + "@"
            + CreateRunSelectionService.CREATE_PROFILE_VERSION,
        "reference-baseline-v1",
        "reference-baseline-v1",
        List.of(),
        "closure",
        packageRef,
        request.languageVersion(),
        List.of(),
        pin);
  }

  /**
   * A compiler refusal in the reader's terms. The engine reports contract failures as exception
   * text, and "contract failure:" tells a reader nothing they can act on.
   */
  private static String describeFailure(RuntimeException e) {
    String message = e.getMessage() == null ? e.toString() : e.getMessage();
    String stripped = message.replace("contract failure: ", "");
    return "The change could not be compiled: " + stripped;
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    if (graph.nodes() == null) {
      return null;
    }
    return graph.nodes().stream()
        .filter(candidate -> candidate != null && nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElse(null);
  }
}

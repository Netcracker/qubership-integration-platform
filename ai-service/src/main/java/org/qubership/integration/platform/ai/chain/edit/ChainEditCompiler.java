package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
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
import org.qubership.integration.platform.ai.productpipeline.create.CompilerValidationPipeline;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
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
  private final DeterministicElementSchemaService schemaService;
  private final CompilerValidationPipeline validationPipeline;

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
      CaptureSession captureSession,
      DeterministicElementSchemaService schemaService,
      CompilerValidationPipeline validationPipeline) {
    this.intentResolver = Objects.requireNonNull(intentResolver, "intentResolver");
    this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.runPinResolver = Objects.requireNonNull(runPinResolver, "runPinResolver");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.knowledgeContextProvider =
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider");
    this.catalogMutationGateway = catalogMutationGateway;
    this.captureSession = Objects.requireNonNull(captureSession, "captureSession");
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
    this.validationPipeline = Objects.requireNonNull(validationPipeline, "validationPipeline");
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
    return resumeAfterImport(request, intent, refs, null);
  }

  public ChainEditOutcome resumeAfterImport(
      ChainEditRequest request,
      ChainEditIntent intent,
      ApiHubRequirementRefs refs,
      BiConsumer<String, String> skillProgress) {
    BiConsumer<String, String> progress = ChainEditSkillProgress.orNoop(skillProgress);
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
      return compile(request, intent, binding, progress);
    }
    return new ChainEditOutcome.ResolutionFailure(
        bound instanceof ServiceCallBindingOutcome.NotFound(String message)
            ? message
            : "The imported specification did not resolve to one operation.");
  }

  public ChainEditOutcome compile(ChainEditRequest request) {
    return compile(request, null);
  }

  public ChainEditOutcome compile(
      ChainEditRequest request, BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(request, "request");
    BiConsumer<String, String> progress = ChainEditSkillProgress.orNoop(skillProgress);
    ChainEditIntent intent =
        ChainEditSkillProgress.call(
            progress,
            ChainEditSkillProgress.INTENT_SKILL_ID,
            () -> intentResolver.resolve(request.imported().graph(), request.userRequest()));
    return fromResolvedIntent(request, intent, progress);
  }

  /**
   * Continues an edit whose classifier stopped to ask which element or aspect the reader meant.
   *
   * <p>The held capture and the question travel with the reply as structured input, the way an
   * approved import carries its intent forward: the classifier completes what it already
   * established rather than resolving the reply with no record of having asked. A reply that turns
   * out to be unrelated is classified on its own, and this method compiles that fresh intent
   * instead of the held one.
   */
  public ChainEditOutcome resumeAfterClarification(
      ChainEditRequest request, ChainEditIntent heldIntent, String question) {
    return resumeAfterClarification(request, heldIntent, question, null);
  }

  public ChainEditOutcome resumeAfterClarification(
      ChainEditRequest request,
      ChainEditIntent heldIntent,
      String question,
      BiConsumer<String, String> skillProgress) {
    BiConsumer<String, String> progress = ChainEditSkillProgress.orNoop(skillProgress);
    ChainEditIntent intent =
        ChainEditSkillProgress.call(
            progress,
            ChainEditSkillProgress.INTENT_SKILL_ID,
            () ->
                intentResolver.resume(
                    request.imported().graph(), heldIntent, question, request.userRequest()));
    return fromResolvedIntent(request, intent, progress);
  }

  private ChainEditOutcome fromResolvedIntent(
      ChainEditRequest request, ChainEditIntent intent, BiConsumer<String, String> progress) {
    // What the reader was understood to have asked for. Logged before anything acts on it: every
    // later refusal is judged against these targets, so reading one without them says little.
    LOG.infof(
        "Chain edit intent conversationId=%s action=%s disposition=%s targets=%s type=%s"
            + " resolved=%s ambiguities=%s",
        request.conversationId(),
        intent.action(),
        intent.disposition(),
        intent.targetNodeIds(),
        intent.requestedElementType(),
        intent.resolved(),
        intent.unresolvedAmbiguities());
    ChainEditOutcome noChange = noChangeOutcome(intent);
    if (noChange != null) {
      return noChange;
    }
    if (!intent.resolved()) {
      return new ChainEditOutcome.Clarification(
          "I need one more thing before I change anything.", intent.unresolvedAmbiguities(), intent);
    }
    return compile(request, intent, null, progress);
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
    return compile(request, intent, importedBinding, null);
  }

  public ChainEditOutcome compile(
      ChainEditRequest request,
      ChainEditIntent intent,
      ResolvedServiceCallBinding importedBinding,
      BiConsumer<String, String> skillProgress) {
    BiConsumer<String, String> progress = ChainEditSkillProgress.orNoop(skillProgress);
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

    if (intent.action() == ChainEditAction.CONFIGURE) {
      return compileConfigure(request, intent, pin, progress);
    }

      if (intent.requiresStructureStage()) {
      if (intent.disposition() != ChainEditDisposition.NEST
          && ChainEditCapabilitySelection.owningSkillId(pin.resolvedDag(), intent).isEmpty()) {
        return new ChainEditOutcome.Unsupported(intent.action());
      }
      return compileStructuralAddition(request, intent, importedBinding, pin, progress);
    }

    String generatorSkillId =
        ChainEditCapabilitySelection.owningSkillId(pin.resolvedDag(), intent).orElse(null);
    if (generatorSkillId == null) {
      return new ChainEditOutcome.Unsupported(intent.action());
    }

    List<String> scopedTargets =
        ChainEditCapabilitySelection.scopedTargets(
            pin.resolvedDag(), generatorSkillId, intent, imported.graph());
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
          return new ChainEditOutcome.Clarification(question, candidates, asHeld(scoped, candidates));
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

    PlacementAndIntent placed = placeAddition(imported.graph(), scoped);
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
        placed.graph(),
        progress);
  }

  /**
   * Routes a {@code CONFIGURE} edit by ownership metadata rather than a hand-maintained action map.
   *
   * <p>The requested property keys are checked against the target elements' schema first, so a key
   * the element type does not define is reported before any generator sees it. What is left is
   * matched against the pinned compiler package's ownership declarations the same way an addition
   * is: an unmatched key means no generator owns it, and the refusal names the element and the
   * property, never a generator the reader never mentioned.
   */
  private ChainEditOutcome compileConfigure(
      ChainEditRequest request,
      ChainEditIntent intent,
      CompilerRunPin pin,
      BiConsumer<String, String> progress) {
    ChainPlanGraph graph = request.imported().graph();
    ChainEditOutcome schemaFailure = rejectUndefinedPropertyKeys(graph, intent);
    if (schemaFailure != null) {
      return schemaFailure;
    }
    List<GeneratorPlan> plans =
        ChainEditCapabilitySelection.configureGeneratorPlans(pin.resolvedDag(), graph, intent);
    Set<String> ownedKeys = new LinkedHashSet<>();
    plans.forEach(plan -> ownedKeys.addAll(plan.matchedSignals()));
    List<String> unownedKeys =
        intent.propertyKeys().stream().filter(key -> !ownedKeys.contains(key)).toList();
    if (!unownedKeys.isEmpty()) {
      return new ChainEditOutcome.ResolutionFailure(
          "No generator owns " + describeProperties(unownedKeys) + " of "
              + describeTargets(graph, intent.targetNodeIds()) + ".");
    }
    List<String> skillIds = plans.stream().map(GeneratorPlan::skillId).toList();
    return runCompiler(
        request, intent, List.of(), plans, skillIds, Set.of(), List.of(), pin, graph, progress);
  }

  /** Refuses a property key before routing, when the target element's schema does not define it. */
  private ChainEditOutcome rejectUndefinedPropertyKeys(ChainPlanGraph graph, ChainEditIntent intent) {
    List<String> undefined = new ArrayList<>();
    for (String nodeId : intent.targetNodeIds()) {
      ChainPlanNode target = node(graph, nodeId);
      if (target == null || target.type() == null) {
        continue;
      }
      Set<String> allowed = schemaService.allowedPatchPropertyKeys(target.type());
      for (String key : intent.propertyKeys()) {
        if (!allowed.contains(key) && !undefined.contains(key)) {
          undefined.add(key);
        }
      }
    }
    if (undefined.isEmpty()) {
      return null;
    }
    return new ChainEditOutcome.ResolutionFailure(
        describeTargets(graph, intent.targetNodeIds()) + " does not define "
            + describeProperties(undefined) + ".");
  }

  private static String describeProperties(List<String> propertyKeys) {
    return propertyKeys.size() == 1
        ? "'" + propertyKeys.get(0) + "'"
        : propertyKeys.stream().map(key -> "'" + key + "'").reduce((a, b) -> a + ", " + b).orElse("");
  }

  private static String describeTargets(ChainPlanGraph graph, List<String> targetNodeIds) {
    return targetNodeIds.stream()
        .map(nodeId -> describeTarget(graph, nodeId))
        .reduce((a, b) -> a + ", " + b)
        .orElse("the requested element");
  }

  private static String describeTarget(ChainPlanGraph graph, String nodeId) {
    ChainPlanNode target = node(graph, nodeId);
    if (target == null) {
      return nodeId;
    }
    return target.label() == null || target.label().isBlank()
        ? nodeId
        : target.label() + " (" + nodeId + ")";
  }

  /**
   * Runs the shared structure stage for every addition it can produce more than a single bare
   * element: a wrap, a branch, or a subgraph spliced at an address. The stage sees the whole
   * imported graph, so a wrap's boundary and an address's pair of elements are both just nodes it
   * reads off {@code intent}, not a distinction this method has to make.
   */
  private ChainEditOutcome compileStructuralAddition(
      ChainEditRequest request,
      ChainEditIntent intent,
      ResolvedServiceCallBinding importedBinding,
      CompilerRunPin pin,
      BiConsumer<String, String> progress) {
    ImportedChainPlan imported = request.imported();
    List<ResolvedServiceCallBinding> bindings =
        importedBinding == null ? List.of() : List.of(importedBinding);
    CompilerDagExecutionResult structureResult;
    try {
      structureResult = runStructureStage(request, intent, bindings, pin, progress);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Chain edit structure generation failed runId=%s", request.editRunId());
      return new ChainEditOutcome.CompilationFailure(describeFailure(e));
    }
    if (structureResult.graph() == null) {
      return new ChainEditOutcome.CompilationFailure("The structure stage produced no graph.");
    }

    ChainPlanGraph structured = structureResult.graph();
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
        structured,
        progress);
  }

  private CompilerDagExecutionResult runStructureStage(
      ChainEditRequest request,
      ChainEditIntent intent,
      List<ResolvedServiceCallBinding> bindings,
      CompilerRunPin pin,
      BiConsumer<String, String> progress) {
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
              progress)
          .await()
          .indefinitely();
    } finally {
      captureSession.clear(structureBaseKey);
    }
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
   * Places a root trigger, the one addition that needs neither an address nor the structure stage.
   *
   * <p>Every other addition -- a wrap, a branch, or an insertion at an address -- goes through
   * {@link #compileStructuralAddition}, which always runs the shared structure stage first.
   */
  private static PlacementAndIntent placeAddition(ChainPlanGraph imported, ChainEditIntent scoped) {
    if (scoped.action() != ChainEditAction.ADD_ELEMENTS || !scoped.isRootTrigger()) {
      return new PlacementAndIntent(imported, scoped);
    }
    ChainEditNodePlacement.Placement placement =
        ChainEditNodePlacement.addTrigger(
            imported,
            scoped.targetNodeIds(),
            scoped.requestedElementType(),
            "New " + scoped.requestedElementType());
    return new PlacementAndIntent(placement.graph(), scoped.withTargets(List.of(placement.newNodeId())));
  }

  private record PlacementAndIntent(ChainPlanGraph graph, ChainEditIntent intent) {}

  /** A copy of {@code intent} held for the next turn, carrying the question's own ambiguities. */
  private static ChainEditIntent asHeld(ChainEditIntent intent, List<String> ambiguities) {
    return new ChainEditIntent(
        intent.action(),
        intent.targetNodeIds(),
        intent.requestedChange(),
        intent.externalBindingQuery(),
        intent.requestedElementType(),
        intent.cronExpression(),
        intent.propertyKeys(),
        ambiguities,
        intent.disposition());
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
      ChainPlanGraph seedGraph,
      BiConsumer<String, String> progress) {
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
                  progress)
              .await()
              .indefinitely();
    } catch (RuntimeException e) {
      LOG.errorf(e, "Chain edit compilation failed runId=%s", runId);
      return new ChainEditOutcome.CompilationFailure(describeFailure(e));
    }

    if (result.graph() == null) {
      return new ChainEditOutcome.CompilationFailure("The compiler produced no graph.");
    }
    if (!ChainEditValidationEligibility.approvalEligible(
        imported.graph(), result.validationBundle(), validationPipeline)) {
      return new ChainEditOutcome.CompilationFailure(
          ChainEditValidationEligibility.failureMessage(
              imported.graph(), result.validationBundle(), validationPipeline));
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

package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
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
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * Prepares one edit of an existing chain, and never writes to the catalog.
 *
 * <p>An edit is a compilation whose starting graph is the imported chain. The reader's words are
 * resolved into a typed intent, anything the request names outside the chain is resolved against
 * the catalog, and the owning generator then configures the element through the same skill
 * document, addon and knowledge package CREATE uses. What comes back is diffed against the imported
 * graph, so the reader approves one change rather than a replay of the generators' working.
 *
 * <p>Every branch that is not a proposal says why: an ambiguous target or operation asks, a missing
 * one reports it plainly, and a compiler refusal names the refusal. None of them invent a catalog
 * identity to keep going.
 */
@ApplicationScoped
public class ChainEditCompiler {

  private static final Logger LOG = Logger.getLogger(ChainEditCompiler.class);

  private final ChainEditIntentResolver intentResolver;
  private final ServiceCallBindingResolver bindingResolver;
  private final CompilerDagExecutionEngine engine;
  private final CompilerRunPinResolver runPinResolver;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final KnowledgeContextProvider knowledgeContextProvider;
  private final CatalogMutationGateway catalogMutationGateway;

  @Inject
  @SuppressWarnings("java:S107")
  public ChainEditCompiler(
      ChainEditIntentResolver intentResolver,
      ServiceCallBindingResolver bindingResolver,
      CompilerDagExecutionEngine engine,
      CompilerRunPinResolver runPinResolver,
      ProductPipelineProfileCatalog profileCatalog,
      KnowledgeContextProvider knowledgeContextProvider,
      CatalogMutationGateway catalogMutationGateway) {
    this.intentResolver = Objects.requireNonNull(intentResolver, "intentResolver");
    this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.runPinResolver = Objects.requireNonNull(runPinResolver, "runPinResolver");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.knowledgeContextProvider =
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider");
    this.catalogMutationGateway = catalogMutationGateway;
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
    CompilerRunPin pin;
    try {
      pin = resolvePin(request.conversationId());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Compiler pin unavailable conversationId=%s", request.conversationId());
      return new ChainEditOutcome.CompilationFailure(
          "The compiler package this edit needs is unavailable: " + e.getMessage());
    }

    String generatorSkillId =
        ChainEditCapabilitySelection.owningSkillId(pin.resolvedDag(), intent).orElse(null);
    if (generatorSkillId == null) {
      return new ChainEditOutcome.Unsupported(intent.action());
    }

    List<String> scopedTargets =
        ChainEditCapabilitySelection.scopedTargets(
            pin.resolvedDag(), generatorSkillId, intent, imported.graph());
    if (scopedTargets.isEmpty()) {
      return new ChainEditOutcome.ResolutionFailure(
          "No element in that request is one " + generatorSkillId + " may change.");
    }
    ChainEditIntent scoped =
        new ChainEditIntent(
            intent.action(),
            scopedTargets,
            intent.requestedChange(),
            intent.externalBindingQuery(),
            intent.requestedElementType(),
            List.of());

    List<ResolvedServiceCallBinding> bindings;
    if (importedBinding != null) {
      bindings = List.of(importedBinding);
    } else if (scoped.action() == ChainEditAction.REBIND_SERVICE_CALL) {
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

    return runCompiler(request, scoped, bindings, generatorSkillId, pin);
  }

  @SuppressWarnings("java:S107")
  private ChainEditOutcome runCompiler(
      ChainEditRequest request,
      ChainEditIntent intent,
      List<ResolvedServiceCallBinding> bindings,
      String generatorSkillId,
      CompilerRunPin pin) {
    ImportedChainPlan imported = request.imported();
    String runId = request.editRunId();
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forEdit(
                runId,
                request.userRequest(),
                imported.graph(),
                imported.materializationMap(),
                intent,
                bindings,
                Set.of())
            .with(targetScopedPlan(generatorSkillId, intent));

    ResolvedCompilerDag dag;
    try {
      dag =
          ChainEditCompilerDag.cut(
              pin.resolvedDag(), Set.of(generatorSkillId), seed.presentArtifactTypes());
    } catch (RuntimeException e) {
      return new ChainEditOutcome.CompilationFailure(
          "The pinned compiler package has no " + generatorSkillId + " to run this edit through.");
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
                      List.of(generatorSkillId),
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
            generatorSkillId,
            intent.requestedChange());
    if (CanonicalGraphDiff.isEmpty(netPatch)) {
      return new ChainEditOutcome.ResolutionFailure(
          "Compiling that request changed nothing in the chain.");
    }
    return new ChainEditOutcome.Proposal(
        netPatch,
        imported.graph(),
        result.graph(),
        intent,
        bindings,
        result.executedSkillIds(),
        manifest);
  }

  /**
   * The generator plan the run executes against: this skill, these node ids. Without the scope, a
   * readiness signal such as "there is a service call here" selects every service call in the
   * chain, and a request about one element rewrites all of them.
   */
  private static SkillArtifact targetScopedPlan(String generatorSkillId, ChainEditIntent intent) {
    return SkillArtifact.of(
        SkillArtifactType.GENERATOR_PLAN_MANIFEST,
        "chain-edit-seed",
        new SkillArtifactPayload.GeneratorPlanManifestPayload(
            new GeneratorPlanManifest(
                "edit",
                List.of(
                    new GeneratorPlan(
                        generatorSkillId,
                        generatorSkillId,
                        GeneratorPlanStatus.READY,
                        List.of(intent.action().name()),
                        intent.targetNodeIds())))));
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

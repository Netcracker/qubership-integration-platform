package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.llm.agent.ChainEditIntentAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/** Primary-seam tests: an imported chain and a request in, a typed outcome out, no catalog write. */
class ChainEditCompilerTest {

  private static final String TARGET = "call-orders";
  private static final String UNRELATED = "call-invoices";
  private static final String GENERATOR = "cip-service-call-generator";
  private static final String STRUCTURE_GENERATOR = "cip-structure-generator";
  private static final String ERROR_HANDLING_GENERATOR = "cip-error-handling-generator";
  private static final String SCRIPT_GENERATOR = "cip-script-generator";

  private ChainEditCapture intentReply;
  private CatalogRestClient catalogRestClient;
  private CatalogSystemReadTool readTool;
  private ApiHubMcpTools apiHub;
  private CatalogMutationGateway catalogMutationGateway;
  private FakeEngine engine;
  private ChainEditCompiler compiler;

  @BeforeEach
  void setUp() {
    intentReply =
        capture(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(TARGET),
            "point it at the order-status operation",
            "order status");
    catalogRestClient = mock(CatalogRestClient.class);
    readTool = mock(CatalogSystemReadTool.class);
    engine = new FakeEngine();

    when(catalogRestClient.getOperation("op-old"))
        .thenReturn(new CatalogRestClient.OperationDto("op-old", "Get order", "GET", "/orders", "spec-1"));
    when(catalogRestClient.getModel("spec-1"))
        .thenReturn(new CatalogRestClient.SpecificationDto("spec-1", "Orders API", "group-1", "sys-1"));
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "Orders", "EXTERNAL", "HTTP"));
    when(readTool.listCatalogOperations(eq("spec-1"), eq("sys-1"), any()))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1")));

    CompilerRunPinResolver runPinResolver = mock(CompilerRunPinResolver.class);
    when(runPinResolver.resolve(any(), any())).thenReturn(pin());
    ProductPipelineProfileCatalog profileCatalog = mock(ProductPipelineProfileCatalog.class);
    when(profileCatalog.require(any(), any())).thenReturn(mock(ProductPipelineProfile.class));
    KnowledgeContextProvider knowledge =
        conversationId ->
            new KnowledgeQueryContext(
                new KnowledgePackageRef(
                    "artifact", "1.0.0", "1.0.0", "checksum", "CERTIFIED", "sha256:cert"));

    apiHub = mock(ApiHubMcpTools.class);
    catalogMutationGateway = mock(CatalogMutationGateway.class);
    ChainEditIntentAgent agent = (elements, userRequest) -> intentReply;
    compiler =
        new ChainEditCompiler(
            new ChainEditIntentResolver(agent),
            new ServiceCallBindingResolver(catalogRestClient, readTool, apiHub),
            engine,
            runPinResolver,
            profileCatalog,
            knowledge,
            catalogMutationGateway,
            new CaptureSession());
  }

  @Test
  void anExactTargetAndAnExactOperationProduceOneValidatedProposal() {
    ChainEditOutcome outcome = compiler.compile(request());

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, outcome);
    assertEquals(List.of(GENERATOR, "cip-chain-assembler"), proposal.executedSkillIds());
    assertNotNull(proposal.runManifest().compilerRunPin());
    // The net patch carries what actually changed. The open failure this path replaces wrote the
    // operation id alone and left the method and path describing the previous operation.
    assertEquals(
        Map.of(
            "integrationOperationId", "op-status",
            "integrationOperationMethod", "POST",
            "integrationOperationPath", "/orders/{id}/status"),
        changedProperties(proposal.netPatch(), TARGET));
    // The element the reader approves describes one operation completely.
    assertEquals(
        Map.of(
            "integrationOperationId", "op-status",
            "integrationOperationMethod", "POST",
            "integrationOperationPath", "/orders/{id}/status",
            "integrationOperationProtocolType", "HTTP",
            "integrationSystemId", "sys-1",
            "integrationSpecificationId", "spec-1",
            "integrationSpecificationGroupId", "group-1",
            "systemType", "EXTERNAL",
            "retryCount", "3"),
        properties(node(proposal.finalGraph(), TARGET)));
  }

  private static Map<String, String> properties(ChainPlanNode node) {
    Map<String, String> values = new java.util.LinkedHashMap<>();
    for (PlanProperty property : node.properties()) {
      values.put(property.key(), property.value());
    }
    return values;
  }

  @Test
  void theResolvedBindingKeepsEveryCatalogFieldAsTypedData() {
    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ResolvedServiceCallBinding binding = proposal.bindings().get(0);
    assertEquals(TARGET, binding.targetNodeId());
    assertEquals("EXTERNAL", binding.systemType());
    assertEquals("sys-1", binding.systemId());
    assertEquals("group-1", binding.specificationGroupId());
    assertEquals("spec-1", binding.specificationId());
    assertEquals("op-status", binding.operationId());
    assertEquals("HTTP", binding.protocolType());
    assertEquals("POST", binding.method());
    assertEquals("/orders/{id}/status", binding.path());
    assertEquals("Post order status", binding.displayName());
    assertEquals(ResolvedServiceCallBinding.Source.EXISTING_CATALOG, binding.source());
    assertEquals("catalog:/v1/operations/op-status", binding.evidenceRef());
  }

  @Test
  void theGeneratorReceivesTheGraphTheExactTargetAndTheCompleteBinding() {
    compiler.compile(request());

    CompilerDagExecutionRequest seen = engine.lastRequest.get();
    assertEquals(
        importedGraph(),
        artifact(seen, SkillArtifactType.CHAIN_PLAN_GRAPH, SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph());
    GeneratorPlanManifest plan =
        artifact(
                seen,
                SkillArtifactType.GENERATOR_PLAN_MANIFEST,
                SkillArtifactPayload.GeneratorPlanManifestPayload.class)
            .manifest();
    assertEquals(List.of(GENERATOR), plan.plans().stream().map(p -> p.skillId()).toList());
    assertEquals(List.of(TARGET), plan.plans().get(0).targetNodeIds());
    assertEquals(
        "op-status",
        artifact(
                seen,
                SkillArtifactType.SERVICE_CALL_BINDINGS,
                SkillArtifactPayload.ServiceCallBindingsPayload.class)
            .bindings()
            .get(0)
            .operationId());
    assertEquals(List.of(GENERATOR), seen.approvedOwningSkillIds());
  }

  @Test
  void aChainWithSeveralServiceCallsChangesOnlyTheRequestedOne() {
    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertTrue(
        proposal.netPatch().propertyPatches().stream()
            .allMatch(patch -> TARGET.equals(patch.targetNodeId())));
    assertTrue(proposal.netPatch().nodePatches().isEmpty());
    assertTrue(proposal.netPatch().edgePatches().isEmpty());
    assertEquals(
        node(importedGraph(), UNRELATED), node(proposal.finalGraph(), UNRELATED));
    assertEquals(importedGraph().edges(), proposal.finalGraph().edges());
  }

  @Test
  void anAmbiguousTargetAsksAndProposesNoPatch() {
    intentReply =
        capture(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "point a service call somewhere else",
            null,
            null,
            null,
            ChainEditPlacement.UNSET,
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, compiler.compile(request()));
    assertEquals(
        List.of("call-orders (Call orders)", "call-invoices (Call invoices)"),
        clarification.choices());
  }

  @Test
  void anAmbiguousOperationOffersRecognizableChoicesAndNoPatch() {
    when(readTool.listCatalogOperations(eq("spec-1"), eq("sys-1"), any()))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1"),
                new CatalogRestClient.OperationDto(
                    "op-history", "Get order history", "GET", "/orders/{id}/history", "spec-1")));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, compiler.compile(request()));
    assertEquals(
        List.of(
            "Post order status (POST /orders/{id}/status) in Orders",
            "Get order history (GET /orders/{id}/history) in Orders"),
        clarification.choices());
  }

  @Test
  void aMissingOperationInventsNoCatalogIdentity() {
    when(readTool.listCatalogOperations(eq("spec-1"), eq("sys-1"), any())).thenReturn(List.of());
    when(readTool.searchCatalogSystems(any())).thenReturn(List.of());

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));
    assertTrue(failure.message().contains("No operation in the local catalog"));
  }

  @Test
  void aCompilerValidationFailureLeavesNothingToApprove() {
    engine.validationValid = false;

    ChainEditOutcome outcome = compiler.compile(request());
    assertInstanceOf(ChainEditOutcome.CompilationFailure.class, outcome);
  }

  @Test
  void anOwnershipRefusalLeavesNothingToApprove() {
    engine.failure =
        new IllegalStateException("contract failure: property 'retryCount' is not owned");

    ChainEditOutcome.CompilationFailure failure =
        assertInstanceOf(ChainEditOutcome.CompilationFailure.class, compiler.compile(request()));
    assertFalse(failure.message().contains("contract failure"));
    assertTrue(failure.message().contains("not owned"));
  }

  @Test
  void areorderIsMadeDeterministicallyWithoutRunningTheCompiler() {
    intentReply =
        capture(ChainEditAction.REORDER, List.of(UNRELATED, TARGET), "put the invoice call first");

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertEquals(
        Map.of("priority", "0"), changedProperties(proposal.netPatch(), UNRELATED));
    assertEquals(Map.of("priority", "1"), changedProperties(proposal.netPatch(), TARGET));
    org.junit.jupiter.api.Assertions.assertNull(
        engine.lastRequest.get(), "a deterministic edit runs no compiler DAG");
  }

  @Test
  void aDeletionCarriesTheCatalogCascadeIntoTheNetPatch() {
    intentReply = capture(ChainEditAction.DELETE, List.of(TARGET), "remove the order call");

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertEquals(
        List.of(TARGET),
        proposal.netPatch().nodePatches().stream()
            .map(patch -> patch.targetNodeId())
            .toList());
    assertTrue(
        proposal.netPatch().edgePatches().stream()
            .anyMatch(patch -> "edge-1".equals(patch.targetEdgeId())),
        "the connection out of the deleted element goes too");
  }

  @Test
  void aScriptSkillIsNotOfferedAnElementItCannotOwn() {
    intentReply = capture(ChainEditAction.EDIT_SCRIPT, List.of(TARGET), "rewrite the script");

    assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));
  }

  @Test
  void compilingChangesNothingInTheCatalog() {
    compiler.compile(request());

    org.mockito.Mockito.verify(catalogRestClient, org.mockito.Mockito.never())
        .updateElement(any(), any(), any());
  }

  @Test
  void aScriptEditRunsThroughTheScriptSkill() {
    intentReply =
        capture(ChainEditAction.EDIT_SCRIPT, List.of("normalize"), "return the customer id in the body");

    assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));
    assertEquals(
        List.of("cip-script-generator"), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(
        List.of("normalize"), scopedTargets(engine.lastRequest.get()));
  }

  @Test
  void eachConfigurationFamilyGoesToTheCapabilityThatOwnsIt() {
    intentReply =
        capture(ChainEditAction.EDIT_AUTHENTICATION, List.of(TARGET), "use the service account");
    compiler.compile(request());
    assertEquals(List.of("cip-auth-generator"), engine.lastRequest.get().approvedOwningSkillIds());

    intentReply = capture(ChainEditAction.EDIT_RETRY, List.of(TARGET), "try five times");
    compiler.compile(request());
    assertEquals(List.of("cip-retry-generator"), engine.lastRequest.get().approvedOwningSkillIds());
  }

  @Test
  void aTargetTheCapabilityCannotOwnIsRefusedBeforeTheCompilerRuns() {
    // The timeout skill owns connectTimeout on http-trigger, and this target is a service call.
    intentReply = capture(ChainEditAction.EDIT_TIMEOUT, List.of(TARGET), "wait longer");

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));
    assertTrue(failure.message().contains("cip-timeout-generator"), failure.message());
  }

  @Test
  void noChangeDoesNotCompileAndDoesNotThrow() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.NO_CHANGE,
            List.of(),
            "No changes requested.",
            null,
            null,
            null,
            ChainEditPlacement.UNSET,
            List.of());

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));
    assertTrue(failure.message().contains("No change was requested"), failure.message());
  }

  @Test
  void addingAQuartzSchedulerDoesNotAskWhichElementToChange() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(),
            "add a quartz-scheduler that starts every 5 minutes",
            null,
            "quartz-scheduler",
            "0 */5 * * * ?",
            ChainEditPlacement.ROOT_TRIGGER,
            List.of());

    ChainEditOutcome outcome =
        compiler.compile(
            new ChainEditRequest(
                "conv-1",
                "chain-1",
                "edit-run-1",
                new ImportedChainPlan(importedGraph(), null, "base-digest"),
                "schedule this every 5 minutes",
                null));

    assertFalse(
        outcome instanceof ChainEditOutcome.Clarification,
        () ->
            outcome instanceof ChainEditOutcome.Clarification clarification
                ? clarification.question() + " " + clarification.choices()
                : outcome.getClass().getSimpleName());
    assertEquals(
        List.of("cip-quartz-scheduler-generator"),
        engine.lastRequest.get().approvedOwningSkillIds());
    ChainPlanGraph seed =
        artifact(
                engine.lastRequest.get(),
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph();
    assertTrue(
        seed.nodes().stream().anyMatch(node -> "quartz-scheduler".equals(node.type())),
        "the seed graph must already carry the new quartz-scheduler");
    assertTrue(
        scopedTargets(engine.lastRequest.get()).stream()
            .anyMatch(
                nodeId ->
                    seed.nodes().stream()
                        .anyMatch(
                            node ->
                                nodeId.equals(node.nodeId())
                                    && "quartz-scheduler".equals(node.type()))),
        "the quartz generator must target the new scheduler, not an existing element");
    assertEquals(
        "0 */5 * * * ?",
        artifact(
                engine.lastRequest.get(),
                SkillArtifactType.CHAIN_EDIT_INTENT,
                SkillArtifactPayload.ChainEditIntentPayload.class)
            .intent()
            .cronExpression());
  }

  @Test
  void anAdditionGoesToWhicheverSkillMayAddThatElementType() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of("call-orders"),
            "add a script after the order call",
            null,
            "script",
            null,
            ChainEditPlacement.AFTER_TARGET,
            List.of());

    compiler.compile(request());

    assertEquals(
        List.of("cip-script-generator"), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of(TARGET), scopedTargets(engine.lastRequest.get()));
  }

  @Test
  void aCompoundStructuralAdditionRunsStructureBeforeEveryConfigurationOwner() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of("normalize"),
            "wrap the script with error handling and return an error response from catch",
            null,
            "try-catch-finally-2",
            null,
            ChainEditPlacement.GENERATOR,
            List.of());

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertEquals(2, engine.requests.size());
    assertEquals(
        List.of(STRUCTURE_GENERATOR), engine.requests.get(0).approvedOwningSkillIds());
    assertFalse(
        engine.requests.get(0).seed().preSatisfiedSkillIds().contains(STRUCTURE_GENERATOR));
    assertEquals(
        List.of(STRUCTURE_GENERATOR, ERROR_HANDLING_GENERATOR, SCRIPT_GENERATOR),
        engine.requests.get(1).approvedOwningSkillIds());
    assertTrue(
        engine.requests.get(1).seed().preSatisfiedSkillIds().contains(STRUCTURE_GENERATOR));
    assertEquals(
        List.of(
            STRUCTURE_GENERATOR,
            ERROR_HANDLING_GENERATOR,
            SCRIPT_GENERATOR,
            "cip-chain-assembler"),
        proposal.executedSkillIds());
    assertEquals("try-shell", node(proposal.finalGraph(), "normalize").parentNodeId());
    assertEquals(
        List.of("catch-shell"),
        scopedTargets(engine.requests.get(1), ERROR_HANDLING_GENERATOR));
    assertEquals(
        List.of("catch-response"),
        scopedTargets(engine.requests.get(1), SCRIPT_GENERATOR));
  }

  @Test
  void anAdditionOfATypeNoSkillMayAddFallsBackToTheCaller() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of("call-orders"),
            "add a mainframe bridge",
            null,
            "mainframe-bridge",
            null,
            ChainEditPlacement.AFTER_TARGET,
            List.of());

    assertInstanceOf(ChainEditOutcome.Unsupported.class, compiler.compile(request()));
  }

  @Test
  void aTimeoutCaptureWithoutATargetAsksWhichElementEvenWhenTheUserSaidAdd() {
    intentReply =
        capture(
            ChainEditAction.EDIT_TIMEOUT,
            List.of(),
            "wait longer",
            null,
            null,
            null,
            ChainEditPlacement.UNSET,
            List.of("http-a", "http-b"));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(
            ChainEditOutcome.Clarification.class,
            compiler.compile(
                new ChainEditRequest(
                    "conv-1",
                    "chain-1",
                    "edit-run-1",
                    new ImportedChainPlan(twoHttpTriggers(), null, "base-digest"),
                    "add quartz-scheduler every 5 minutes",
                    null)));

    assertEquals(List.of("http-a", "http-b"), clarification.choices());
  }

  @Test
  void anOperationOnlyApiHubHasAsksBeforeItImportsAnything() {
    localCatalogHasNothing();
    when(apiHub.searchApiOperations(any(), any(), any(), any(), any(), any()))
        .thenReturn(apiHubHit());

    ChainEditOutcome.Escalation escalation =
        assertInstanceOf(ChainEditOutcome.Escalation.class, compiler.compile(request()));

    assertEquals("pkg-1", escalation.refs().packageId());
    assertEquals("2026.1", escalation.refs().version());
    assertEquals(ChainEditAction.REBIND_SERVICE_CALL, escalation.intent().action());
    org.mockito.Mockito.verifyNoInteractions(catalogMutationGateway);
  }

  @Test
  void anApprovedImportResumesTheSameEditWithAnApiHubBinding() {
    when(catalogMutationGateway.importApiHubSpecification(any(), any()))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new ApiHubSpecificationImportResult(
                        "sys-1",
                        "spec-1",
                        "group-1",
                        "import-7",
                        "Orders API",
                        java.util.Optional.of("op-status"))));
    when(catalogRestClient.getOperation("op-status"))
        .thenReturn(
            new CatalogRestClient.OperationDto(
                "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1"));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.resumeAfterImport(request(), rebindIntent(), refs()));

    ResolvedServiceCallBinding binding = proposal.bindings().get(0);
    assertEquals(ResolvedServiceCallBinding.Source.APIHUB_IMPORT, binding.source());
    assertEquals("2026.1", binding.release());
    assertEquals("apihub-import:import-7", binding.evidenceRef());
    assertEquals("POST", binding.method());
    assertEquals("/orders/{id}/status", binding.path());
  }

  @Test
  void anImportThatNamesNoOperationChangesNothing() {
    when(catalogMutationGateway.importApiHubSpecification(any(), any()))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new ApiHubSpecificationImportResult(
                        "sys-1", "spec-1", "group-1", "import-7", "Orders API", java.util.Optional.empty())));

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(
            ChainEditOutcome.ResolutionFailure.class,
            compiler.resumeAfterImport(request(), rebindIntent(), refs()));
    assertTrue(failure.message().contains("without naming an operation"), failure.message());
  }

  @Test
  void thesameSeedAndPinnedInputsProduceTheSameProposal() {
    ChainEditOutcome.Proposal first =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));
    ChainEditOutcome.Proposal second =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertEquals(first.finalGraph(), second.finalGraph());
    assertEquals(first.netPatch().propertyPatches(), second.netPatch().propertyPatches());
    assertEquals(
        first.runManifest().compilerRunPin().resolvedDag().digest(),
        second.runManifest().compilerRunPin().resolvedDag().digest());
  }

  private void localCatalogHasNothing() {
    when(readTool.listCatalogOperations(any(), any(), any())).thenReturn(List.of());
    when(readTool.searchCatalogSystems(any())).thenReturn(List.of());
  }

  private static String apiHubHit() {
    return """
        [{"operationId":"post-order-status","packageId":"pkg-1","version":"2026.1",\
"documentId":"doc-1","title":"Post order status"}]
        """;
  }

  private static ApiHubRequirementRefs refs() {
    return new ApiHubRequirementRefs(
        "pkg-1", "2026.1", "post-order-status", "doc-1", "rest", "Orders", "Orders API");
  }

  private static ChainEditIntent rebindIntent() {
    return new ChainEditIntent(
        ChainEditAction.REBIND_SERVICE_CALL,
        List.of(TARGET),
        "point it at the order-status operation",
        "order status",
        List.of());
  }

  private static ChainEditCapture capture(
      ChainEditAction action, List<String> targets, String change) {
    return capture(action, targets, change, null);
  }

  private static ChainEditCapture capture(
      ChainEditAction action, List<String> targets, String change, String lookup) {
    return capture(
        action, targets, change, lookup, null, null, ChainEditPlacement.UNSET, List.of());
  }

  private static ChainEditCapture capture(
      ChainEditAction action,
      List<String> targets,
      String change,
      String lookup,
      String elementType,
      String cron,
      ChainEditPlacement placement,
      List<String> ambiguities) {
    return new ChainEditCapture(
        action, targets, change, lookup, elementType, cron, placement, ambiguities);
  }

  private static List<String> scopedTargets(CompilerDagExecutionRequest request) {
    return scopedTargets(request, request.approvedOwningSkillIds().get(0));
  }

  private static List<String> scopedTargets(
      CompilerDagExecutionRequest request, String skillId) {
    return artifact(
            request,
            SkillArtifactType.GENERATOR_PLAN_MANIFEST,
            SkillArtifactPayload.GeneratorPlanManifestPayload.class)
        .manifest()
        .plans()
        .stream()
        .filter(plan -> skillId.equals(plan.skillId()))
        .findFirst()
        .orElseThrow()
        .targetNodeIds();
  }

  private static Map<String, String> changedProperties(GraphPatch patch, String nodeId) {
    Map<String, String> changed = new java.util.LinkedHashMap<>();
    for (PropertyPatch propertyPatch : patch.propertyPatches()) {
      if (nodeId.equals(propertyPatch.targetNodeId())
          && propertyPatch.operation() != GraphPatchOperation.REMOVE) {
        changed.put(propertyPatch.property().key(), propertyPatch.property().value());
      }
    }
    return changed;
  }

  private static <T> T artifact(
      CompilerDagExecutionRequest request, SkillArtifactType type, Class<T> payloadType) {
    for (SkillArtifact artifact : request.seed().artifacts()) {
      if (artifact.type() == type) {
        return payloadType.cast(artifact.payload());
      }
    }
    throw new AssertionError("seed has no " + type);
  }

  private static ChainEditRequest request() {
    return new ChainEditRequest(
        "conv-1",
        "chain-1",
        "edit-run-1",
        new ImportedChainPlan(importedGraph(), null, "base-digest"),
        "point the order call at the order-status operation",
        null);
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream().filter(n -> nodeId.equals(n.nodeId())).findFirst().orElseThrow();
  }

  private static ChainPlanGraph importedGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode(
                TARGET,
                "service-call",
                "Call orders",
                null,
                null,
                List.of(
                    new PlanProperty("integrationOperationId", "op-old"),
                    new PlanProperty("integrationOperationMethod", "GET"),
                    new PlanProperty("integrationOperationPath", "/orders"),
                    new PlanProperty("integrationOperationProtocolType", "HTTP"),
                    new PlanProperty("integrationSystemId", "sys-1"),
                    new PlanProperty("integrationSpecificationId", "spec-1"),
                    new PlanProperty("integrationSpecificationGroupId", "group-1"),
                    new PlanProperty("systemType", "EXTERNAL"),
                    new PlanProperty("retryCount", "3"))),
            new ChainPlanNode(
                UNRELATED,
                "service-call",
                "Call invoices",
                null,
                null,
                List.of(new PlanProperty("integrationOperationId", "op-invoices"))),
            new ChainPlanNode(
                "normalize",
                "script",
                "Normalize payload",
                null,
                null,
                List.of(new PlanProperty("script", "return 1")))),
        List.of(new ChainPlanEdge("edge-1", TARGET, UNRELATED, null)));
  }

  private static ChainPlanGraph twoHttpTriggers() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("http-a", "http-trigger", "HTTP A", null, null, List.of()),
            new ChainPlanNode("http-b", "http-trigger", "HTTP B", null, null, List.of())),
        List.of());
  }

  /** What the service-call generator writes when it is handed the resolved binding. */
  private static ChainPlanGraph compiledGraph() {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : importedGraph().nodes()) {
      if (!TARGET.equals(node.nodeId())) {
        nodes.add(node);
        continue;
      }
      nodes.add(
          new ChainPlanNode(
              node.nodeId(),
              node.type(),
              node.label(),
              node.parentNodeId(),
              node.order(),
              List.of(
                  new PlanProperty("integrationOperationId", "op-status"),
                  new PlanProperty("integrationOperationMethod", "POST"),
                  new PlanProperty("integrationOperationPath", "/orders/{id}/status"),
                  new PlanProperty("integrationOperationProtocolType", "HTTP"),
                  new PlanProperty("integrationSystemId", "sys-1"),
                  new PlanProperty("integrationSpecificationId", "spec-1"),
                  new PlanProperty("integrationSpecificationGroupId", "group-1"),
                  new PlanProperty("systemType", "EXTERNAL"),
                  new PlanProperty("retryCount", "3"))));
    }
    return new ChainPlanGraph(
        importedGraph().schemaVersion(),
        importedGraph().chain(),
        List.copyOf(nodes),
        importedGraph().edges());
  }

  private static ChainPlanGraph structuralGraph(boolean configured) {
    return new ChainPlanGraph(
        importedGraph().schemaVersion(),
        importedGraph().chain(),
        List.of(
            node(importedGraph(), TARGET),
            node(importedGraph(), UNRELATED),
            new ChainPlanNode(
                "normalize",
                "script",
                "Normalize payload",
                "try-shell",
                null,
                List.of(new PlanProperty("script", "return 1"))),
            new ChainPlanNode(
                "error-handler",
                "try-catch-finally-2",
                "Error handler",
                null,
                null,
                List.of()),
            new ChainPlanNode(
                "try-shell", "try-2", "Try", "error-handler", null, List.of()),
            new ChainPlanNode(
                "catch-shell",
                "catch-2",
                "Catch",
                "error-handler",
                null,
                configured
                    ? List.of(
                        new PlanProperty("exception", "java.lang.Exception"),
                        new PlanProperty("priority", "0"))
                    : List.of()),
            new ChainPlanNode(
                "catch-response",
                "script",
                "Return error response",
                "catch-shell",
                null,
                configured
                    ? List.of(new PlanProperty("script", "return [status: 500]"))
                    : List.of())),
        List.of(
            new ChainPlanEdge("edge-1", TARGET, UNRELATED, null),
            new ChainPlanEdge("normalize-to-error-handler", "normalize", "error-handler", null)));
  }

  private static CompilerRunPin pin() {
    ResolvedCompilerDag dag =
        new ResolvedCompilerDag(
            List.of(
                new ResolvedCompilerNode(
                    STRUCTURE_GENERATOR,
                    "Planning",
                    null,
                    List.of("CHAIN_PLAN_GRAPH"),
                    List.of("CHAIN_STRUCTURE", "CHAIN_PLAN_GRAPH"),
                    List.of(),
                    "captureChainStructure",
                    List.of(),
                    List.of(),
                    true,
                    List.of(),
                    0,
                    0,
                    true,
                    CompilerNodeExecutionMode.LLM_SKILL,
                    null,
                    null),
                generator(
                    GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        true,
                        true,
                        Set.of("service-call", "http-sender"),
                        Set.of(),
                        Map.of("service-call", Set.of("integrationOperationId")))),
                generator(
                    ERROR_HANDLING_GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        Map.of("catch-2", Set.of("exception", "priority")))),
                generator(
                    SCRIPT_GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script")))),
                generator(
                    "cip-quartz-scheduler-generator",
                    new GraphPatchOwnershipPolicy(
                        false,
                        true,
                        Set.of("quartz-scheduler"),
                        Set.of(),
                        Map.of("quartz-scheduler", Set.of("cron", "deleteJob")))),
                generator(
                    "cip-auth-generator",
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        Map.of("service-call", Set.of("authorizationConfiguration")))),
                generator(
                    "cip-timeout-generator",
                    new GraphPatchOwnershipPolicy(
                        false, false, Set.of(), Set.of(), Map.of("http-trigger", Set.of("connectTimeout")))),
                generator(
                    "cip-retry-generator",
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        Map.of("service-call", Set.of("retryCount", "retryDelay")))),
                node("cip-chain-assembler", "Assembly", CompilerNodeExecutionMode.JAVA_ADAPTER, "graph-assembly"),
                node(
                    "cip-element-validator",
                    "Validation",
                    CompilerNodeExecutionMode.JAVA_ADAPTER,
                    "cip-element-validator")),
            List.of(),
            "dag-digest");
    return new CompilerRunPin(
        "compiler-v2", "1.0.0", "package-digest", 2, "v1", "index-digest", dag, List.of(), Map.of(), Map.of(), List.of());
  }

  private static ResolvedCompilerNode generator(String skillId, GraphPatchOwnershipPolicy ownership) {
    return new ResolvedCompilerNode(
        skillId,
        "Generation",
        null,
        List.of("CHAIN_PLAN_GRAPH"),
        List.of("CHAIN_PLAN_GRAPH"),
        List.of(),
        "captureGraphPatch",
        List.of(),
        List.of(),
        true,
        List.of(),
        0,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null,
        ownership);
  }

  private static ResolvedCompilerNode node(
      String skillId, String phase, CompilerNodeExecutionMode mode, String adapterId) {
    return new ResolvedCompilerNode(
        skillId,
        phase,
        null,
        List.of("CHAIN_PLAN_GRAPH"),
        List.of("CHAIN_PLAN_GRAPH"),
        List.of(),
        mode == CompilerNodeExecutionMode.LLM_SKILL ? "captureGraphPatch" : null,
        List.of(),
        List.of(),
        true,
        List.of(),
        0,
        0,
        true,
        mode,
        adapterId);
  }

  /** Stands in for the shared DAG execution engine, recording what the compiler asked it to run. */
  private static final class FakeEngine implements CompilerDagExecutionEngine {

    private final AtomicReference<CompilerDagExecutionRequest> lastRequest = new AtomicReference<>();
    private final List<CompilerDagExecutionRequest> requests = new ArrayList<>();
    private boolean validationValid = true;
    private RuntimeException failure;

    @Override
    public Uni<CompilerDagExecutionResult> execute(
        CompilerDagExecutionRequest request, java.util.function.BiConsumer<String, String> progress) {
      lastRequest.set(request);
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      ValidationResult validation =
          validationValid
              ? new ValidationResult(true, List.of(), "ok")
              : new ValidationResult(
                  false,
                  List.of(new ValidationIssue(
                          "v-1",
                          org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity.BLOCKER,
                          "element refused",
                          null,
                          List.of(),
                          List.of(),
                          null)),
                  "refused");
      boolean structureOnly =
          request.approvedOwningSkillIds().equals(List.of(STRUCTURE_GENERATOR));
      boolean structuralConfiguration =
          request.approvedOwningSkillIds().contains(ERROR_HANDLING_GENERATOR);
      List<String> executed =
          structureOnly
              ? List.of(STRUCTURE_GENERATOR)
              : structuralConfiguration
                  ? List.of(ERROR_HANDLING_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler")
                  : List.of(GENERATOR, "cip-chain-assembler");
      ChainPlanGraph resultGraph =
          structureOnly
              ? structuralGraph(false)
              : structuralConfiguration ? structuralGraph(true) : compiledGraph();
      return Uni.createFrom()
          .item(
              new CompilerDagExecutionResult(
                  StageOutcomeClass.SUCCEEDED,
                  null,
                  executed,
                  null,
                  resultGraph,
                  null,
                  structureOnly
                      ? null
                      : new CompilerValidationBundle(
                          1,
                          "digest",
                          List.of(
                              new CompilerValidationPass(
                                  "cip-element-validator", validation)))));
    }
  }
}

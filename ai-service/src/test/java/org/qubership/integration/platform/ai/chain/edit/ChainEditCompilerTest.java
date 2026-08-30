package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.llm.agent.ChainEditIntentAgent;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
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
import org.qubership.integration.platform.ai.productpipeline.create.CompilerValidationPipeline;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBody;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBranch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphConnection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphElement;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.MaterializationRequirementsValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.QipSchemaYamlParser;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
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
  private static final String SECURITY_GENERATOR = "cip-security-generator";
  private static final String HTTP_TRIGGER_ENDPOINT_GENERATOR =
      "cip-http-trigger-endpoint-generator";
  private static final String MESSAGING_GENERATOR = "cip-messaging-generator";

  private ChainEditCapture intentReply;
  private CatalogRestClient catalogRestClient;
  private CatalogSystemReadTool readTool;
  private ApiHubMcpTools apiHub;
  private CatalogMutationGateway catalogMutationGateway;
  private FakeEngine engine;
  private ChainEditCompiler compiler;
  private CompilerRunPinResolver runPinResolver;

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
    when(readTool.searchCatalogSystems(any()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Orders", "EXTERNAL", "HTTP")));
    when(readTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "Orders API", "group-1", "sys-1")));

    runPinResolver = mock(CompilerRunPinResolver.class);
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
    ChainEditIntentAgent agent =
        (elements, transcriptWindow, pinnedFailure, userRequest) -> intentReply;
    compiler =
        new ChainEditCompiler(
            new ChainEditIntentResolver(agent),
            new ServiceCallBindingResolver(catalogRestClient, readTool, apiHub),
            engine,
            runPinResolver,
            profileCatalog,
            knowledge,
            catalogMutationGateway,
            new CaptureSession(),
            DeterministicElementSchemaService.createForUnitTests(new ObjectMapper()),
            realValidationPipeline());
  }

  private static CompilerValidationPipeline realValidationPipeline() {
    ObjectMapper mapper = new ObjectMapper();
    DeterministicElementSchemaService schemaService =
        DeterministicElementSchemaService.createForUnitTests(mapper);
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    SchemaRefResolver schemaRefResolver =
        new SchemaRefResolver(schemaResourceLoader, new QipSchemaYamlParser());
    MaterializationRequirementsValidator requirements =
        mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(any())).thenReturn(List.of());
    return new CompilerValidationPipeline(
        schemaResourceLoader,
        schemaRefResolver,
        mapper,
        new ChainPlanGraphValidator(schemaService),
        schemaService,
        new CompilerSecurityValidator(),
        new CompilerQualityValidator(requirements));
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
            "serviceCallId", "call-orders",
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
            "serviceCallId", "call-orders",
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
  void compileReportsIntentAndGeneratorSkillProgress() {
    List<String> seen = new ArrayList<>();

    compiler.compile(request(), (skillId, status) -> seen.add(skillId + ":" + status));

    assertTrue(seen.contains("chain-edit-intent:running"), seen.toString());
    assertTrue(seen.contains("chain-edit-intent:completed"), seen.toString());
    assertTrue(seen.contains(GENERATOR + ":running"), seen.toString());
    assertTrue(seen.contains(GENERATOR + ":completed"), seen.toString());
  }

  @Test
  void theResolvedBindingKeepsEveryCatalogFieldAsTypedData() {
    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ResolvedServiceCallBinding binding = proposal.bindings().get(0);
    assertEquals(TARGET, binding.targetNodeId());
    assertEquals(TARGET, binding.serviceCallId());
    assertEquals("EXTERNAL", binding.systemType());
    assertEquals("sys-1", binding.systemId());
    assertEquals("group-1", binding.specificationGroupId());
    assertEquals("spec-1", binding.specificationId());
    assertEquals("op-status", binding.operationId());
    assertEquals("http", binding.protocolType());
    assertEquals("POST", binding.method());
    assertEquals("/orders/{id}/status", binding.path());
    assertEquals("Post order status", binding.displayName());
    assertEquals(ResolvedServiceCallBinding.Source.EXISTING_CATALOG, binding.source());
    assertEquals("catalog:/v1/operations/op-status", binding.evidenceRef());
  }

  @Test
  void theGeneratorReceivesTheHydratedGraphTheExactTargetAndTheCompleteBinding() {
    compiler.compile(request());

    CompilerDagExecutionRequest seen = engine.lastRequest.get();
    ChainPlanGraph seedGraph =
        artifact(
                seen,
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph();
    assertEquals("call-orders", property(seedGraph, TARGET, "serviceCallId"));
    assertEquals("op-status", property(seedGraph, TARGET, "integrationOperationId"));
    assertEquals("POST", property(seedGraph, TARGET, "integrationOperationMethod"));
    assertEquals("/orders/{id}/status", property(seedGraph, TARGET, "integrationOperationPath"));
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
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, compiler.compile(request()));
    assertEquals(
        List.of("call-orders (Call orders)", "call-invoices (Call invoices)"),
        clarification.choices());
    // The unresolved intent travels with the clarification so the next turn can resume this same
    // edit instead of resolving the reply with no record of having asked.
    assertEquals(ChainEditAction.REBIND_SERVICE_CALL, clarification.heldIntent().action());
    assertEquals(List.of(), clarification.heldIntent().targetNodeIds());
  }

  @Test
  void resumingAClarificationThatAnswersTheQuestionReachesAProposal() {
    intentReply =
        capture(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(TARGET),
            "point it at the order-status operation",
            "order status");
    ChainEditIntent held =
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "point it at the order-status operation",
            "order status",
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));

    ChainEditOutcome outcome =
        compiler.resumeAfterClarification(request(), held, "Which element did you mean?");

    assertInstanceOf(ChainEditOutcome.Proposal.class, outcome);
  }

  @Test
  void resumingAClarificationThatIsStillUnresolvedAsksAgainWithTheUpdatedHeldIntent() {
    intentReply =
        capture(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "point it at the order-status operation",
            "order status",
            null,
            null,
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));
    ChainEditIntent held =
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "point it at the order-status operation",
            "order status",
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(
            ChainEditOutcome.Clarification.class,
            compiler.resumeAfterClarification(request(), held, "Which element did you mean?"));

    assertEquals(
        List.of("call-orders (Call orders)", "call-invoices (Call invoices)"),
        clarification.choices());
    assertEquals(ChainEditAction.REBIND_SERVICE_CALL, clarification.heldIntent().action());
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

    ChainEditOutcome.CompilationFailure failure =
        assertInstanceOf(ChainEditOutcome.CompilationFailure.class, compiler.compile(request()));
    assertTrue(failure.message().contains("element refused"));
  }

  @Test
  void aCatalogExternalRouteDoesNotRefuseAnHttpTriggerEndpointEdit() {
    intentReply =
        configureCapture(
            List.of("http-a"),
            "change the endpoint to /test-test POST",
            List.of("contextPath", "httpMethodRestrict"));
    ChainPlanGraph seed = catalogHttpTrigger("test", "GET");
    ChainPlanGraph compiled = catalogHttpTrigger("/test-test", "POST");
    engine.scriptedResults.add(
        new CompilerDagExecutionResult(
            StageOutcomeClass.SUCCEEDED,
            null,
            List.of(HTTP_TRIGGER_ENDPOINT_GENERATOR, "cip-chain-assembler"),
            null,
            compiled,
            null,
            new CompilerValidationBundle(
                1,
                "digest",
                List.of(
                    new CompilerValidationPass(
                        CompilerValidationPipeline.SECURITY,
                        new ValidationResult(
                            false,
                            List.of(
                                new ValidationIssue(
                                    "security-1",
                                    ValidationSeverity.BLOCKER,
                                    "External route requires accessControlType=RBAC",
                                    CompilerValidationPipeline.SECURITY,
                                    List.of("http-a"),
                                    List.of(),
                                    "Set accessControlType to RBAC and provide explicit roles")),
                            "security validation failed with 1 blocker(s)"))))));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.compile(
                new ChainEditRequest(
                    "conv-1",
                    "chain-1",
                    "edit-run-1",
                    new ImportedChainPlan(seed, null, "base-digest"),
                    "change the endpoint to /test-test POST",
                    null)));

    assertEquals("/test-test", property(proposal.finalGraph(), "http-a", "contextPath"));
    assertEquals("POST", property(proposal.finalGraph(), "http-a", "httpMethodRestrict"));
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
  void aScriptPropertyIsRefusedOnAnElementThatDoesNotDefineIt() {
    // "script" belongs to the script element's own schema, and this target is a service call.
    intentReply = configureCapture(List.of(TARGET), "rewrite the script", List.of("script"));

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));
    assertTrue(failure.message().contains("script"), failure.message());
    org.junit.jupiter.api.Assertions.assertNull(
        engine.lastRequest.get(), "the script capability is never reached");
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
        configureCapture(
            List.of("normalize"), "return the customer id in the body", List.of("script"));

    assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));
    assertEquals(
        List.of("cip-script-generator"), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(
        List.of("normalize"), scopedTargets(engine.lastRequest.get()));
  }

  @Test
  void eachConfigurationFamilyGoesToTheCapabilityThatOwnsIt() {
    intentReply =
        configureCapture(
            List.of(TARGET), "use the service account", List.of("authorizationConfiguration"));
    compiler.compile(request());
    assertEquals(List.of("cip-auth-generator"), engine.lastRequest.get().approvedOwningSkillIds());

    intentReply = configureCapture(List.of(TARGET), "try five times", List.of("retryCount"));
    compiler.compile(request());
    assertEquals(List.of("cip-retry-generator"), engine.lastRequest.get().approvedOwningSkillIds());
  }

  @Test
  void aSecurityConfigurationReachesTheSecurityCapability() {
    intentReply = configureCapture(List.of("http-a"), "require RBAC roles", List.of("accessControlType"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(twoHttpTriggers(), null, "base-digest"),
            "require RBAC roles",
            null));

    assertEquals(
        List.of(SECURITY_GENERATOR), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(
        List.of("http-a"), scopedTargets(engine.lastRequest.get(), SECURITY_GENERATOR));
  }

  @Test
  void aTimeoutConfigurationReachesTheTimeoutCapability() {
    intentReply = configureCapture(List.of("http-a"), "wait longer", List.of("connectTimeout"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(twoHttpTriggers(), null, "base-digest"),
            "wait longer",
            null));

    assertEquals(
        List.of("cip-timeout-generator"), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(
        List.of("http-a"), scopedTargets(engine.lastRequest.get(), "cip-timeout-generator"));
  }

  @Test
  void anHttpTriggerEndpointChangeReachesTheHttpTriggerEndpointCapability() {
    intentReply =
        configureCapture(
            List.of("http-a"),
            "change the endpoint to /test-test POST",
            List.of("contextPath", "httpMethodRestrict"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(twoHttpTriggers(), null, "base-digest"),
            "change the endpoint to /test-test POST",
            null));

    assertEquals(
        List.of(HTTP_TRIGGER_ENDPOINT_GENERATOR),
        engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(
        List.of("http-a"),
        scopedTargets(engine.lastRequest.get(), HTTP_TRIGGER_ENDPOINT_GENERATOR));
  }

  @Test
  void aKafkaTriggerIdentityChangeReachesTheMessagingCapability() {
    intentReply =
        configureCapture(
            List.of("kafka-a"),
            "consume orders from brokers kafka:9092",
            List.of("brokers", "topics", "groupId"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(kafkaTrigger(), null, "base-digest"),
            "consume orders from brokers kafka:9092",
            null));

    assertEquals(
        List.of(MESSAGING_GENERATOR), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of("kafka-a"), scopedTargets(engine.lastRequest.get(), MESSAGING_GENERATOR));
  }

  @Test
  void aRabbitMqTriggerIdentityChangeReachesTheMessagingCapability() {
    intentReply =
        configureCapture(
            List.of("rabbit-a"),
            "listen on orders-queue via orders-exchange",
            List.of("queues", "exchange", "addresses"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(rabbitMqTrigger(), null, "base-digest"),
            "listen on orders-queue via orders-exchange",
            null));

    assertEquals(
        List.of(MESSAGING_GENERATOR), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of("rabbit-a"), scopedTargets(engine.lastRequest.get(), MESSAGING_GENERATOR));
  }

  @Test
  void aCatalogBindingOnAnHttpTriggerReachesTheServiceCallCapability() {
    intentReply =
        configureCapture(
            List.of("http-a"),
            "bind the implemented service",
            List.of("systemType", "integrationSystemId"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(twoHttpTriggers(), null, "base-digest"),
            "bind the implemented service",
            null));

    assertEquals(List.of(GENERATOR), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of("http-a"), scopedTargets(engine.lastRequest.get(), GENERATOR));
  }

  @Test
  void aCatalogBindingOnAnAsyncApiTriggerReachesTheServiceCallCapability() {
    intentReply =
        configureCapture(
            List.of("async-a"),
            "bind the kafka service",
            List.of("integrationSystemId", "integrationOperationId"));

    compiler.compile(
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(asyncApiTrigger(), null, "base-digest"),
            "bind the kafka service",
            null));

    assertEquals(List.of(GENERATOR), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of("async-a"), scopedTargets(engine.lastRequest.get(), GENERATOR));
  }

  @Test
  void aTimeoutPropertyIsRefusedOnAnElementThatDoesNotDefineIt() {
    // connectTimeout belongs to http-trigger, and this target is a service call.
    intentReply = configureCapture(List.of(TARGET), "wait longer", List.of("connectTimeout"));

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));
    assertTrue(failure.message().contains("connectTimeout"), failure.message());
    assertFalse(failure.message().contains("cip-"), failure.message());
  }

  @Test
  void aConfigureRequestNamingAPropertyOneGeneratorOwnsResolvesToThatGenerator() {
    intentReply = configureCapture(List.of(TARGET), "try five times", List.of("retryCount"));

    compiler.compile(request());

    assertEquals(List.of("cip-retry-generator"), engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of(TARGET), scopedTargets(engine.lastRequest.get(), "cip-retry-generator"));
  }

  @Test
  void aConfigureRequestNamingPropertiesOwnedByTwoGeneratorsReachesBothEachScopedToItsOwnProperties() {
    intentReply =
        configureCapture(
            List.of(TARGET),
            "use the service account and try five times",
            List.of("authorizationConfiguration", "retryCount"));

    compiler.compile(request());

    assertEquals(
        List.of("cip-auth-generator", "cip-retry-generator"),
        engine.lastRequest.get().approvedOwningSkillIds());
    GeneratorPlanManifest plan =
        artifact(
                engine.lastRequest.get(),
                SkillArtifactType.GENERATOR_PLAN_MANIFEST,
                SkillArtifactPayload.GeneratorPlanManifestPayload.class)
            .manifest();
    assertEquals(
        List.of("authorizationConfiguration"), matchedSignals(plan, "cip-auth-generator"));
    assertEquals(List.of("retryCount"), matchedSignals(plan, "cip-retry-generator"));
    assertEquals(List.of(TARGET), scopedTargets(engine.lastRequest.get(), "cip-auth-generator"));
    assertEquals(List.of(TARGET), scopedTargets(engine.lastRequest.get(), "cip-retry-generator"));
  }

  @Test
  void aConfigureRequestNamingAPropertyNoGeneratorOwnsIsRefusedNamingTheElementNeverAGenerator() {
    // errorThrowing is a real service-call property, but no pinned generator declares it.
    intentReply = configureCapture(List.of(TARGET), "stop throwing errors", List.of("errorThrowing"));

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));

    assertTrue(failure.message().contains("errorThrowing"), failure.message());
    assertTrue(failure.message().contains(TARGET), failure.message());
    assertFalse(failure.message().contains("cip-"), failure.message());
    org.junit.jupiter.api.Assertions.assertNull(
        engine.lastRequest.get(), "an unowned property never reaches the compiler");
  }

  @Test
  void aConfigurePropertyTheElementTypeDoesNotDefineIsReportedBeforeAnyGeneratorRuns() {
    intentReply = configureCapture(List.of(TARGET), "change something odd", List.of("notARealProperty"));

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));

    assertTrue(failure.message().contains("notARealProperty"), failure.message());
    org.junit.jupiter.api.Assertions.assertNull(
        engine.lastRequest.get(), "an undefined property key never reaches the compiler");
  }

  /**
   * One CONFIGURE names two types and the union of their keys. A key that only one of them defines
   * is still a real key: routing already slices each owner to the targets and keys it owns.
   */
  @Test
  void aConfigureRequestNamingKeysSplitAcrossTwoElementTypesReachesEachOwner() {
    intentReply =
        configureCapture(
            List.of(TARGET, "normalize"),
            "try five times and rewrite the script",
            List.of("retryCount", "script"));

    compiler.compile(request());

    assertEquals(
        List.of(SCRIPT_GENERATOR, "cip-retry-generator"),
        engine.lastRequest.get().approvedOwningSkillIds());
    assertEquals(List.of("normalize"), scopedTargets(engine.lastRequest.get(), SCRIPT_GENERATOR));
    assertEquals(List.of(TARGET), scopedTargets(engine.lastRequest.get(), "cip-retry-generator"));
  }

  @Test
  void aConfigureKeyNeitherNamedElementDefinesIsStillRefused() {
    intentReply =
        configureCapture(
            List.of(TARGET, "normalize"),
            "change something odd on both",
            List.of("notARealProperty"));

    ChainEditOutcome.ResolutionFailure failure =
        assertInstanceOf(ChainEditOutcome.ResolutionFailure.class, compiler.compile(request()));

    assertTrue(failure.message().contains("notARealProperty"), failure.message());
    org.junit.jupiter.api.Assertions.assertNull(
        engine.lastRequest.get(), "an undefined property key never reaches the compiler");
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
            List.of(),
            List.of());

    assertInstanceOf(ChainEditOutcome.NoChange.class, compiler.compile(request()));
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
            List.of(),
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
  void addingAnHttpTriggerWithEndpointKeysReachesTheHttpTriggerEndpointCapability() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(),
            "add an http-trigger at /test-test POST",
            null,
            "http-trigger",
            null,
            List.of("contextPath", "httpMethodRestrict"),
            List.of());

    ChainEditOutcome outcome =
        compiler.compile(
            new ChainEditRequest(
                "conv-1",
                "chain-1",
                "edit-run-1",
                new ImportedChainPlan(importedGraph(), null, "base-digest"),
                "add an http-trigger at /test-test POST",
                null));

    assertFalse(
        outcome instanceof ChainEditOutcome.Clarification,
        () ->
            outcome instanceof ChainEditOutcome.Clarification clarification
                ? clarification.question() + " " + clarification.choices()
                : outcome.getClass().getSimpleName());
    assertEquals(
        List.of(HTTP_TRIGGER_ENDPOINT_GENERATOR),
        engine.lastRequest.get().approvedOwningSkillIds());
    ChainPlanGraph seed =
        artifact(
                engine.lastRequest.get(),
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph();
    assertTrue(
        seed.nodes().stream().anyMatch(node -> "http-trigger".equals(node.type())),
        "the seed graph must already carry the new http-trigger");
  }

  @Test
  void anAdditionAtANamedAddressRunsThroughTheStructureStage() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "add a script after the order call",
            null,
            "script",
            null,
            List.of(),
            List.of());
    engine.scriptedResults.add(structureOnlyResult(singleElementSpliceGraph(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            singleElementSpliceGraph(true)));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertEquals(2, engine.requests.size());
    assertEquals(List.of(STRUCTURE_GENERATOR), engine.requests.get(0).approvedOwningSkillIds());
    assertEquals(
        List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR),
        engine.requests.get(1).approvedOwningSkillIds());
    assertEquals(List.of("new-script"), scopedTargets(engine.requests.get(1), SCRIPT_GENERATOR));
    assertEquals(
        "return 1", properties(node(proposal.finalGraph(), "new-script")).get("script"));
  }

  @Test
  void namingBothEndsSplicesTheNewElementBetweenExactlyThoseTwoNamedElements() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET, UNRELATED),
            "add a script between the order call and the invoice call",
            null,
            "script",
            null,
            List.of(),
            List.of());
    engine.scriptedResults.add(structureOnlyResult(singleElementSpliceGraph(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            singleElementSpliceGraph(true)));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph finalGraph = proposal.finalGraph();
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && "new-script".equals(e.toNodeId())));
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> "new-script".equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())));
    assertFalse(
        finalGraph.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())),
        "the direct edge between the two named elements is replaced by the insertion");
  }

  @Test
  void namingOnlyThePrecedingElementInsertsAfterItWhenThatElementHasOneSuccessor() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "add a script after the order call",
            null,
            "script",
            null,
            List.of(),
            List.of());
    engine.scriptedResults.add(structureOnlyResult(singleElementSpliceGraph(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            singleElementSpliceGraph(true)));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph finalGraph = proposal.finalGraph();
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && "new-script".equals(e.toNodeId())));
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> "new-script".equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())));
    assertFalse(
        finalGraph.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())));
  }

  @Test
  void severalConnectedElementsArriveWiredToEachOtherAndSplicedAtTheAddressInOneApproval() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET, UNRELATED),
            "add a script that normalizes the payload, then call the shipping service",
            null,
            "script",
            null,
            List.of(),
            List.of());
    engine.scriptedResults.add(structureOnlyResult(addressSpliceGraph(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            addressSpliceGraph(true)));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    // One structure pass, then one configuration pass that reaches both new elements' owners
    // together: the reader approves one card for the whole insertion, not one per generator.
    assertEquals(2, engine.requests.size());
    assertEquals(List.of(STRUCTURE_GENERATOR), engine.requests.get(0).approvedOwningSkillIds());
    assertEquals(
        List.of(STRUCTURE_GENERATOR, GENERATOR, SCRIPT_GENERATOR),
        engine.requests.get(1).approvedOwningSkillIds());

    ChainPlanGraph finalGraph = proposal.finalGraph();
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && "transform".equals(e.toNodeId())),
        "the order call connects into the new script");
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(
                e -> "transform".equals(e.fromNodeId()) && "call-shipping".equals(e.toNodeId())),
        "the new elements are wired to each other");
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> "call-shipping".equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())),
        "the new service call connects into the invoice call that was already there");
    assertFalse(
        finalGraph.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())),
        "the direct connection between the two named elements is replaced by the new subgraph");

    // Each configuration owner is scoped to its own new element, not the whole insertion.
    assertEquals(List.of("transform"), scopedTargets(engine.requests.get(1), SCRIPT_GENERATOR));
    assertEquals(List.of("call-shipping"), scopedTargets(engine.requests.get(1), GENERATOR));

    // The element already at the address stays exactly where it was.
    assertEquals(node(importedGraph(), TARGET), node(finalGraph, TARGET));
    assertEquals(node(importedGraph(), UNRELATED), node(finalGraph, UNRELATED));
    assertEquals(node(importedGraph(), "normalize"), node(finalGraph, "normalize"));
  }

  @Test
  void aStructuralEditThatAddsTwoServiceCallsFailsClosed() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply = structuralServiceCallCapture();
    engine.scriptedResults.add(structureOnlyResult(addressSpliceWithTwoServiceCalls()));

    ChainEditOutcome.CompilationFailure failure =
        assertInstanceOf(ChainEditOutcome.CompilationFailure.class, compiler.compile(request()));

    assertTrue(failure.message().contains("one service-call occurrence per edit"), failure.message());
    assertEquals(1, engine.requests.size());
  }

  @Test
  void anAmbiguousStructuralServiceCallStoresItsStructuredContinuation() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply = structuralServiceCallCapture();
    stubStructuralCatalogOperations(
        List.of(
            new CatalogRestClient.OperationDto(
                "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1"),
            new CatalogRestClient.OperationDto(
                "op-history", "Get order history", "GET", "/orders/{id}/history", "spec-1")));
    engine.scriptedResults.add(structureOnlyResult(addressSpliceGraph(false)));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, compiler.compile(request()));
    ChainEditClarificationStore store = new ChainEditClarificationStore();
    store.put(
        "conv-1",
        new ChainEditClarificationStore.PendingClarification(
            "chain-1",
            clarification.heldIntent(),
            clarification.question(),
            clarification.continuation()));

    StructuralBindingContinuation continuation =
        store.take("conv-1").orElseThrow().continuation();
    assertEquals(addressSpliceGraph(false), continuation.structuredGraph());
    assertEquals("call-shipping", continuation.targetNodeId());
    assertEquals("call-shipping", continuation.serviceCallId());
    assertEquals("shipping", continuation.bindingQuery());
    assertNull(continuation.importRefs());
  }

  @Test
  void resumingStructuralBindingHydratesTheSavedNodeWithoutRunningStructureAgain() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply = structuralServiceCallCapture();
    stubStructuralCatalogOperations(
        List.of(
            new CatalogRestClient.OperationDto(
                "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1"),
            new CatalogRestClient.OperationDto(
                "op-history", "Get order history", "GET", "/orders/{id}/history", "spec-1")));
    engine.scriptedResults.add(structureOnlyResult(addressSpliceGraph(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, GENERATOR, "cip-chain-assembler"),
            addressSpliceGraph(false)));
    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, compiler.compile(request()));
    stubStructuralCatalogOperations(
        List.of(
            new CatalogRestClient.OperationDto(
                "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1")));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.resumeAfterClarification(
                request("Post order status"),
                clarification.heldIntent(),
                clarification.question(),
                clarification.continuation(),
                null));

    assertEquals(2, engine.requests.size());
    assertEquals(List.of(STRUCTURE_GENERATOR), engine.requests.get(0).approvedOwningSkillIds());
    assertEquals(
        List.of(STRUCTURE_GENERATOR, GENERATOR, SCRIPT_GENERATOR),
        engine.requests.get(1).approvedOwningSkillIds());
    ChainPlanGraph resumedSeed =
        artifact(
                engine.requests.get(1),
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph();
    assertNotNull(node(resumedSeed, "call-shipping"));
    assertEquals("call-shipping", property(resumedSeed, "call-shipping", "serviceCallId"));
    assertEquals("op-status", property(resumedSeed, "call-shipping", "integrationOperationId"));
    assertEquals("POST", property(resumedSeed, "call-shipping", "integrationOperationMethod"));
    assertEquals(
        "/orders/{id}/status",
        property(resumedSeed, "call-shipping", "integrationOperationPath"));
    assertEquals("call-shipping", proposal.bindings().get(0).targetNodeId());
    verify(readTool).searchCatalogSystems("Post order status");
  }

  @Test
  void resumingAResolvedStructuralIntentWithoutContinuationIsAContractFailure() {
    ChainEditOutcome.CompilationFailure failure =
        assertInstanceOf(
            ChainEditOutcome.CompilationFailure.class,
            compiler.resumeAfterClarification(
                request("Post order status"),
                structuralServiceCallIntent(),
                "Which operation do you mean?"));

    assertTrue(failure.message().contains("continuation"), failure.message());
    assertTrue(engine.requests.isEmpty());
  }

  @Test
  void namingAPrecedingElementWithSeveralSuccessorsAsksWhichBranchRatherThanPickingOne() {
    ChainPlanGraph branchingGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("orders", "Orders"),
            List.of(
                new ChainPlanNode(TARGET, "service-call", "Call orders", null, null, List.of()),
                new ChainPlanNode("branch-a", "script", "Branch A", null, null, List.of()),
                new ChainPlanNode("branch-b", "script", "Branch B", null, null, List.of())),
            List.of(
                new ChainPlanEdge("edge-a", TARGET, "branch-a", null),
                new ChainPlanEdge("edge-b", TARGET, "branch-b", null)));
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "add a script after the order call",
            null,
            "script",
            null,
            List.of(),
            List.of());

    ChainEditOutcome outcome =
        compiler.compile(
            new ChainEditRequest(
                "conv-1",
                "chain-1",
                "edit-run-1",
                new ImportedChainPlan(branchingGraph, null, "base-digest"),
                "add a script after the order call",
                null));

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, outcome);
    assertEquals(List.of("branch-a", "branch-b"), clarification.choices());
    assertEquals(List.of(TARGET), clarification.heldIntent().targetNodeIds());
    org.junit.jupiter.api.Assertions.assertNull(
        engine.lastRequest.get(), "nothing is written while the branch is ambiguous");
  }

  @Test
  void anInsertionAddressNamingAnElementTheChainDoesNotHaveIsReportedAsUnresolved() {
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of("call-shipping"),
            "add a script after the shipping call",
            null,
            "script",
            null,
            List.of(),
            List.of());

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(ChainEditOutcome.Clarification.class, compiler.compile(request()));

    assertEquals(
        List.of("The chain has no element 'call-shipping'."), clarification.choices());
    org.junit.jupiter.api.Assertions.assertNull(engine.lastRequest.get());
  }

  @Test
  void aNewElementPlacedInsideAContainerStaysInThatContainerWhenAddedByAddress() {
    ChainPlanGraph containedGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("orders", "Orders"),
            List.of(
                new ChainPlanNode(
                    TARGET, "service-call", "Call orders", "container-1", null, List.of())),
            List.of());
    ChainPlanGraph spliced =
        new ChainPlanGraph(
            containedGraph.schemaVersion(),
            containedGraph.chain(),
            List.of(
                node(containedGraph, TARGET),
                new ChainPlanNode(
                    "new-script",
                    "script",
                    "New script",
                    "container-1",
                    null,
                    List.of(new PlanProperty("script", "return 1")))),
            List.of(new ChainPlanEdge("orders-to-script", TARGET, "new-script", null)));
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "add a script after the order call",
            null,
            "script",
            null,
            List.of(),
            List.of());
    engine.scriptedResults.add(structureOnlyResult(spliced));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"), spliced));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.compile(
                new ChainEditRequest(
                    "conv-1",
                    "chain-1",
                    "edit-run-1",
                    new ImportedChainPlan(containedGraph, null, "base-digest"),
                    "add a script after the order call",
                    null)));

    assertEquals("container-1", node(proposal.finalGraph(), "new-script").parentNodeId());
    assertEquals("container-1", node(proposal.finalGraph(), TARGET).parentNodeId());
  }

  @Test
  void aReplacementYieldsOneProposalThatAddsTheSubgraphAndRemovesTheReplacedElement() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "replace the order call with a script that normalizes the payload, then call shipping",
            null,
            "script",
            null,
            List.of(),
            List.of(),
            ChainEditDisposition.REMOVE);
    engine.scriptedResults.add(structureOnlyResult(addressReplaceGraph(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            addressReplaceGraph(true)));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    assertEquals(2, engine.requests.size());
    ChainPlanGraph finalGraph = proposal.finalGraph();
    assertTrue(finalGraph.nodes().stream().noneMatch(node -> TARGET.equals(node.nodeId())));
    assertTrue(
        proposal.netPatch().nodePatches().stream()
            .anyMatch(
                patch ->
                    patch.operation() == GraphPatchOperation.REMOVE
                        && TARGET.equals(patch.targetNodeId())));
    assertTrue(
        finalGraph.nodes().stream().anyMatch(node -> "transform".equals(node.nodeId())));
    assertTrue(
        finalGraph.nodes().stream().anyMatch(node -> "call-shipping".equals(node.nodeId())));
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(
                e -> "transform".equals(e.fromNodeId()) && "call-shipping".equals(e.toNodeId())));
    assertTrue(
        finalGraph.edges().stream()
            .anyMatch(e -> "call-shipping".equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())));
    assertEquals(node(importedGraph(), UNRELATED), node(finalGraph, UNRELATED));
    assertEquals(node(importedGraph(), "normalize"), node(finalGraph, "normalize"));
    assertEquals(List.of("transform"), scopedTargets(engine.requests.get(1), SCRIPT_GENERATOR));
    assertEquals(List.of("call-shipping"), scopedTargets(engine.requests.get(1), GENERATOR));
  }

  @Test
  void aReplacementDiscoversANewNodeThatKeepsTheReplacedOccurrenceOwner() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "replace the order call with a script that normalizes the payload, then call shipping",
            "shipping",
            "script",
            null,
            List.of(),
            List.of(),
            ChainEditDisposition.REMOVE);
    stubStructuralCatalogOperations(
        List.of(
            new CatalogRestClient.OperationDto(
                "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1")));
    engine.scriptedResults.add(
        structureOnlyResult(addressReplaceGraphWithOccurrenceOwner(false)));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            addressReplaceGraphWithOccurrenceOwner(true)));

    ChainPlanGraph imported = importedGraphWithOccurrenceOwner();
    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.compile(
                new ChainEditRequest(
                    "conv-1",
                    "chain-1",
                    "edit-run-1",
                    new ImportedChainPlan(imported, null, "base-digest"),
                    "replace the order call, then call shipping",
                    null)));

    ChainPlanGraph configuredSeed =
        artifact(
                engine.requests.get(1),
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph();
    assertEquals("call-1", property(configuredSeed, "call-shipping", "serviceCallId"));
    assertEquals("op-status", property(configuredSeed, "call-shipping", "integrationOperationId"));
    assertEquals("call-1", proposal.bindings().get(0).serviceCallId());
    assertEquals("call-shipping", proposal.bindings().get(0).targetNodeId());
  }

  @Test
  void replacingAnElementInsideAContainerKeepsTheSubgraphInsideThatContainer() {
    ChainPlanGraph containedGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("orders", "Orders"),
            List.of(
                new ChainPlanNode("container-1", "try-2", "Try", null, null, List.of()),
                new ChainPlanNode(
                    TARGET, "service-call", "Call orders", "container-1", null, List.of()),
                new ChainPlanNode(
                    UNRELATED, "script", "After call", "container-1", null, List.of())),
            List.of(new ChainPlanEdge("edge-1", TARGET, UNRELATED, null)));
    ChainPlanGraph replaced =
        new ChainPlanGraph(
            containedGraph.schemaVersion(),
            containedGraph.chain(),
            List.of(
                node(containedGraph, "container-1"),
                node(containedGraph, UNRELATED),
                new ChainPlanNode(
                    "transform",
                    "script",
                    "Transform payload",
                    "container-1",
                    null,
                    List.of(new PlanProperty("script", "return 1"))),
                new ChainPlanNode(
                    "call-shipping",
                    "service-call",
                    "Call shipping",
                    "container-1",
                    null,
                    List.of())),
            List.of(new ChainPlanEdge("transform-to-shipping", "transform", "call-shipping", null)));
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply =
        new ChainEditCapture(
            ChainEditAction.ADD_ELEMENTS,
            List.of(TARGET),
            "replace the order call with a script then a shipping call",
            null,
            "script",
            null,
            List.of(),
            List.of(),
            ChainEditDisposition.REMOVE);
    engine.scriptedResults.add(structureOnlyResult(replaced));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"),
            replaced));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.compile(
                new ChainEditRequest(
                    "conv-1",
                    "chain-1",
                    "edit-run-1",
                    new ImportedChainPlan(containedGraph, null, "base-digest"),
                    "replace the order call with a script then a shipping call",
                    null)));

    assertEquals("container-1", node(proposal.finalGraph(), "transform").parentNodeId());
    assertEquals("container-1", node(proposal.finalGraph(), "call-shipping").parentNodeId());
    assertEquals("container-1", node(proposal.finalGraph(), UNRELATED).parentNodeId());
    assertTrue(
        proposal.finalGraph().nodes().stream().noneMatch(node -> TARGET.equals(node.nodeId())));
  }

  @Test
  void wrappingOneElementLeavesEveryOtherElementWhereTheReaderLeftIt() {
    intentReply = wrapCapture(TARGET);
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedGraph(), errorHandlingSubgraph(TARGET), wrapIntent(TARGET), permissiveCache());
    engine.scriptedResults.add(structureOnlyResult(assembled));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, ERROR_HANDLING_GENERATOR, "cip-chain-assembler"),
            assembled));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph proposed = proposal.finalGraph();
    assertEquals(
        nodeOfType(proposed, "try-2").nodeId(), node(proposed, TARGET).parentNodeId());
    assertNull(node(proposed, UNRELATED).parentNodeId());
    assertNull(node(proposed, "normalize").parentNodeId());
  }

  @Test
  void aWrappedElementKeepsItsOutgoingConnectionThroughTheContainer() {
    intentReply = wrapCapture(TARGET);
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedGraph(), errorHandlingSubgraph(TARGET), wrapIntent(TARGET), permissiveCache());
    engine.scriptedResults.add(structureOnlyResult(assembled));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, ERROR_HANDLING_GENERATOR, "cip-chain-assembler"),
            assembled));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph proposed = proposal.finalGraph();
    String container = nodeOfType(proposed, "try-catch-finally-2").nodeId();
    assertTrue(
        proposed.edges().stream()
            .anyMatch(
                edge ->
                    container.equals(edge.fromNodeId()) && UNRELATED.equals(edge.toNodeId())),
        proposed.edges().toString());
    assertTrue(
        proposed.edges().stream()
            .noneMatch(
                edge -> TARGET.equals(edge.fromNodeId()) && UNRELATED.equals(edge.toNodeId())),
        proposed.edges().toString());
  }

  @Test
  void wrappingAnAdjacentGroupMovesEveryNamedElementAndKeepsTheirConnection() {
    intentReply = wrapCapture(TARGET, UNRELATED);
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedGraph(),
            errorHandlingSubgraph(TARGET, UNRELATED),
            wrapIntent(TARGET, UNRELATED),
            permissiveCache());
    engine.scriptedResults.add(structureOnlyResult(assembled));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, ERROR_HANDLING_GENERATOR, "cip-chain-assembler"),
            assembled));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph proposed = proposal.finalGraph();
    String tryBranch = nodeOfType(proposed, "try-2").nodeId();
    assertEquals(tryBranch, node(proposed, TARGET).parentNodeId());
    assertEquals(tryBranch, node(proposed, UNRELATED).parentNodeId());
    assertNull(node(proposed, "normalize").parentNodeId());
    assertTrue(
        proposed.edges().stream()
            .anyMatch(
                edge ->
                    TARGET.equals(edge.fromNodeId()) && UNRELATED.equals(edge.toNodeId())),
        proposed.edges().toString());
  }

  @Test
  void aWrapThatSkipsAnElementBetweenItsTargetsAsksAboutThatElement() {
    intentReply = wrapCapture(TARGET, UNRELATED);

    ChainEditOutcome.Clarification clarification =
        assertInstanceOf(
            ChainEditOutcome.Clarification.class,
            compiler.compile(
                new ChainEditRequest(
                    "conv-1",
                    "chain-1",
                    "edit-run-1",
                    new ImportedChainPlan(chainWithAScriptBetweenTheCalls(), null, "base-digest"),
                    "add error handling around the two calls",
                    null)));

    assertEquals(
        List.of(
            "Normalize payload (normalize) sits between the elements you asked me to wrap."
                + " Say whether to wrap it too, or which elements to wrap instead."),
        clarification.choices());
    assertEquals(List.of(TARGET, UNRELATED), clarification.heldIntent().targetNodeIds());
    assertTrue(engine.requests.isEmpty(), "no generator runs before the reader answers");
  }

  @Test
  void anInsertionOfSeveralLinkedElementsSplicesOneRunAtTheNamedAddressThroughTheStructureStage() {
    intentReply = insertCapture(TARGET, UNRELATED);
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedGraph(),
            insertionSubgraph("transform", "notify"),
            insertIntent(TARGET, UNRELATED),
            permissiveCache());
    engine.scriptedResults.add(structureOnlyResult(assembled));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"), assembled));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph proposed = proposal.finalGraph();
    assertTrue(
        proposed.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && "transform".equals(e.toNodeId())),
        "the address's preceding element connects into the new run");
    assertTrue(
        proposed.edges().stream()
            .anyMatch(e -> "transform".equals(e.fromNodeId()) && "notify".equals(e.toNodeId())),
        "the new elements are wired to each other in the order the request gives");
    assertTrue(
        proposed.edges().stream()
            .anyMatch(e -> "notify".equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())),
        "the new run connects into the address's following element");
    assertFalse(
        proposed.edges().stream()
            .anyMatch(e -> TARGET.equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())),
        "the direct edge between the two named elements is replaced by the new run");
  }

  @Test
  void theAddressElementsOfAnInsertionStayExactlyWhereTheyAre() {
    intentReply = insertCapture(TARGET, UNRELATED);
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedGraph(),
            insertionSubgraph("transform"),
            insertIntent(TARGET, UNRELATED),
            permissiveCache());
    engine.scriptedResults.add(structureOnlyResult(assembled));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, "cip-chain-assembler"), assembled));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph proposed = proposal.finalGraph();
    assertEquals(node(importedGraph(), TARGET), node(proposed, TARGET));
    assertEquals(node(importedGraph(), UNRELATED), node(proposed, UNRELATED));
  }

  @Test
  void aReplacementAssembledFromASubgraphCaptureRemovesTheTargetAndReconnectsItsNeighbour() {
    when(runPinResolver.resolve(any(), any())).thenReturn(pinForAddressSplice());
    intentReply = replaceCapture(TARGET);
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedGraph(),
            insertionSubgraph("transform", "call-shipping"),
            replaceIntent(TARGET),
            permissiveCache());
    engine.scriptedResults.add(structureOnlyResult(assembled));
    engine.scriptedResults.add(
        configuredResult(
            List.of(STRUCTURE_GENERATOR, SCRIPT_GENERATOR, GENERATOR, "cip-chain-assembler"),
            assembled));

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(ChainEditOutcome.Proposal.class, compiler.compile(request()));

    ChainPlanGraph proposed = proposal.finalGraph();
    assertTrue(
        proposed.nodes().stream().noneMatch(node -> TARGET.equals(node.nodeId())),
        "the replaced element is gone");
    assertTrue(
        proposed.edges().stream()
            .anyMatch(e -> "transform".equals(e.fromNodeId()) && "call-shipping".equals(e.toNodeId())),
        "the new elements are wired to each other");
    assertTrue(
        proposed.edges().stream()
            .anyMatch(e -> "call-shipping".equals(e.fromNodeId()) && UNRELATED.equals(e.toNodeId())),
        "the replaced element's outgoing connection follows to the new subgraph's exit");
    assertTrue(
        proposal.netPatch().nodePatches().stream()
            .anyMatch(
                patch ->
                    patch.operation() == GraphPatchOperation.REMOVE
                        && TARGET.equals(patch.targetNodeId())));
  }

  /** The replacement the reader asks for: the named element swapped for a new subgraph. */
  private static ChainEditCapture replaceCapture(String... targetNodeIds) {
    return new ChainEditCapture(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "replace the order call with a transform script and a shipping call",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.REMOVE);
  }

  private static ChainEditIntent replaceIntent(String... targetNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "replace the order call with a transform script and a shipping call",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.REMOVE);
  }

  /** The insertion the reader asks for: new elements spliced at the address they name. */
  private static ChainEditCapture insertCapture(String... targetNodeIds) {
    return new ChainEditCapture(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "add a step between the two named elements",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.KEEP);
  }

  private static ChainEditIntent insertIntent(String... targetNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "add a step between the two named elements",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.KEEP);
  }

  /**
   * What the structure stage captures for that insertion: no container, the new elements and
   * their connections in a single body, wired to each other in the order given.
   */
  private static ChainEditSubgraph insertionSubgraph(String... newNodeIds) {
    List<ChainEditSubgraphElement> elements = new ArrayList<>();
    List<ChainEditSubgraphConnection> connections = new ArrayList<>();
    for (int i = 0; i < newNodeIds.length; i++) {
      elements.add(new ChainEditSubgraphElement(newNodeIds[i], "script", "Step " + (i + 1)));
      if (i > 0) {
        connections.add(new ChainEditSubgraphConnection(newNodeIds[i - 1], newNodeIds[i]));
      }
    }
    return new ChainEditSubgraph(
        null, null, List.of(), new ChainEditSubgraphBody(elements, connections));
  }

  /** The two calls with a script between them, so a wrap of both leaves the script out. */
  private static ChainPlanGraph chainWithAScriptBetweenTheCalls() {
    ChainPlanGraph chain = importedGraph();
    return new ChainPlanGraph(
        chain.schemaVersion(),
        chain.chain(),
        chain.nodes(),
        List.of(
            new ChainPlanEdge("call-to-normalize", TARGET, "normalize", null),
            new ChainPlanEdge("normalize-to-invoices", "normalize", UNRELATED, null)));
  }

  /** The wrap the reader asks for: error handling around the elements they named. */
  private static ChainEditCapture wrapCapture(String... targetNodeIds) {
    return new ChainEditCapture(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "add error handling to the order call",
        null,
        "try-catch-finally-2",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.NEST);
  }

  private static ChainEditIntent wrapIntent(String... targetNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "add error handling to the order call",
        null,
        "try-catch-finally-2",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.NEST);
  }

  /** What the structure stage captures for that wrap: the container, its branches, and the ids. */
  private static ChainEditSubgraph errorHandlingSubgraph(String... movedNodeIds) {
    return new ChainEditSubgraph(
        "try-catch-finally-2",
        "Error handler",
        List.of(
            new ChainEditSubgraphBranch(
                "try-2", "Try", List.of(), null, List.of(movedNodeIds), null),
            new ChainEditSubgraphBranch(
                "catch-2",
                "Catch",
                List.of(),
                null,
                List.of(),
                new ChainEditSubgraphBody(
                    List.of(
                        new ChainEditSubgraphElement("catch-response", "script", "Return error")),
                    List.of()))));
  }

  private static ChainPlanNode nodeOfType(ChainPlanGraph graph, String type) {
    return graph.nodes().stream()
        .filter(candidate -> type.equals(candidate.type()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no element of type " + type));
  }

  /** Every type is a permissive container, so assembling a fixture never fails descriptor checks. */
  private static CatalogElementDescriptorCache permissiveCache() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(loader);
    return new CatalogElementDescriptorCache(loader);
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
            List.of(),
            List.of(),
            ChainEditDisposition.NEST);

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
            List.of(),
            List.of());

    assertInstanceOf(ChainEditOutcome.Unsupported.class, compiler.compile(request()));
  }

  @Test
  void aConfigureCaptureWithoutATargetAsksWhichElementEvenWhenTheUserSaidAdd() {
    intentReply =
        capture(
            ChainEditAction.CONFIGURE,
            List.of(),
            "wait longer",
            null,
            null,
            null,
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
  void anApprovedImportKeepsTheImportedOccurrenceOwner() {
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
    engine.scriptedResults.add(
        configuredResult(
            List.of(GENERATOR, "cip-chain-assembler"), importedOccurrenceGraph(true)));
    ChainEditRequest request =
        new ChainEditRequest(
            "conv-1",
            "chain-1",
            "edit-run-1",
            new ImportedChainPlan(importedOccurrenceGraph(false), null, "base-digest"),
            "point the imported call at the order-status operation",
            null);
    ChainEditIntent intent =
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of("imported-uuid"),
            "point the imported call at the order-status operation",
            "order status",
            List.of());

    ChainEditOutcome.Proposal proposal =
        assertInstanceOf(
            ChainEditOutcome.Proposal.class,
            compiler.resumeAfterImport(request, intent, refs()));

    ChainPlanGraph seed =
        artifact(
                engine.lastRequest.get(),
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SkillArtifactPayload.ChainPlanGraphPayload.class)
            .graph();
    assertEquals("call-petstore", property(seed, "imported-uuid", "serviceCallId"));
    assertEquals("op-status", property(seed, "imported-uuid", "integrationOperationId"));
    assertEquals("call-petstore", proposal.bindings().get(0).serviceCallId());
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
    return capture(action, targets, change, lookup, null, null, List.of());
  }

  private static ChainEditCapture capture(
      ChainEditAction action,
      List<String> targets,
      String change,
      String lookup,
      String elementType,
      String cron,
      List<String> ambiguities) {
    return capture(action, targets, change, lookup, elementType, cron, List.of(), ambiguities);
  }

  private static ChainEditCapture capture(
      ChainEditAction action,
      List<String> targets,
      String change,
      String lookup,
      String elementType,
      String cron,
      List<String> propertyKeys,
      List<String> ambiguities) {
    return new ChainEditCapture(
        action, targets, change, lookup, elementType, cron, propertyKeys, ambiguities);
  }

  private static ChainEditCapture configureCapture(
      List<String> targets, String change, List<String> propertyKeys) {
    return capture(
        ChainEditAction.CONFIGURE,
        targets,
        change,
        null,
        null,
        null,
        propertyKeys,
        List.of());
  }

  private static List<String> scopedTargets(CompilerDagExecutionRequest request) {
    return scopedTargets(request, request.approvedOwningSkillIds().get(0));
  }

  private static List<String> matchedSignals(GeneratorPlanManifest manifest, String skillId) {
    return manifest.plans().stream()
        .filter(plan -> skillId.equals(plan.skillId()))
        .findFirst()
        .orElseThrow()
        .matchedSignals();
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
    return request("point the order call at the order-status operation");
  }

  private static ChainEditRequest request(String userRequest) {
    return new ChainEditRequest(
        "conv-1",
        "chain-1",
        "edit-run-1",
        new ImportedChainPlan(importedGraph(), null, "base-digest"),
        userRequest,
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

  private static ChainPlanGraph importedGraphWithOccurrenceOwner() {
    ChainPlanGraph graph = importedGraph();
    List<ChainPlanNode> nodes =
        graph.nodes().stream()
            .map(
                node -> {
                  if (!TARGET.equals(node.nodeId())) {
                    return node;
                  }
                  List<PlanProperty> properties = new ArrayList<>(node.properties());
                  properties.add(new PlanProperty("serviceCallId", "call-1"));
                  return new ChainPlanNode(
                      node.nodeId(),
                      node.type(),
                      node.label(),
                      node.parentNodeId(),
                      node.order(),
                      List.copyOf(properties));
                })
            .toList();
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
  }

  private static ChainPlanGraph importedOccurrenceGraph(boolean rebound) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "imported-uuid",
                "service-call",
                "Call Petstore",
                null,
                null,
                List.of(
                    new PlanProperty("serviceCallId", "call-petstore"),
                    new PlanProperty(
                        "integrationOperationId", rebound ? "op-status" : "op-old"),
                    new PlanProperty(
                        "integrationOperationMethod", rebound ? "POST" : "GET"),
                    new PlanProperty(
                        "integrationOperationPath",
                        rebound ? "/orders/{id}/status" : "/orders"),
                    new PlanProperty("integrationOperationProtocolType", "http"),
                    new PlanProperty("integrationSystemId", "sys-1"),
                    new PlanProperty("integrationSpecificationId", "spec-1"),
                    new PlanProperty("integrationSpecificationGroupId", "group-1"),
                    new PlanProperty("systemType", "EXTERNAL"),
                    new PlanProperty("retryCount", "3")))),
        List.of());
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

  private static ChainPlanGraph catalogHttpTrigger(String contextPath, String method) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode(
                "http-a",
                "http-trigger",
                "HTTP A",
                null,
                null,
                List.of(
                    new PlanProperty("accessControlType", "NONE"),
                    new PlanProperty("httpMethodRestrict", method),
                    new PlanProperty("contextPath", contextPath),
                    new PlanProperty("externalRoute", "true")))),
        List.of());
  }

  private static String property(ChainPlanGraph graph, String nodeId, String key) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .flatMap(node -> node.properties().stream())
        .filter(property -> key.equals(property.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElse(null);
  }

  private static ChainPlanGraph asyncApiTrigger() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("async-a", "async-api-trigger", "Async A", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph kafkaTrigger() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("kafka-a", "kafka-trigger-2", "Kafka A", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph rabbitMqTrigger() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("rabbit-a", "rabbitmq-trigger-2", "Rabbit A", null, null, List.of())),
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
                  new PlanProperty("serviceCallId", "call-orders"),
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

  /**
   * The graph the structure stage returns for a two-element address insertion: a new script and a
   * new service call spliced between {@code TARGET} and {@code UNRELATED}, wired to each other.
   * {@code edge-1} is reused and retargeted onto the entry of the new pair rather than dropped and
   * re-added, so the direct connection it used to carry does not survive alongside the new one.
   */
  private static ChainPlanGraph addressSpliceGraph(boolean configured) {
    return new ChainPlanGraph(
        importedGraph().schemaVersion(),
        importedGraph().chain(),
        List.of(
            node(importedGraph(), TARGET),
            node(importedGraph(), UNRELATED),
            node(importedGraph(), "normalize"),
            new ChainPlanNode(
                "transform",
                "script",
                "Transform payload",
                null,
                null,
                configured ? List.of(new PlanProperty("script", "return 1")) : List.of()),
            new ChainPlanNode(
                "call-shipping",
                "service-call",
                "Call shipping",
                null,
                null,
                configured
                    ? List.of(new PlanProperty("integrationOperationId", "op-shipping"))
                    : List.of())),
        List.of(new ChainPlanEdge("edge-1", TARGET, "transform", null),
            new ChainPlanEdge("transform-to-shipping", "transform", "call-shipping", null),
            new ChainPlanEdge("shipping-to-invoices", "call-shipping", UNRELATED, null)));
  }

  private static ChainPlanGraph addressSpliceWithTwoServiceCalls() {
    ChainPlanGraph graph = addressSpliceGraph(false);
    List<ChainPlanNode> nodes = new ArrayList<>(graph.nodes());
    nodes.add(
        new ChainPlanNode(
            "call-billing", "service-call", "Call billing", null, null, List.of()));
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
  }

  private void stubStructuralCatalogOperations(List<CatalogRestClient.OperationDto> operations) {
    when(readTool.searchCatalogSystems(any()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Orders", "EXTERNAL", "HTTP")));
    when(readTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "Orders API", "group-1", "sys-1")));
    when(readTool.listCatalogOperations(eq("spec-1"), eq("sys-1"), any()))
        .thenReturn(operations);
  }

  private static ChainEditCapture structuralServiceCallCapture() {
    return new ChainEditCapture(
        ChainEditAction.ADD_ELEMENTS,
        List.of(TARGET, UNRELATED),
        "add a shipping service call between the order and invoice calls",
        "shipping",
        "service-call",
        null,
        List.of(),
        List.of());
  }

  private static ChainEditIntent structuralServiceCallIntent() {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(TARGET, UNRELATED),
        "add a shipping service call between the order and invoice calls",
        "shipping",
        "service-call",
        null,
        List.of(),
        List.of());
  }

  /**
   * The graph the structure stage returns when replacing {@code TARGET}: the new script and service
   * call sit where the order call was, and that order call is omitted.
   */
  private static ChainPlanGraph addressReplaceGraph(boolean configured) {
    return new ChainPlanGraph(
        importedGraph().schemaVersion(),
        importedGraph().chain(),
        List.of(
            node(importedGraph(), UNRELATED),
            node(importedGraph(), "normalize"),
            new ChainPlanNode(
                "transform",
                "script",
                "Transform payload",
                null,
                null,
                configured ? List.of(new PlanProperty("script", "return 1")) : List.of()),
            new ChainPlanNode(
                "call-shipping",
                "service-call",
                "Call shipping",
                null,
                null,
                configured
                    ? List.of(new PlanProperty("integrationOperationId", "op-shipping"))
                    : List.of())),
        List.of(
            new ChainPlanEdge("transform-to-shipping", "transform", "call-shipping", null),
            new ChainPlanEdge("shipping-to-invoices", "call-shipping", UNRELATED, null)));
  }

  private static ChainPlanGraph addressReplaceGraphWithOccurrenceOwner(boolean configured) {
    ChainPlanGraph graph = addressReplaceGraph(configured);
    List<ChainPlanNode> nodes =
        graph.nodes().stream()
            .map(
                node -> {
                  if (!"call-shipping".equals(node.nodeId())) {
                    return node;
                  }
                  List<PlanProperty> properties = new ArrayList<>();
                  properties.add(new PlanProperty("serviceCallId", "call-1"));
                  properties.addAll(node.properties());
                  return new ChainPlanNode(
                      node.nodeId(),
                      node.type(),
                      node.label(),
                      node.parentNodeId(),
                      node.order(),
                      List.copyOf(properties));
                })
            .toList();
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
  }

  /** The graph the structure stage returns for a single new element spliced at one address. */
  private static ChainPlanGraph singleElementSpliceGraph(boolean configured) {
    return new ChainPlanGraph(
        importedGraph().schemaVersion(),
        importedGraph().chain(),
        List.of(
            node(importedGraph(), TARGET),
            node(importedGraph(), UNRELATED),
            node(importedGraph(), "normalize"),
            new ChainPlanNode(
                "new-script",
                "script",
                "New script",
                null,
                null,
                configured ? List.of(new PlanProperty("script", "return 1")) : List.of())),
        List.of(
            new ChainPlanEdge("edge-1", TARGET, "new-script", null),
            new ChainPlanEdge("new-script-to-invoices", "new-script", UNRELATED, null)));
  }

  private static CompilerDagExecutionResult structureOnlyResult(ChainPlanGraph graph) {
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED, null, List.of(STRUCTURE_GENERATOR), null, graph, null, null);
  }

  private static CompilerDagExecutionResult configuredResult(
      List<String> executed, ChainPlanGraph graph) {
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        null,
        executed,
        null,
        graph,
        null,
        new CompilerValidationBundle(
            1,
            "digest",
            List.of(
                new CompilerValidationPass(
                    "cip-element-validator", new ValidationResult(true, List.of(), "ok")))));
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
                    HTTP_TRIGGER_ENDPOINT_GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        Map.of(
                            "http-trigger",
                            Set.of(
                                "contextPath",
                                "httpMethodRestrict",
                                "externalRoute",
                                "privateRoute")))),
                generator(
                    GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of("service-call", "http-sender"),
                        Set.of(),
                        Map.of(
                            "service-call",
                            Set.of("integrationOperationId"),
                            "http-trigger",
                            Set.of(
                                "systemType",
                                "integrationSystemId",
                                "integrationSpecificationGroupId",
                                "integrationSpecificationId",
                                "integrationOperationId",
                                "integrationOperationPath"),
                            "async-api-trigger",
                            Set.of(
                                "systemType",
                                "integrationSystemId",
                                "integrationSpecificationGroupId",
                                "integrationSpecificationId",
                                "integrationOperationId",
                                "integrationOperationPath",
                                "integrationOperationProtocolType",
                                "integrationOperationMethod")))),
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
                    MESSAGING_GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        Map.of(
                            "kafka-trigger-2",
                            Set.of("brokers", "topics", "groupId", "connectionSourceType"),
                            "rabbitmq-trigger-2",
                            Set.of("queues", "exchange", "addresses", "connectionSourceType")))),
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
                generator(
                    SECURITY_GENERATOR,
                    new GraphPatchOwnershipPolicy(
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        Map.of("http-trigger", Set.of("accessControlType")))),
                node("cip-chain-assembler", "Assembly", CompilerNodeExecutionMode.JAVA_ADAPTER, "graph-assembly"),
                node(
                    "cip-element-validator",
                    "Validation",
                    CompilerNodeExecutionMode.JAVA_ADAPTER,
                    "cip-element-validator")),
            List.of(),
            "dag-digest");
    return new CompilerRunPin(
        "compiler-v2", "1.0.0", "package-digest", 2, "v1", "index-digest", dag, List.of(), Map.of(), Map.of(), List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ResolvedCompilerNode generator(String skillId, GraphPatchOwnershipPolicy ownership) {
    return generator(skillId, ownership, "captureGraphPatch");
  }

  private static ResolvedCompilerNode generator(
      String skillId, GraphPatchOwnershipPolicy ownership, String captureTool) {
    return new ResolvedCompilerNode(
        skillId,
        "Generation",
        null,
        List.of("CHAIN_PLAN_GRAPH"),
        List.of("CHAIN_PLAN_GRAPH"),
        List.of(),
        captureTool,
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

  /**
   * Owners that a two-element insertion actually needs: the structure stage plus the script and
   * service-call configuration generators. The default pin also carries auth and retry owners for
   * {@code service-call}, which would each get their own plan slice on the new call.
   */
  private static CompilerRunPin pinForAddressSplice() {
    CompilerRunPin base = pin();
    Set<String> keep =
        Set.of(
            STRUCTURE_GENERATOR,
            GENERATOR,
            SCRIPT_GENERATOR,
            "cip-chain-assembler",
            "cip-element-validator");
    List<ResolvedCompilerNode> nodes =
        base.resolvedDag().nodes().stream().filter(node -> keep.contains(node.skillId())).toList();
    ResolvedCompilerDag dag =
        new ResolvedCompilerDag(nodes, List.of(), base.resolvedDag().digest());
    return new CompilerRunPin(
        base.compilerPackageId(),
        base.compilerPackageVersion(),
        base.compilerPackageDigest(),
        base.pipelineIndexSchemaVersion(),
        base.pipelineIndexVersion(),
        base.pipelineIndexDigest(),
        dag,
        base.capabilityClosure(),
        base.skillSha256ById(),
        base.addonSha256ById(),
        base.runtimeArtifactSchemas(),
        null,
        null,
        null,
        null,
        null,
        null);
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
    // Populated by a test that needs a specific structure-stage or configuration-stage result
    // rather than the default wrap/rebind fixtures below; consumed in call order.
    private final java.util.Deque<CompilerDagExecutionResult> scriptedResults =
        new java.util.ArrayDeque<>();
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
      if (!scriptedResults.isEmpty()) {
        CompilerDagExecutionResult scripted = scriptedResults.removeFirst();
        reportProgress(progress, scripted.executedSkillIds());
        return Uni.createFrom().item(scripted);
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
      reportProgress(progress, executed);
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

    private static void reportProgress(
        java.util.function.BiConsumer<String, String> progress, List<String> skillIds) {
      if (progress == null || skillIds == null) {
        return;
      }
      for (String skillId : skillIds) {
        progress.accept(skillId, "running");
        progress.accept(skillId, "completed");
      }
    }
  }
}

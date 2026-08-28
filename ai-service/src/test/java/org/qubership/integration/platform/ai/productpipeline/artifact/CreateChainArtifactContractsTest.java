package org.qubership.integration.platform.ai.productpipeline.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionPhase;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ApiOperationBindings;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolutions;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionTrace;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ExecutorValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.OrderedGraphPatches;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class CreateChainArtifactContractsTest {

  private static final String RUN_ID = "design-contracts-run";
  private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T12:00:00Z");

  private ObjectMapper mapper;
  private CompilationArtifacts artifacts;
  private ProductPipelineArtifactStore store;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    store = new ProductPipelineArtifactStore(artifacts);
  }

  @Test
  void graphPatchArtifactRetainsReplayInputs() throws Exception {
    var patch = GraphPatchFixtures.empty("cip-routing-generator");
    var artifact =
        new GraphPatchArtifact(
            1,
            "patch-1",
            "cip-routing-generator",
            "base-sha",
            "result-sha",
            patch,
            List.of(new Reference(Kind.CHAIN_STRUCTURE, "structure-1", "structure-sha")),
            List.of("fact-routing"),
            List.of(),
            "No routing requested",
            PatchApplicability.NOT_APPLICABLE,
            "invocation-sha");

    String json = mapper.writeValueAsString(artifact);
    GraphPatchArtifact restored = mapper.readValue(json, GraphPatchArtifact.class);

    assertEquals("base-sha", restored.baseGraphDigest());
    assertEquals("result-sha", restored.resultGraphDigest());
    assertEquals(PatchApplicability.NOT_APPLICABLE, restored.applicability());
    assertEquals("invocation-sha", restored.invocationKey());
  }

  @Test
  void elementSkeletonRejectsBehavioralPropertiesByConstruction() {
    assertFalse(
        Arrays.stream(ElementRole.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("properties")));
  }

  @Test
  void compilationArtifactsKindContainsCreateChainAdditions() {
    for (String name :
        List.of(
            "ELEMENT_SKELETON",
            "NAMING_MANIFEST",
            "CONFIGURED_TRIGGER_SET",
            "CHAIN_STRUCTURE",
            "GRAPH_PATCH_ARTIFACT",
            "GRAPH_ASSEMBLY_RESULT",
            "COMPILER_VALIDATION_BUNDLE",
            "MATERIALIZATION_CHECKPOINT",
            "MATERIALIZATION_RESULT",
            "MATERIALIZATION_MAP",
            "CATALOG_CHAIN_SNAPSHOT",
            "RECONCILE_RESULT")) {
      assertNotNull(Kind.valueOf(name));
    }
  }

  @Test
  void compilationArtifactsKindContainsSharedDesignAdditions() {
    for (String name :
        List.of(
            "IDS_DOCUMENT",
            "CHAIN_SEMANTIC_REVISION",
            "CATALOG_BINDING_HINT",
            "DESIGN_PLAN_REPORT",
            "DESIGN_EXECUTION_PLAN",
            "CATALOG_BINDING_RESOLUTIONS",
            "EXECUTION_TRACE",
            "API_OPERATION_BINDINGS",
            "ORDERED_GRAPH_PATCHES",
            "EXECUTOR_VALIDATION_BUNDLE",
            "VALIDATED_EXECUTION_BUNDLE",
            "MATERIALIZATION_REQUEST",
            "DESIGN_EXECUTION_CHECKPOINT",
            "DESIGN_EXECUTION_RESULT")) {
      assertNotNull(Kind.valueOf(name));
    }
  }

  @Test
  void skillArtifactTypeContainsSixPlanningArtifacts() {
    for (String name :
        List.of(
            "ELEMENT_SKELETON",
            "NAMING_MANIFEST",
            "CONFIGURED_TRIGGER_SET",
            "CHAIN_STRUCTURE",
            "GRAPH_PATCH_ARTIFACT",
            "GRAPH_ASSEMBLY_RESULT")) {
      assertNotNull(SkillArtifactType.valueOf(name));
    }
  }

  @Test
  void sharedDesignArtifactsRoundTripThroughProductStore() {
    IdsDocument ids =
        new IdsDocument(
            "1",
            IdsDocument.Mode.DERIVED,
            "brief-ref",
            "brief-hash",
            "flow-hash",
            "renderer-1",
            "# IDS\n");
    assertEquals(ids, roundTrip(Kind.IDS_DOCUMENT, ids));

    CatalogBindingHint hint =
        new CatalogBindingHint(
            "2",
            "call-1",
            "fact-1",
            "get order",
            "sys-1",
            "sg-1",
            "spec-1",
            "op-1",
            "http",
            "GET",
            "/orders/{id}",
            "2024.4",
            FIXED_INSTANT,
            "evidence-1");
    assertEquals(hint, roundTrip(Kind.CATALOG_BINDING_HINT, hint));

    DesignPlanReport report = new DesignPlanReport("1", "1. Create trigger\n");
    assertEquals(report, roundTrip(Kind.DESIGN_PLAN_REPORT, report));

    DesignExecutionPlan projection = sampleExecutionPlan();
    assertEquals(projection, roundTrip(Kind.DESIGN_EXECUTION_PLAN, projection));

    CatalogBindingResolutions resolutions =
        new CatalogBindingResolutions(
            "1",
            List.of(
                new CatalogBindingResolution(
                    "step-call",
                    CatalogBindingResolution.Source.EXISTING_CATALOG,
                    "sys-1",
                    "sg-1",
                    "spec-1",
                    "op-1",
                    "pkg-1",
                    "2024.4",
                    "evidence-1")));
    assertEquals(resolutions, roundTrip(Kind.CATALOG_BINDING_RESOLUTIONS, resolutions));

    DesignExecutionTrace trace =
        new DesignExecutionTrace(
            "1",
            List.of(
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.PRECONDITIONS,
                    "step-1",
                    List.of(),
                    List.of(),
                    "ok")));
    assertEquals(trace, roundTrip(Kind.EXECUTION_TRACE, trace));

    ApiOperationBindings apiBindings =
        new ApiOperationBindings(
            "1",
            List.of(
                new ApiOperationBindings.Binding(
                    "step-call", "sys-1", "sg-1", "spec-1", "op-1", "pkg-1", "2024.4")));
    assertEquals(apiBindings, roundTrip(Kind.API_OPERATION_BINDINGS, apiBindings));

    OrderedGraphPatches patches =
        new OrderedGraphPatches(
            "1", List.of(new Reference(Kind.GRAPH_PATCH_ARTIFACT, "patch-1", "patch-hash")));
    assertEquals(patches, roundTrip(Kind.ORDERED_GRAPH_PATCHES, patches));

    ExecutorValidationBundle executorValidation =
        new ExecutorValidationBundle(
            "1",
            "graph-digest",
            new Reference(Kind.DESIGN_PLAN_REPORT, "report-1", "report-hash"),
            "report-hash",
            new Reference(Kind.DESIGN_EXECUTION_PLAN, "plan-1", "plan-hash"),
            "plan-hash",
            true,
            List.of());
    assertEquals(
        executorValidation, roundTrip(Kind.EXECUTOR_VALIDATION_BUNDLE, executorValidation));

    ValidatedExecutionBundle bundle = sampleValidatedBundle();
    assertEquals(bundle, roundTrip(Kind.VALIDATED_EXECUTION_BUNDLE, bundle));

    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            bundle.approvalRef(),
            bundle.designPlanReportRef(),
            bundle.designExecutionPlanRef(),
            bundle.graphDigest(),
            bundle.orderedPatchDigest(),
            new Reference(Kind.VALIDATED_EXECUTION_BUNDLE, "bundle-1", "bundle-hash"));
    assertEquals(request, roundTrip(Kind.MATERIALIZATION_REQUEST, request));

    DesignExecutionCheckpoint checkpoint =
        new DesignExecutionCheckpoint(
            "1",
            bundle.approvalRef(),
            "report-hash",
            "plan-hash",
            "manifest-hash",
            DesignExecutionPhase.WAITING_FOR_MATERIALIZATION,
            List.of(
                new DesignExecutionCheckpoint.CompletedStep(
                    "step-1",
                    List.of("in-hash"),
                    List.of(new Reference(Kind.GRAPH_PATCH_ARTIFACT, "patch-1", "patch-hash")),
                    List.of("out-hash"),
                    sampleProvenance(),
                    "ok")));
    assertEquals(checkpoint, roundTrip(Kind.DESIGN_EXECUTION_CHECKPOINT, checkpoint));
    assertEquals(DesignExecutionPhase.WAITING_FOR_MATERIALIZATION, checkpoint.phase());

    DesignExecutionResult result =
        new DesignExecutionResult(
            "1",
            bundle.approvalRef(),
            "report-hash",
            "plan-hash",
            new Reference(Kind.MATERIALIZATION_RESULT, "mat-1", "mat-hash"),
            new Reference(Kind.RECONCILE_RESULT, "rec-1", "rec-hash"),
            "COMPLETE");
    assertEquals(result, roundTrip(Kind.DESIGN_EXECUTION_RESULT, result));
  }

  @Test
  void roundTripsCatalogBindingHintV2() {
    CatalogBindingHint omHint =
        new CatalogBindingHint(
            "2",
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            "http",
            "POST",
            "/tasks/result",
            "2024.4",
            FIXED_INSTANT,
            "evidence-om");
    CatalogBindingHint wfmHint =
        new CatalogBindingHint(
            "2",
            "call-wfm-create-task",
            "fact-wfm",
            "createTask",
            "sys-wfm",
            "sg-wfm",
            "spec-wfm",
            "op-shared",
            "http",
            "POST",
            "/tasks",
            "2024.4",
            FIXED_INSTANT,
            "evidence-wfm");

    CatalogBindingHint restoredOm = roundTrip(Kind.CATALOG_BINDING_HINT, omHint);
    CatalogBindingHint restoredWfm = roundTrip(Kind.CATALOG_BINDING_HINT, wfmHint);

    assertEquals("call-om-result", restoredOm.serviceCallId());
    assertEquals("call-wfm-create-task", restoredWfm.serviceCallId());
    assertEquals("op-shared", restoredOm.integrationOperationId());
    assertEquals("op-shared", restoredWfm.integrationOperationId());
    assertEquals("POST", restoredOm.method());
    assertEquals("/tasks", restoredWfm.path());
    assertEquals(omHint, restoredOm);
    assertEquals(wfmHint, restoredWfm);
  }

  @Test
  void rejectsV1HintServiceCallSourceFactId() {
    String v1 =
        """
        {
          "schemaVersion": "1",
          "serviceCallSourceFactId": "fact-1",
          "operationQuery": "get order",
          "systemId": "sys-1",
          "specificationGroupId": "sg-1",
          "specificationId": "spec-1",
          "integrationOperationId": "op-1",
          "release": "2024.4",
          "observedAt": "2026-07-29T12:00:00Z",
          "evidenceRef": "evidence-1"
        }
        """;

    assertThrows(
        Exception.class, () -> mapper.readValue(v1, CatalogBindingHint.class));
  }

  @Test
  void missingCollectionsNormalizeToImmutableEmptyLists() {
    DesignExecutionPlan projection =
        new DesignExecutionPlan(
            "1",
            "flow-1",
            "cip-design-planner",
            "ids-ref",
            "ids-hash",
            "2024.4",
            "CATALOG_FIRST_V1",
            null,
            "report-ref",
            "report-hash",
            null,
            null,
            "catalog-hash",
            "policy-hash");
    assertTrue(projection.steps().isEmpty());
    assertTrue(projection.pinnedSkillHashes().isEmpty());
    assertTrue(projection.pinnedAddonHashes().isEmpty());

    DesignExecutionPlan.Step step =
        new DesignExecutionPlan.Step(
            "step-1",
            1,
            "Create trigger",
            DesignExecutionPlan.OwnerKind.SKILL,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertTrue(step.owningSkillIds().isEmpty());
    assertTrue(step.toolOperationRefs().isEmpty());
    assertTrue(step.participantRefs().isEmpty());
    assertTrue(step.operationQueryRefs().isEmpty());
    assertTrue(step.dependsOn().isEmpty());
    assertTrue(step.requiredArtifactTypes().isEmpty());
    assertTrue(step.producedArtifactTypes().isEmpty());

    CatalogBindingResolutions resolutions = new CatalogBindingResolutions("1", null);
    assertTrue(resolutions.resolutions().isEmpty());

    DesignExecutionTrace trace = new DesignExecutionTrace("1", null);
    assertTrue(trace.entries().isEmpty());

    ApiOperationBindings apiBindings = new ApiOperationBindings("1", null);
    assertTrue(apiBindings.bindings().isEmpty());

    OrderedGraphPatches patches = new OrderedGraphPatches("1", null);
    assertTrue(patches.patchRefs().isEmpty());

    ExecutorValidationBundle executorValidation =
        new ExecutorValidationBundle(
            "1",
            "graph-digest",
            new Reference(Kind.DESIGN_PLAN_REPORT, "report-1", "report-hash"),
            "report-hash",
            new Reference(Kind.DESIGN_EXECUTION_PLAN, "plan-1", "plan-hash"),
            "plan-hash",
            true,
            null);
    assertTrue(executorValidation.findings().isEmpty());

    DesignExecutionCheckpoint checkpoint =
        new DesignExecutionCheckpoint(
            "1",
            new Reference(Kind.APPROVAL_RECORD, "apr-1", "apr-hash"),
            "report-hash",
            "plan-hash",
            "manifest-hash",
            DesignExecutionPhase.PRECONDITIONS,
            null);
    assertTrue(checkpoint.completedSteps().isEmpty());
  }

  @Test
  void blankIdsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignExecutionPlan.Step(
                " ",
                1,
                "text",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
  }

  private <T> T roundTrip(Kind kind, T payload) {
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) payload.getClass();
    Revision revision =
        store.append(
            new AppendCommand(
                RUN_ID, kind, "1", "test-producer", "1", payload, List.of(), null, sampleProvenance()));
    return artifacts.payload(revision, type);
  }

  private static ArtifactProvenance sampleProvenance() {
    return new ArtifactProvenance(
        RUN_ID,
        "design-planning",
        "create-chain",
        "2",
        "profile-sha256",
        "design-planning",
        "1",
        "closure-sha256");
  }

  private static DesignExecutionPlan sampleExecutionPlan() {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "ids-ref",
        "ids-hash",
        "2024.4",
        "CATALOG_FIRST_V1",
        List.of(
            new DesignExecutionPlan.Step(
                "step-1",
                1,
                "Create HTTP trigger",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-trigger-generator"),
                List.of(),
                List.of("p-client"),
                List.of(),
                List.of(),
                List.of("CHAIN_SEMANTIC_REVISION"),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "report-ref",
        "report-hash",
        java.util.Map.of("cip-design-planner", "skill-hash"),
        java.util.Map.of("cip-design-planner", "addon-hash"),
        "catalog-hash",
        "policy-hash");
  }

  private static ValidatedExecutionBundle sampleValidatedBundle() {
    return new ValidatedExecutionBundle(
        "1",
        new Reference(Kind.APPROVAL_RECORD, "apr-1", "apr-hash"),
        new Reference(Kind.DESIGN_PLAN_REPORT, "report-1", "report-hash"),
        "report-hash",
        new Reference(Kind.DESIGN_EXECUTION_PLAN, "plan-1", "plan-hash"),
        "plan-hash",
        new Reference(Kind.RUN_MANIFEST, "manifest-1", "manifest-hash"),
        new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "graph-hash"),
        "graph-digest",
        new Reference(Kind.ORDERED_GRAPH_PATCHES, "patches-1", "patches-hash"),
        "patch-digest",
        new Reference(Kind.PLAN_VALIDATION_RESULT, "graph-val-1", "graph-val-hash"),
        new Reference(Kind.PLAN_VALIDATION_RESULT, "plan-val-1", "plan-val-hash"),
        new Reference(Kind.COMPILER_VALIDATION_BUNDLE, "comp-val-1", "comp-val-hash"),
        new Reference(Kind.EXECUTOR_VALIDATION_BUNDLE, "exec-val-1", "exec-val-hash"));
  }
}

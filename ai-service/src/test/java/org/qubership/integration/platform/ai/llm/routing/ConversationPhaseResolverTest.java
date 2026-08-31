package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class ConversationPhaseResolverTest {

  private static final String CONVERSATION_ID = "conv-1";

  @Test
  void noPlanOrDraftResolvesToCold() {
    ConversationPhaseResolver resolver = PlanCompilationTestSupport.memory().phaseResolver();

    assertEquals(ConversationPhase.COLD, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void incompleteDraftResolvesToDiscovery() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(CONVERSATION_ID, new RequirementDraft(false, "Call API getOrder"));
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.DISCOVERY, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void pendingApiHubCandidateResolvesToImportPending() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(
            CONVERSATION_ID,
            new RequirementDraft(
                false,
                "GeoSite proxy",
                DraftDecision.NEEDS_INPUT,
                List.of(),
                null,
                null,
                null,
                new ApiHubRequirementRefs(
                    "S.CustParty.Care.GeoSite",
                    "2026.2@1",
                    "geographicSiteManagement-v4-geographicSite-_id_-get",
                    "api",
                    "rest",
                    null,
                    null),
                false,
                List.of(),
                true,
                "call-1"));
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.IMPORT_PENDING, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void importIntentWithoutCandidateStaysInDiscovery() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(
            CONVERSATION_ID,
            new RequirementDraft(false, "Import GeoSite when refs are known")
                .withImportIntent(true));
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.DISCOVERY, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void catalogBindingClearsImportPendingEvenWithCandidate() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(
            CONVERSATION_ID,
            boundDraft());
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.PLAN_DRAFT, resolver.resolve(CONVERSATION_ID));
  }

  private static RequirementDraft boundDraft() {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("http-in", Direction.INBOUND, "Caller", "GET /proxy", ""),
                new Interaction("call-1", Direction.OUTBOUND, "GeoSite", "getGeographicSite", "")),
            List.of(new Transition("http-in", "call-1")));
    return new RequirementDraft(
            true,
            "GeoSite proxy",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            null,
            new ApiHubRequirementRefs(
                "S.CustParty.Care.GeoSite",
                "2026.2@1",
                "op-get",
                "api",
                "rest",
                null,
                null),
            false,
            List.of(),
            false,
            "call-1")
        .withFlow(flow)
        .withBoundServiceCall(
            "call-1",
            new CatalogBindingHint(
                CatalogBindingHint.SCHEMA_VERSION,
                "call-1",
                "call-1",
                "getGeographicSite",
                "sys-1",
                "group-1",
                "spec-1",
                "op-1",
                "http",
                "GET",
                "/geographicSite",
                "catalog",
                Instant.EPOCH,
                "test"));
  }

  @Test
  void readyDecisionWithOpenQuestionsResolvesToDiscovery() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(
            CONVERSATION_ID,
            new RequirementDraft(
                true,
                "Almost full vision",
                DraftDecision.READY_FOR_PLAN,
                List.of("Which operation should be used?"),
                "brainstorming",
                "test"));
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.DISCOVERY, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void completeDraftResolvesToPlanDraftWithoutLegacyDesignAuthority() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(CONVERSATION_ID, RequirementFactFixtures.readyDraft("Full vision"));
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.PLAN_DRAFT, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void legacyPlanAndBundleArtifactsDoNotAdvancePhaseWithoutProductRun() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    runtime
        .requirementDraftStore()
        .put(CONVERSATION_ID, RequirementFactFixtures.readyDraft("Full vision"));
    PlanCompilationTestSupport.storeGraph(
        runtime, CONVERSATION_ID, PlanCompilationTestSupport.sampleGraph("Greetings"));
    ConversationPhaseResolver resolver = runtime.phaseResolver();

    assertEquals(ConversationPhase.PLAN_DRAFT, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void productPipelineImportStageMapsToImportPending() {
    CreateProductPipelineCoordinator coordinator = mock(CreateProductPipelineCoordinator.class);
    ProductPipelineRunDocument document =
        new ProductPipelineRunDocument(
            new RunSnapshot(
                "run-1",
                CONVERSATION_ID,
                1L,
                RunStatus.WAITING_FOR_INPUT,
                "import-stage",
                List.of(),
                null),
            List.of(),
            List.of(),
            "v1");
    when(coordinator.loadRun(CONVERSATION_ID)).thenReturn(Optional.of(document));

    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    ConversationPhaseResolver resolver =
        new ConversationPhaseResolver(runtime.requirementDraftStore(), coordinator);

    assertEquals(ConversationPhase.IMPORT_PENDING, resolver.resolve(CONVERSATION_ID));
  }

  @Test
  void productPipelinePlanApprovedMapsToPlanApproved() {
    CreateProductPipelineCoordinator coordinator = mock(CreateProductPipelineCoordinator.class);
    ProductPipelineRunDocument document =
        new ProductPipelineRunDocument(
            new RunSnapshot(
                "run-1",
                CONVERSATION_ID,
                1L,
                RunStatus.WAITING_FOR_IMPLEMENT,
                "planning",
                List.of(),
                null),
            List.of(),
            List.of(),
            "v1");
    when(coordinator.loadRun(CONVERSATION_ID)).thenReturn(Optional.of(document));

    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    ConversationPhaseResolver resolver =
        new ConversationPhaseResolver(runtime.requirementDraftStore(), coordinator);

    assertEquals(ConversationPhase.PLAN_APPROVED, resolver.resolve(CONVERSATION_ID));
  }
}

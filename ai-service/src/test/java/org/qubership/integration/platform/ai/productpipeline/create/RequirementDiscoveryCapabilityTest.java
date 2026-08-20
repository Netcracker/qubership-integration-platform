package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.ConversationCatalogBindings;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class RequirementDiscoveryCapabilityTest {

  @Test
  void greetingsDraftPreservesThirteenNegativeFacts() {
    List<RequirementFact> negatives = RequirementFactFixtures.greetingsNegativeFacts();
    assertEquals(13, negatives.size());

    RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
    assertEquals(13, draft.facts().stream().filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE).count());

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, draft);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(draft);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-greetings-facts",
            "conv-greetings-facts",
            "requirement-discovery",
            "exec-greetings-facts",
            "attempt-greetings-facts",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    RequirementDraft candidate =
        (RequirementDraft) completed.get().outcome().candidates().get(0).payload();
    assertEquals(
        13,
        candidate.facts().stream().filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE).count());
  }

  @Test
  void conversationalAskWithParaphrasedReadyDraftAdvancesToCandidate() {
    String userAsk =
        "Hi, create a chain. It has to receive HTTP GET \"/hello\" and return string \"Good day\"";
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "HTTP GET /hello returns string 'Good day'.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.ENDPOINT,
                    "http-trigger",
                    "HTTP GET /hello"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.BEHAVIOR,
                    "response",
                    "returns string 'Good day'")));

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, draft);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(draft);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-hello",
            "conv-hello",
            "requirement-discovery",
            "exec-hello",
            "attempt-hello",
            null,
            null,
            List.of(),
            Map.of("userText", userAsk, "discoveryUserText", userAsk));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    assertFalse(completed.get().outcome().message().contains("transcript line absent"));
  }

  @Test
  void discoveryAdvancesWhenDraftReadyDespiteFollowUpClarificationText() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-clarify",
            "conv-clarify",
            "requirement-discovery",
            "exec-clarify",
            "attempt-clarify",
            null,
            null,
            List.of(),
            Map.of(
                "userText",
                "Yes, use a Groovy script for the response.",
                "discoveryUserText",
                RequirementFactFixtures.GREETINGS_PROMPT));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
  }

  @Test
  void discoveryCapabilityEmitsBrainstormingSkillProgress() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-skill-activity",
            "conv-skill-activity",
            "requirement-discovery",
            "exec-skill",
            "attempt-skill",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();

    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && "brainstorming".equals(sp.skillId())
                        && "running".equals(sp.status())));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && "brainstorming".equals(sp.skillId())
                        && "completed".equals(sp.status())));
    assertTrue(
        signals.stream().anyMatch(CapabilitySignal.Completed.class::isInstance));
  }

  @Test
  void discoveryCapabilityEmitsDraftCandidateWithFacts() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-discovery",
            "requirement-discovery",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    RequirementDraft candidate =
        (RequirementDraft) completed.get().outcome().candidates().get(0).payload();
    assertEquals(13, candidate.facts().stream().filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE).count());
  }

  @Test
  void discoveryCapabilityWrapsUserTextWithBrainstormingAddon() {
    RequirementDraftStore store = new RequirementDraftStore();
    GatherRequirementsAgent agent = mock(GatherRequirementsAgent.class);
    CompilerSkillDocumentService skillDocumentService = mock(CompilerSkillDocumentService.class);
    CompilerSkillAddonRepository addonRepository = mock(CompilerSkillAddonRepository.class);
    when(skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(
            new CompilerSkillDocument(
                "brainstorming",
                "brainstorming",
                "skills/brainstorming/SKILL.md",
                "Brainstorming Ideas Into Designs",
                QipKnowledgeCapabilityPhase.UNSUPPORTED,
                false,
                new QipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2"),
                "# Brainstorming Ideas Into Designs\n"));
    when(addonRepository.loadForSkill(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(
            new CompilerSkillAddonContext(
                List.of(),
                new CompilerSkillAddonDocument(
                    "skills/brainstorming.addon.md",
                    "Every READY_FOR_PLAN capture must include explicit `facts`."),
                List.of()));
    when(agent.chat(any(), any())).thenReturn(Multi.createFrom().empty());

    GatherRequirementsPromptBuilder promptBuilder =
        new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, store);
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(agent, store, promptBuilder);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-wrap",
            "conv-wrap",
            "requirement-discovery",
            "exec-wrap",
            "attempt-wrap",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    capability.execute(context).subscribe().with(signal -> {});

    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(agent).chat(eq("conv-wrap"), input.capture());
    assertTrue(input.getValue().contains("<compiler-process-skill id=\"brainstorming\""));
    assertTrue(input.getValue().contains("explicit `facts`"));
    assertTrue(input.getValue().contains(RequirementFactFixtures.GREETINGS_PROMPT));
  }

  @Test
  void discoverySkipsCatalogBindingHintWithoutOptionalProducesDeclaration() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-1", "findPetsByStatus", "GET", "/pets", "spec-1")));

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = petstoreServiceCallDraft();
    RequirementDiscoveryCapability capability =
        discoveryWithMatcher(store, approved, new CatalogBindingMatcher(catalogReadTool));

    // create-chain@1 shape: produces requirement-draft only — no catalog-binding-hint declaration.
    ProductPipelineProfile v1Like =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)), List.of());

    StageExecutionContext context =
        new StageExecutionContext(
            "run-v1-safe",
            "conv-v1-safe",
            "requirement-discovery",
            "exec-v1-safe",
            "attempt-v1-safe",
            v1Like,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(1, candidates.size());
    assertEquals(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, candidates.get(0).kind());
  }

  @Test
  void discoveryEmitsCatalogBindingHintOnlyForExactLocalMatch() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-1", "findPetsByStatus", "GET", "/pets", "spec-1")));

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = petstoreServiceCallDraft();
    RequirementDiscoveryCapability capability =
        discoveryWithMatcher(store, approved, new CatalogBindingMatcher(catalogReadTool));

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-hint",
            "conv-hint",
            "requirement-discovery",
            "exec-hint",
            "attempt-hint",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(2, candidates.size());
    assertEquals(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, candidates.get(0).kind());
    assertEquals(CompilationArtifacts.Kind.CATALOG_BINDING_HINT, candidates.get(1).kind());
    CatalogBindingHint hint = (CatalogBindingHint) candidates.get(1).payload();
    assertEquals("sys-1", hint.systemId());
    assertEquals("op-1", hint.integrationOperationId());
  }

  @Test
  void discoveryCatalogMissDoesNotCallApiHubOrEmitHint() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Call UnknownSvc GET /x.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create chain"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.SERVICE_CALL,
                    "UnknownSvc",
                    "GET /x")));
    RequirementDiscoveryCapability capability =
        discoveryWithMatcher(store, approved, new CatalogBindingMatcher(catalogReadTool));

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-miss",
            "conv-miss",
            "requirement-discovery",
            "exec-miss",
            "attempt-miss",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call UnknownSvc GET /x."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    assertEquals(1, completed.get().outcome().candidates().size());
    assertEquals(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
        completed.get().outcome().candidates().get(0).kind());
    verifyNoInteractions(apiHub, gateway);
  }

  @Test
  void incompleteDraftNeedsInputWithoutReadyForPlanLeak() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft incomplete =
        new RequirementDraft(
            false,
            "Check pending payments somehow",
            DraftDecision.NEEDS_INPUT,
            List.of("Which service or API should check pending payments?"),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of());
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, incomplete);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(incomplete);
              return Multi.createFrom()
                  .item(
                      org.qubership.integration.platform.ai.chat.ChatEvent.token(
                          "To proceed, could you please specify which service or API should be"
                              + " used to check for pending payments?"));
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-needs-input",
            "conv-needs-input",
            "requirement-discovery",
            "exec-needs-input",
            "attempt-needs-input",
            null,
            null,
            List.of(),
            Map.of("userText", "Check pending payments"));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.get().outcome().outcomeClass());
    assertEquals("", completed.get().outcome().message());
    assertFalse(completed.get().outcome().message().contains("READY_FOR_PLAN"));
  }

  @Test
  void discoveryBindsToolSessionBeforeGatherWithoutChatExecutionService() {
    RequirementDraftStore store = new RequirementDraftStore();
    AtomicReference<String> observedConversationId = new AtomicReference<>();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              observedConversationId.set(ToolSession.resolveConversationId());
              return Multi.createFrom().item(ChatEvent.token("gathering"));
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-tool-session",
            "conv-tool-session",
            "requirement-discovery",
            "exec-tool-session",
            "attempt-tool-session",
            null,
            null,
            List.of(),
            Map.of("userText", "HTTP GET /hello returns Good day"));

    capability.execute(context).collect().asList().await().indefinitely();

    assertEquals("conv-tool-session", observedConversationId.get());
    assertNull(ToolSession.resolveConversationId());
  }

  /**
   * The approved binding reaches the hint even when no rule can read the fact that carries it.
   *
   * <p>Fact text and ids are the ones from the run that sent {@code GET /store/inventory.} — the
   * trailing period included — to APIHub and got a gateway timeout back.
   */
  @Test
  void discoveryEmitsApprovedDraftBindingWithoutReadingTheCatalog() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact serviceCall =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Petstore Ext",
            "Call Petstore Ext catalog operation getInventory: GET /store/inventory.");
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Call the Petstore Ext getInventory operation.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            new ResolvedCatalogBinding(
                "bbf14771-de8d-48e8-a2ed-2e691f7f6eff",
                "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7",
                "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-sg",
                "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7-getInventory",
                "EXTERNAL"),
            false,
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create a chain named Pet Inventory Check"),
                serviceCall));
    RequirementDiscoveryCapability capability =
        discoveryWithMatcher(store, approved, new CatalogBindingMatcher(catalogReadTool));

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-approved-binding",
            "conv-approved-binding",
            "requirement-discovery",
            "exec-approved-binding",
            "attempt-approved-binding",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call the Petstore Ext getInventory operation."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(2, candidates.size());
    assertEquals(CompilationArtifacts.Kind.CATALOG_BINDING_HINT, candidates.get(1).kind());
    CatalogBindingHint hint = (CatalogBindingHint) candidates.get(1).payload();
    assertEquals("bbf14771-de8d-48e8-a2ed-2e691f7f6eff", hint.systemId());
    assertEquals(
        "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7-getInventory",
        hint.integrationOperationId());
    // The step the hint binds to is found by fact id, so the wording of the fact never decides.
    assertEquals(serviceCall.sourceFactId(), hint.serviceCallSourceFactId());
    verifyNoInteractions(catalogReadTool);
  }

  /**
   * Each outbound call keeps the binding gathering resolved for it, with no catalog read left.
   *
   * <p>Two services share one draft binding, so the draft alone cannot say which call it belongs
   * to. The resolutions recorded during gathering can.
   */
  @Test
  void discoveryPairsEveryServiceCallWithItsOwnResolvedBinding() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);
    ConversationCatalogBindings bindings = new ConversationCatalogBindings();
    bindings.remember(
        "conv-two-services",
        new CatalogBindingMatcher.CatalogMatch(
            "sys-pet", "sg-pet", "spec-pet", "op-inventory", "Petstore Ext", "http", "GET",
            "/store/inventory", "getInventory", "catalog-read:pet"));
    bindings.remember(
        "conv-two-services",
        new CatalogBindingMatcher.CatalogMatch(
            "sys-bill", "sg-bill", "spec-bill", "op-invoice", "Billing", "http", "POST",
            "/invoices", "createInvoice", "catalog-read:bill"));

    RequirementFact inventoryCall =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Petstore Ext",
            "Call Petstore Ext catalog operation getInventory: GET /store/inventory.");
    RequirementFact invoiceCall =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Billing",
            "Then post the invoice through Billing using POST /invoices.");

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Check inventory, then raise an invoice.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            new ResolvedCatalogBinding("sys-pet", "spec-pet", "sg-pet", "op-inventory", "EXTERNAL"),
            false,
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create an inventory-to-invoice chain"),
                inventoryCall,
                invoiceCall));

    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            },
            new CatalogBindingMatcher(catalogReadTool),
            bindings);

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-two-services",
            "conv-two-services",
            "requirement-discovery",
            "exec-two-services",
            "attempt-two-services",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Check inventory, then raise an invoice."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(3, candidates.size());
    CatalogBindingHint first = (CatalogBindingHint) candidates.get(1).payload();
    CatalogBindingHint second = (CatalogBindingHint) candidates.get(2).payload();
    assertEquals(inventoryCall.sourceFactId(), first.serviceCallSourceFactId());
    assertEquals("op-inventory", first.integrationOperationId());
    assertEquals("sys-pet", first.systemId());
    assertEquals(invoiceCall.sourceFactId(), second.serviceCallSourceFactId());
    assertEquals("op-invoice", second.integrationOperationId());
    assertEquals("sys-bill", second.systemId());
    verifyNoInteractions(catalogReadTool);
  }

  private static RequirementDraft petstoreServiceCallDraft() {
    return new RequirementDraft(
        true,
        "Call Petstore Ext GET /pets for pending pets.",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        null,
        false,
        List.of(
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.GOAL,
                "chain",
                "Create pending pets chain"),
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.SERVICE_CALL,
                "Petstore Ext",
                "GET /pets")));
  }

  private static RequirementDiscoveryCapability discoveryWithMatcher(
      RequirementDraftStore store,
      RequirementDraft approved,
      CatalogBindingMatcher matcher) {
    return new RequirementDiscoveryCapability(
        null,
        store,
        null,
        (conversationId, userText) -> {
          store.beginTurn(conversationId);
          store.put(conversationId, approved);
          store.markCaptured(conversationId);
          ProductCapabilityCaptureContext.offerDraft(approved);
          return Multi.createFrom().empty();
        },
        matcher);
  }

  private static ProductPipelineProfile discoveryProfile(
      List<ArtifactTypeRef> produces, List<ArtifactTypeRef> optionalProduces) {
    return new ProductPipelineProfile(
        1,
        "fixture-discovery",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-discovery",
                "requirement-discovery",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                produces,
                optionalProduces,
                new ApprovalPolicy(new ArtifactTypeRef("requirement-draft", 2)),
                null,
                new RetryPolicy(0, 1000L),
                null)),
        new TerminalPolicy("requirement-discovery", "PLAN_APPROVED"),
        List.of("requirement-discovery"));
  }
}

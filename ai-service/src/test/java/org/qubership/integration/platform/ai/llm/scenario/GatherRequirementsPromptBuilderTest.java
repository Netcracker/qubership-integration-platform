package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

class GatherRequirementsPromptBuilderTest {

  private CompilerSkillDocumentService skillDocumentService;
  private CompilerSkillAddonRepository addonRepository;
  private RequirementDraftStore draftStore;
  private GatherRequirementsPromptBuilder builder;

  @BeforeEach
  void setUp() {
    skillDocumentService = mock(CompilerSkillDocumentService.class);
    addonRepository = mock(CompilerSkillAddonRepository.class);
    draftStore = new RequirementDraftStore();
    when(skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(brainstormingDocument());
    when(addonRepository.loadForSkill(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(brainstormingAddon());
    builder =
        new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, draftStore);
  }

  @Test
  void failedSkillLoadingKeepsTheDraftPendingSourcesAndQuestion() {
    ConversationService conversations = new ConversationService();
    conversations.addMessage("conv-1", org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.user(
        "Receive work orders and do not log their bodies."));
    conversations.addMessage("conv-1", org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.assistant(
        "Use the existing operation? Options: existing, custom."));
    conversations.addMessage("conv-1", org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.user("existing"));
    draftStore.put("conv-1", RequirementFactFixtures.greetingsApprovedDraft());
    when(skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenThrow(new IllegalStateException("skill unavailable"));
    builder = new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, draftStore, conversations);
    String input = builder.wrap("conv-1", "existing", "en");
    assertTrue(input.contains("Receive work orders and do not log their bodies."), input);
    assertTrue(input.contains("Options: existing, custom."), input);
    assertTrue(input.contains("No service calls"), input);
    assertTrue(input.contains("Selected revision:"), input);
    assertTrue(input.contains("authorApproved=false"), input);
    assertFalse(input.contains("Current author message:"), input);
    assertTrue(input.contains("Apply one author amendment atomically to every affected representation"), input);
    assertTrue(input.contains("operation, description, or fact text that repeats that URL"), input);
  }

  @Test
  void wrapIncludesProcessSkillAndAddonFactsContract() {
    String input = builder.wrap("conv-1", "Create chain named Greetings via script", "en");

    assertTrue(input.contains("<compiler-process-skill id=\"brainstorming\""));
    assertTrue(input.contains("Brainstorming Ideas Into Designs"));
    assertTrue(input.contains("Compiler skill addon (skills/brainstorming.addon.md):"));
    assertTrue(input.contains("explicit `facts`"));
    assertTrue(input.contains("QIP platform defaults"));
    assertTrue(input.contains("Follow the compiler process skill and the brainstorming addon"));
    assertTrue(input.contains("pinned response locale en"));
    assertTrue(input.contains("Create chain named Greetings via script"));
    assertTrue(input.contains("resolveApiOperation"));
    assertTrue(input.contains("Capture known requirements before catalog lookup"), input);
    assertTrue(input.contains("searchCatalogSystems does not bind"));
    assertTrue(input.contains("After the draft is stored"), input);
    assertTrue(input.contains("only for catalog-backed interactions"), input);
    assertTrue(input.contains("do not invent a sender key"), input);
    assertTrue(input.contains("sftp-trigger-2"), input);
    assertTrue(input.contains("sftp-upload"), input);
    assertTrue(input.contains("mail-sender"), input);
    assertTrue(input.contains("mcp-trigger"), input);
    assertTrue(input.contains("skips catalog lookup"), input);
    assertFalse(input.contains("not supported in create-chain"), input);
    assertTrue(input.contains("Custom HTTP"), input);
    assertTrue(input.contains("Implemented service HTTP"), input);
    assertTrue(input.contains("Native triggers and direct elements"), input);
    assertTrue(input.contains("save the known interactions and facts as a partial"), input);
    assertFalse(input.contains("after you pick a catalog operation"), input);
    assertFalse(input.contains("with serviceCallId"), input);
    assertFalse(input.contains("searchCatalogSystems, getApiSpecifications, and listCatalogOperations"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void uploadedImportGuidanceOnlyAppliesToMatchingOperations(boolean matchingOperation) {
    var conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys("upload-chat", List.of("uploads/probe.json"));
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /probe", null, null, null),
        new InteractionInput("call", Direction.OUTBOUND, "Probe API", "getProbe", null, null, null)),
        List.of(new TransitionInput("entry", "call"))),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Read the probe.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/probe", null, null, null)),
        List.of(), new DraftSettings(false, null));
    draftStore.put("upload-chat", RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash"));
    builder = new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, draftStore, conversations);
    builder.uploadedSpecs = mock(org.qubership.integration.platform.ai.plan.UploadedSpecOperations.class);
    when(builder.uploadedSpecs.pendingInteractionIds(anyString(), any()))
        .thenReturn(matchingOperation ? java.util.Set.of("call") : java.util.Set.of());
    String prompt = builder.wrap("upload-chat", "Continue.", "en");
    if (matchingOperation) {
      assertTrue(prompt.contains("<pending-uploaded-spec-import>"), prompt);
      assertTrue(prompt.contains("nextAction=RUN_IMPORT"), prompt);
      assertFalse(prompt.contains("<incomplete-requirement-draft>"), prompt);
    } else {
      assertFalse(prompt.contains("<pending-uploaded-spec-import>"), prompt);
      assertTrue(prompt.contains("<incomplete-requirement-draft>"), prompt);
      assertTrue(prompt.contains("fill the missing fields before CONTINUE"), prompt);
    }
  }

  @Test
  void wrapTellsTheAgentNotToSearchApiHubWhenUploadedSpecsAreApproved() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "conv-1", List.of("sessions/conv/salesforce-wfm.json"));
    builder =
        new GatherRequirementsPromptBuilder(
            skillDocumentService, addonRepository, draftStore, conversations);

    String input = builder.wrap("conv-1", "Create OM to Salesforce WFM", "en");

    assertTrue(input.contains("already approved for catalog import after discovery"), input);
    assertTrue(input.contains("Do not search API Hub for operations from those specs"), input);
    assertTrue(input.contains("do not ask the reader to import or bind them"), input);
    assertTrue(input.contains("Capture the business flow from the attached document"), input);
    assertFalse(input.contains("Capture SERVICE_CALL facts"), input);
    assertFalse(input.contains("resolveApiOperation"), input);
  }

  @Test
  void wrapIncludesLastCaptureRejectionWhenDraftIsMissing() {
    draftStore.recordCaptureRejection(
        "conv-1",
        "duplicate sourceFactId in facts: CAPABILITY sourceFactId=om-on-task-start and"
            + " BEHAVIOR sourceFactId=om-on-task-start. Call captureRequirementDraft again with"
            + " unique sourceFactId values. Rename one colliding fact or drop the duplicate.");

    String input =
        builder.wrap(
            "conv-1",
            RequirementDraftTool.CAPTURE_MISSING_USER_GUIDANCE,
            "en");

    assertTrue(input.contains("<last-capture-rejection tool=\"captureRequirementDraft\">"), input);
    assertTrue(input.contains("CAPABILITY sourceFactId=om-on-task-start"), input);
    assertTrue(input.contains("Call captureRequirementDraft again"), input);
    assertTrue(input.contains(RequirementDraftTool.CAPTURE_MISSING_USER_GUIDANCE), input);
    assertFalse(input.contains("<current-requirement-draft"), input);
  }

  @Test
  void wrapKeepsDiscoveryContextWhenDraftIsAlreadyReadyForPlan() {
    draftStore.put("conv-1", RequirementFactFixtures.readyDraft("already ready"));

    String input = builder.wrap("conv-1", "More detail");

    assertTrue(input.contains("<compiler-process-skill"), input);
    assertTrue(input.contains("<current-requirement-draft decision=\"READY_FOR_PLAN\">"), input);
    assertTrue(input.contains("already ready"), input);
    assertTrue(input.contains("finishRequirementDiscoveryTurn"), input);
    assertTrue(input.contains("The service owns the IDS review choice and presents a Yes/No card"), input);
    assertTrue(input.contains("requesting a choice card is not a Yes answer"), input);
    assertTrue(input.contains("searchRequirementKnowledge"), input);
    assertTrue(input.contains("Do not show source names or identifiers"), input);
    assertTrue(input.contains("write exactly one final answer"), input);
    assertFalse(input.contains("Cite the returned source names"), input);
    assertTrue(input.contains("Capture accepted requirement changes only"), input);
    assertTrue(input.contains("STAY"), input);
    assertTrue(input.contains("CONTINUE"), input);
    assertFalse(input.contains("<last-capture-rejection"));
    assertTrue(input.contains("More detail"));
  }

  @Test
  void wrapListsServiceCallIdsFromTheCurrentDraft() {
    draftStore.put(
        "conv-1",
        new RequirementDraft(
                false,
                "Call OM then Salesforce WFM",
                DraftDecision.NEEDS_INPUT,
                List.of("Which operations?"),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "1",
                null,
                null,
                false,
                List.of())
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction("http-in", Direction.INBOUND, "Caller", "onTaskStart", ""),
                        new Interaction(
                            "call-om-result", Direction.OUTBOUND, "OM", "onTaskResult", ""),
                        new Interaction(
                            "call-wfm-create-task",
                            Direction.OUTBOUND,
                            "Salesforce WFM",
                            "createTask",
                            "")),
                    List.of(
                        new Transition("http-in", "call-om-result"),
                        new Transition("call-om-result", "call-wfm-create-task")))));

    String input = builder.wrap("conv-1", "Continue gathering", "en");

    assertTrue(input.contains("interactionId=call-om-result"), input);
    assertTrue(input.contains("interactionId=call-wfm-create-task"), input);
    assertTrue(input.contains("participant=OM"), input);
    assertTrue(input.contains("operation=onTaskResult"), input);
    assertTrue(input.contains("resolved=false"), input);
    assertTrue(input.contains("reuse interactionId"), input);
    assertFalse(input.contains("sys-"), input);
  }

  @Test
  void wrapNamesMissingInteractionFieldsWhenTheSavedDraftIsPartial() {
    draftStore.put(
        "conv-1",
        RequirementCaptureProjection.toDraft(partialKafkaSenderDraft(), null, List.of(), "1", "hash"));

    String input = builder.wrap("conv-1", "yes", "en");

    assertTrue(input.contains("<incomplete-requirement-draft>"), input);
    assertTrue(input.contains("http-trigger is missing operation"), input);
    assertTrue(input.contains("script-set-body is missing direction and participant"), input);
    assertTrue(input.contains("kafka-sender is missing operation"), input);
    assertTrue(input.contains("use updateRequirementDraft"), input);
    assertTrue(input.contains("Do not tell the user the draft is ready"), input);
  }

  private static DraftInput partialKafkaSenderDraft() {
    return new DraftInput(
        new FlowInput(
            List.of(
                new InteractionInput(
                    "http-trigger", Direction.INBOUND, "Caller", null, null, null, null),
                new InteractionInput(
                    "script-set-body", null, null, "Set body", null, null, null),
                new InteractionInput(
                    "kafka-sender", Direction.OUTBOUND, "Kafka", null, null, null, null)),
            List.of(
                new TransitionInput("http-trigger", "script-set-body"),
                new TransitionInput("script-set-body", "kafka-sender"))),
        List.of(new FactInput(
            "goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Send a Kafka message.")),
        List.of(
            new CapabilityInput(
                "http-trigger", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
                "/auto-tests/kafka-sender", null, null, null),
            new CapabilityInput(
                "kafka-sender", "kafka-sender-2", null, null, null,
                "cip-auto-tests-topic1", null, null)),
        List.of(),
        new DraftSettings(null, null));
  }

  private static CompilerSkillAddonContext brainstormingAddon() {
    return new CompilerSkillAddonContext(
        List.of(),
        new CompilerSkillAddonDocument(
            "skills/brainstorming.addon.md",
            """
            # brainstorming addon

            ## QIP platform defaults

            - Script steps use the QIP `script` element with Groovy.

            Every `READY_FOR_PLAN` capture must include explicit `facts` with stable polarity.
            """),
        List.of());
  }

  private static CompilerSkillDocument brainstormingDocument() {
    return new CompilerSkillDocument(
        "brainstorming",
        "brainstorming",
        "skills/brainstorming/SKILL.md",
        "Brainstorming Ideas Into Designs",
        CipKnowledgeCapabilityPhase.UNSUPPORTED,
        false,
        new CipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2"),
        "# Brainstorming Ideas Into Designs\n");
  }
}

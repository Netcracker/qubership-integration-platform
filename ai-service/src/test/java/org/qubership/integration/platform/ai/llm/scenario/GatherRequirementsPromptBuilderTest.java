package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

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
    assertTrue(input.contains("searchCatalogSystems does not bind"));
    assertFalse(input.contains("searchCatalogSystems, getApiSpecifications, and listCatalogOperations"));
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
  }

  @Test
  void wrapSkipsProcessSkillWhenDraftAlreadyReadyForPlan() {
    draftStore.put("conv-1", new RequirementDraft(true, "already ready"));

    String input = builder.wrap("conv-1", "More detail");

    assertFalse(input.contains("<compiler-process-skill"));
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
            List.of(
                serviceCall("call-om-result", "OM", "onTaskResult"),
                serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")),
            false));

    String input = builder.wrap("conv-1", "Continue gathering", "en");

    assertTrue(input.contains("serviceCallId=call-om-result"), input);
    assertTrue(input.contains("serviceCallId=call-wfm-create-task"), input);
    assertTrue(input.contains("sourceFactId=call-om-result"), input);
    assertTrue(input.contains("participant=OM"), input);
    assertTrue(input.contains("operation=onTaskResult"), input);
    assertTrue(input.contains("resolved=false"), input);
    assertTrue(input.contains("reuse serviceCallId"), input);
    assertFalse(input.contains("sys-"), input);
  }

  private static RequirementFact serviceCall(
      String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        serviceCallId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
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
        QipKnowledgeCapabilityPhase.UNSUPPORTED,
        false,
        new QipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2"),
        "# Brainstorming Ideas Into Designs\n");
  }
}

package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.AddonPromptMaterialStripper;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;

/**
 * Builds the gather-agent user message for both the legacy GATHER_REQUIREMENTS scenario and the
 * product CREATE requirement-discovery capability. Wraps the raw user text with the brainstorming
 * process skill and ai-service addon so READY_FOR_PLAN captures include explicit facts.
 */
@ApplicationScoped
public class GatherRequirementsPromptBuilder {

  private static final Logger LOG = Logger.getLogger(GatherRequirementsPromptBuilder.class);

  private final CompilerSkillDocumentService skillDocumentService;
  private final CompilerSkillAddonRepository addonRepository;
  private final RequirementDraftStore draftStore;
  private final ConversationService conversationService;

  @Inject
  public GatherRequirementsPromptBuilder(
      CompilerSkillDocumentService skillDocumentService,
      CompilerSkillAddonRepository addonRepository,
      RequirementDraftStore draftStore,
      ConversationService conversationService) {
    this.skillDocumentService = skillDocumentService;
    this.addonRepository = addonRepository;
    this.draftStore = draftStore;
    this.conversationService = conversationService;
  }

  public GatherRequirementsPromptBuilder(
      CompilerSkillDocumentService skillDocumentService,
      CompilerSkillAddonRepository addonRepository,
      RequirementDraftStore draftStore) {
    this(skillDocumentService, addonRepository, draftStore, null);
  }

  /**
   * Returns the agent input for one gather turn. When the draft is already READY_FOR_PLAN, returns
   * the escaped raw user message so continuation turns stay short.
   */
  public String wrap(String conversationId, String userMessage) {
    return wrap(conversationId, userMessage, "en");
  }

  public String wrap(String conversationId, String userMessage, String responseLocale) {
    if (skillDocumentService == null) {
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(userMessage);
    }
    Optional<RequirementDraft> draft =
        draftStore != null ? draftStore.get(conversationId) : Optional.empty();
    if (draft.isPresent() && draft.get().readyForPlan()) {
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(userMessage);
    }
    try {
      CompilerSkillDocument document =
          skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID);
      String body =
          """
          <compiler-process-skill id="%s" version="%s" source="%s">
          %s
          </compiler-process-skill>

          <service-runtime-envelope>
          Follow the compiler process skill and the brainstorming addon below for requirement
          discovery behavior (catalog/API Hub for non-uploaded outbound calls, capture decisions,
          facts, platform defaults, clarifying-question overrides). Do not write files, commit
          changes, invoke implementation skills, or run the compiler spine. Call
          captureRequirementDraft every turn. Reply in the pinned response locale %s. This locale is
          authoritative; do not infer another language from conversation history or embedded text.

          Uploaded-spec override: when a SERVICE_CALL fact describes an uploaded OpenAPI/AsyncAPI
          specification (its text starts with "Uploaded "), do NOT call resolveApiOperation, do NOT
          search API Hub, and do NOT mention API Hub. The product pipeline imports uploaded
          specifications automatically after reader approval. Capture the fact in the uploaded-spec
          form and set READY_FOR_PLAN with empty openQuestions when the rest of the brief is clear.
          </service-runtime-envelope>
          %s
          %s
          %s
          <user-message>
          %s
          </user-message>
          """
              .formatted(
                  document.sourceSkillId(),
                  document.packVersion().normalized(),
                  document.sourcePath(),
                  document.markdown(),
                  normalizedLocale(responseLocale),
                  addonBlock(),
                  currentDraftBlock(draft),
                  attachmentList(conversationId),
                  userMessage != null ? userMessage : "");
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Failed to load process skill %s; using raw gather input",
          RequirementDraftTool.SOURCE_SKILL_ID);
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(userMessage);
    }
  }

  private static String normalizedLocale(String responseLocale) {
    return responseLocale == null || responseLocale.isBlank() ? "en" : responseLocale.trim();
  }

  private String addonBlock() {
    if (addonRepository == null) {
      return "";
    }
    CompilerSkillAddonContext addon =
        addonRepository.loadForSkill(RequirementDraftTool.SOURCE_SKILL_ID);
    if (!addon.hasContent()) {
      return "";
    }
    StringBuilder body = new StringBuilder();
    for (CompilerSkillAddonDocument addonDocument : addon.globalDocuments()) {
      body.append("ai-service runtime addon (")
          .append(addonDocument.relativePath())
          .append("):\n");
      body.append(addonDocument.content()).append("\n\n");
    }
    if (addon.skillAddon() != null) {
      String promptMaterial =
          AddonPromptMaterialStripper.stripForPrompt(addon.skillAddon().content());
      if (!promptMaterial.isBlank()) {
        body.append("Compiler skill addon (")
            .append(addon.skillAddon().relativePath())
            .append("):\n");
        body.append(promptMaterial).append("\n\n");
      }
    }
    return body.toString();
  }

  private static String currentDraftBlock(Optional<RequirementDraft> draft) {
    if (draft.isEmpty()) {
      return "";
    }
    RequirementDraft current = draft.get();
    String openQuestions =
        current.openQuestions().isEmpty()
            ? ""
            : current.openQuestions().stream()
                .map(question -> "- " + question)
                .reduce("\nOpen questions:\n", (left, right) -> left + right + "\n");
    return """

        <current-requirement-draft decision="%s">
        %s%s
        </current-requirement-draft>
        """
        .formatted(current.decision(), current.assembledText(), openQuestions);
  }

  private String attachmentList(String conversationId) {
    if (conversationService == null || conversationId == null) {
      return "";
    }
    List<String> allowedKeys = conversationService.getAllowedAttachmentKeys(conversationId);
    if (allowedKeys == null || allowedKeys.isEmpty()) {
      return "";
    }
    StringBuilder body = new StringBuilder();
    body.append("User uploaded the following API specifications:\n");
    for (String key : allowedKeys) {
      if (key == null) {
        continue;
      }
      String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
      body.append("- ").append(filename).append("\n");
    }
    return body.toString();
  }
}

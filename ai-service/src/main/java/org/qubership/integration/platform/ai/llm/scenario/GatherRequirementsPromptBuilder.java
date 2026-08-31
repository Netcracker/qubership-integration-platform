package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.AddonPromptMaterialStripper;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

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
          discovery behavior (catalog/API Hub, capture decisions, facts, platform defaults,
          clarifying-question overrides). Do not write files, commit changes, invoke implementation
          skills, or run the compiler spine. Call captureRequirementDraft every turn.
          Capture RequirementFlow before catalog lookup. searchCatalogSystems does not bind an
          interaction; after the flow is stored, call resolveApiOperation with the interactionId from the stored flow.%s Reply in the
          pinned response locale %s. This
          locale is authoritative; do not infer another language from conversation history or
          embedded text.
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
                  uploadedSpecGuidance(conversationId),
                  normalizedLocale(responseLocale),
                  addonBlock(),
                  currentDraftBlock(draft),
                  lastCaptureRejectionBlock(conversationId, draft),
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

  /**
   * When the reader already attached specs and approved import, discovery must not block on a
   * catalog miss or search API Hub for those operations. Import runs after this stage.
   */
  private String uploadedSpecGuidance(String conversationId) {
    if (conversationService == null || conversationId == null || conversationId.isBlank()) {
      return "";
    }
    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    if (keys == null || keys.isEmpty()) {
      return "";
    }
    return " This conversation has uploaded API specifications that are already approved for"
        + " catalog import after discovery. Do not search API Hub for operations from those specs,"
        + " and do not ask the reader to import or bind them. Capture the business flow from the"
        + " attached document, including participants and operations, without ENDPOINT or"
        + " SERVICE_CALL facts. Set READY_FOR_PLAN when the flow and constraints are complete."
        + " Catalog lookup may miss until import runs.";
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

  private String lastCaptureRejectionBlock(
      String conversationId, Optional<RequirementDraft> draft) {
    if (draft.isPresent() || draftStore == null || conversationId == null) {
      return "";
    }
    return draftStore
        .lastCaptureRejection(conversationId)
        .map(
            message ->
                """

                <last-capture-rejection tool="captureRequirementDraft">
                %s
                </last-capture-rejection>
                """.formatted(message))
        .orElse("");
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
        %s%s%s
        </current-requirement-draft>
        """
        .formatted(
            current.decision(), current.assembledText(), openQuestions, interactionsBlock(current));
  }

  private static String interactionsBlock(RequirementDraft current) {
    if (current.flow().interactions().isEmpty()) {
      return "";
    }
    StringBuilder body =
        new StringBuilder(
            "\nBusiness interactions (reuse interactionId when editing the same semantic"
                + " interaction; allocate a new id only for a new occurrence):\n");
    for (RequirementFlow.Interaction interaction : current.flow().interactions()) {
      boolean resolved =
          current.catalogBindings().stream()
              .anyMatch(hint -> interaction.interactionId().equals(hint.interactionId()));
      body.append("- interactionId=")
          .append(interaction.interactionId())
          .append(", direction=")
          .append(interaction.direction())
          .append(", participant=")
          .append(interaction.participant())
          .append(", operation=")
          .append(interaction.operation())
          .append(", resolved=")
          .append(resolved)
          .append('\n');
    }
    return body.toString();
  }
}

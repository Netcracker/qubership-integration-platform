package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;
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

  @Inject
  public GatherRequirementsPromptBuilder(
      CompilerSkillDocumentService skillDocumentService,
      CompilerSkillAddonRepository addonRepository,
      RequirementDraftStore draftStore) {
    this.skillDocumentService = skillDocumentService;
    this.addonRepository = addonRepository;
    this.draftStore = draftStore;
  }

  /**
   * Returns the agent input for one gather turn. When the draft is already READY_FOR_PLAN, returns
   * the escaped raw user message so continuation turns stay short.
   */
  public String wrap(String conversationId, String userMessage) {
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
          skills, or run the compiler spine. Call captureRequirementDraft every turn. Reply in the
          same language as the user.
          </service-runtime-envelope>
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
                  addonBlock(),
                  currentDraftBlock(draft),
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
}

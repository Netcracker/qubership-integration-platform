package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final ObjectMapper DRAFT_MAPPER = new ObjectMapper();

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

  /** Returns the agent input for one conversational requirement-discovery turn. */
  public String wrap(String conversationId, String userMessage) {
    return wrap(conversationId, userMessage, "en");
  }

  public String wrap(String conversationId, String userMessage, String responseLocale) {
    if (skillDocumentService == null) {
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(userMessage);
    }
    Optional<RequirementDraft> draft =
        draftStore != null ? draftStore.get(conversationId) : Optional.empty();
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
          discovery behavior (catalog/API Hub, accepted edits, platform defaults, consultation,
          and clarifying questions). The tool schema defines the exact input form. Do not write files, commit changes,
          invoke implementation skills, or run the compiler spine. Answer the user's current
          question before asking for more requirements. Capture accepted requirement changes only;
          do not capture explanations, recommendations, or unselected alternatives.
          Before making a QIP-specific capability, element, constraint, or pattern claim, call
          searchRequirementKnowledge with the user's question and ground a concise synthesis in the
          result. Do not show source names or identifiers in the answer and do not reproduce long
          passages; the server records provenance in logs. If the lookup fails or lacks support,
          say that the QIP-specific claim is unverified.
          Call finishRequirementDiscoveryTurn exactly once as the final tool call, after any
          required capture and before the final answer. Complete all tool calls before writing
          user-visible prose. After that tool returns, write exactly one final answer; do not repeat
          or revise it. Use STAY for questions, advice, comparisons, or continued discussion. Use
          CONTINUE only when the user asked to proceed with design or chain creation.
          A request to prepare a design for approval is a request to proceed: use CONTINUE
          after saving the known requirements. The later approval gate still applies.
          Capture known requirements before catalog lookup or a clarifying question. When a
          business choice is unresolved, save the known interactions and facts as a partial
          draft with an open question. Do not end a create or design request without saving
          its known requirements. Named entry requests and following calls belong in the flow
          as separate interactions. If the capture result reports missing information already
          present in the user's request, correct the accepted draft before asking the user.
          For a prohibition, use NEGATIVE polarity and write the text as a prohibition, for example
          "Do not log the input body." Do not write an affirmative command with NEGATIVE polarity.
          In each capability record, set fields that do not belong to its capabilityKey to JSON
          null. An http-sender uses httpMethod and path for its direct URI; httpMode and
          targetReference stay null. A direct HTTP URI is not a catalog call.
          Read the accepted view after lookups. searchCatalogSystems does not bind an
          interaction.%s Reply in the
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
                  catalogFollowUpGuidance(conversationId),
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

  private String catalogFollowUpGuidance(String conversationId) {
    String uploaded = uploadedSpecGuidance(conversationId);
    if (!uploaded.isBlank()) {
      return uploaded;
    }
    return " After the draft is stored, call resolveApiOperation only for catalog-backed"
        + " interactions (implemented-service HTTP triggers, async-api-trigger, and outbound"
        + " calls with no native capability). Native triggers"
        + " and direct elements (for example sftp-trigger-2, sftp-upload, mail-sender,"
        + " kafka-sender-2, and jms-sender) skip the catalog; capture their capability entry with"
        + " method, path, topic,"
        + " or URI when applicable. Custom HTTP uses httpMode=CUSTOM with a path; no"
        + " catalog. Implemented service HTTP uses httpMode=CATALOG with participant set"
        + " and a null path, then resolveApiOperation. Catalog outbound calls do not invent a"
        + " sender key; resolveApiOperation is that classification. Ambiguous HTTP or outbound"
        + " needs one question; do not search. mcp-trigger skips catalog lookup; capture"
        + " participant and optional operation, and do not call resolveApiOperation for it."
        + " Do not search the catalog or API Hub for direct elements.";
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
        + " SERVICE_CALL facts. Let the server decide readiness when the requirements are complete."
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
    if (draftStore == null || conversationId == null) {
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
    if (current.authoredDraft() != null) {
      return "\n<current-requirement-draft>\n"
          + "Accepted authored content (use readRequirementDraft for fresh resolutions):\n"
          + DRAFT_MAPPER.valueToTree(current.authoredDraft())
          + "\n</current-requirement-draft>\n";
    }
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

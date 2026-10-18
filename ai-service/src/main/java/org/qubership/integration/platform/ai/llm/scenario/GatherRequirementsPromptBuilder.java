package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.AddonPromptMaterialStripper;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
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
  private static final ObjectMapper DRAFT_MAPPER = new ObjectMapper().findAndRegisterModules();

  private final CompilerSkillDocumentService skillDocumentService;
  private final CompilerSkillAddonRepository addonRepository;
  private final RequirementDraftStore draftStore;
  private final ConversationService conversationService;
  @Inject org.qubership.integration.platform.ai.plan.UploadedSpecOperations uploadedSpecs;

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
    Optional<RequirementDraft> draft =
        draftStore != null ? draftStore.get(conversationId) : Optional.empty();
    Set<String> uploadedInteractions = uploadedSpecs == null ? Set.of()
        : uploadedSpecs.pendingInteractionIds(conversationId, draft.orElse(null));
    String mandatory = currentDraftBlock(draft) + incompleteDraftBlock(draft, uploadedInteractions)
        + lastCaptureRejectionBlock(conversationId, draft)
        + sourceDialogueBlock(conversationId, userMessage)
        + "\nPinned response locale: " + normalizedLocale(responseLocale) + "\n";
    if (skillDocumentService == null) {
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(mandatory);
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
          discovery behavior (catalog/API Hub, accepted edits, platform defaults, consultation,
          and clarifying questions). The tool schema defines the exact input form. Do not write files, commit changes,
          invoke implementation skills, or run the compiler spine. Answer the user's current
          question before asking for more requirements. Capture accepted requirement changes only;
          do not capture explanations, recommendations, or unselected alternatives.
          The service owns the IDS review choice and presents a Yes/No card. Do not ask about IDS
          in prose or add that choice to openQuestions. Keep idsRequested null unless the author
          explicitly chose it; requesting a choice card is not a Yes answer.
          When an explicit requirement conflicts with a company recommendation, explain the
          requested value, the recommended value, and the consequence. Persist an open question
          and ask the user which value to use. Do not silently replace the requested value or
          treat the initial request as an informed exception. After the user answers, preserve
          the selected requirement and the acknowledged policy conflict in the accepted draft.
          Record each conflict as one positive CONSTRAINT fact scoped to the affected interaction,
          with policyConflict containing policyId, propertyKey, requestedValue, recommendedValue,
          questionId, and decision=null. Add the matching scoped open question. For external HTTP
          accessControlType=NONE, use policyId=CORPORATE_CIP_STANDARDS:12.4 and recommend RBAC.
          After a later user reply, update that same fact with decision=KEEP_REQUESTED or
          USE_RECOMMENDED and remove its question. Keep both original values and the policy ID.
          Do not ask again unless that requirement or recommendation changes. Technical schema
          errors are not optional recommendations; explain unavailable choices and offer valid ones.
          Before making a QIP-specific capability, element, constraint, or pattern claim, call
          searchRequirementKnowledge with the user's question and ground a concise synthesis in the
          result. Do not show source names or identifiers in the answer and do not reproduce long
          passages; the server records provenance in logs. If the lookup fails or lacks support,
          say that the QIP-specific claim is unverified.
          Call finishRequirementDiscoveryTurn exactly once as the final tool call, after any
          required capture and before the final answer. Complete all tool calls before writing
          user-visible prose. After that tool returns, write exactly one final answer; do not repeat
          or revise it. Use STAY for questions, advice, comparisons, or continued discussion. Use
          CONTINUE only when the user asked to proceed and the saved draft has no missing fields.
          When nextAction is RUN_IMPORT, the business requirements are complete and uploaded
          operations await import. Use CONTINUE to hand them to the import stage; do not look up
          missing uploaded operations or ask the user to bind them.
          For other PARTIAL drafts, or nextAction COMPLETE_DRAFT, call updateRequirementDraft
          to complete the saved requirements. Do not claim readiness or use CONTINUE.
          Preserve explicit elementIntent on facts: ANCHOR uses the interaction ID; LOCAL_NODE
          uses a separate stable elementRef; BEFORE_HOOK and AFTER_HOOK belong only to their
          Service Call. Chain names use CHAIN placement with elementType=chain. Copy requestedName
          without prefixes or language labels, and keep literal bodies/strings in exactValues.
          Use existing element schema keys for requestedProperties; omit guessed defaults.
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
          Catalog lookup results update bindings themselves. Do not add "catalog bound" authored
          facts or repeat the trigger and operation declarations as BEHAVIOR. BEHAVIOR describes
          additional processing. If the request sends success and failure fields through the same
          result delivery, set INLINE_RESPONSE on the producing call; null propagates and skips
          that delivery on failure. Keep the delivery call's own failureMode null by default.
          Read the accepted view after lookups. searchCatalogSystems does not bind an
          interaction.%s Reply in the
          pinned response locale %s. This
          locale is authoritative; do not infer another language from conversation history or
          embedded text.
          </service-runtime-envelope>
          %s
          %s
          """
              .formatted(
                  document.sourceSkillId(),
                  document.packVersion().normalized(),
                  document.sourcePath(),
                  document.markdown(),
                  catalogFollowUpGuidance(conversationId),
                  normalizedLocale(responseLocale),
                  addonBlock(),
                  mandatory);
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Failed to load process skill %s; using the mandatory gather envelope",
          RequirementDraftTool.SOURCE_SKILL_ID);
      return QuteUserMessageEscaping.escapeForAiServiceUserMessage(mandatory);
    }
  }

  private String sourceDialogueBlock(String conversationId, String currentText) {
    StringBuilder body = new StringBuilder("\n<requirement-source>\n");
    body.append("Use the current draft as accepted state. Apply explicit author amendments; ")
        .append("retain unrelated requirements. Prior sources are evidence, and superseded values ")
        .append("do not override accepted changes. Read an earlier source by its turn id when needed. ")
        .append("When changing or removing accepted content, include amendments in updateRequirementDraft: ")
        .append("each has the exact stable path, pending author sourceTurnId, and a verbatim quote of the amendment. ")
        .append("Examples: /facts/goal/text, /facts/goal for removal, /capabilities/entry/path, /transitions/entry for outgoing transitions. ")
        .append("Apply one author amendment atomically to every affected representation. When an endpoint URL changes, ")
        .append("update its capability path and any operation, description, or fact text that repeats that URL. ")
        .append("Use the same source quote with a separate exact path for each changed field; retain all other content. ")
        .append("An unrelated new message grants no permission to change earlier requirements. ")
        .append("Automatic correction uses amendments: [] and retains confirmed answers.\n");
    boolean currentIncluded = false;
    if (conversationService != null) {
      var archive = conversationService.archive();
      var source = archive.read(conversationId);
      body.append("Reset generation: ").append(source.generation()).append('\n');
      if (draftStore != null) {
        var revision = draftStore.latestRevision(conversationId).map(value -> value.reference());
        body.append("Selected revision: ").append(revision.map(Object::toString).orElse("none"))
            .append(" authorApproved=").append(revision.map(ref -> draftStore.isApproved(conversationId, ref))
                .orElse(false)).append('\n');
      }
      for (var turn : archive.pendingInputs(conversationId)) {
        body.append("Pending author source: ").append(DRAFT_MAPPER.valueToTree(turn).toString()).append('\n');
        currentIncluded |= java.util.Objects.equals(turn.message().content(), currentText);
      }
      var assistants = source.activeTurns().stream().filter(turn -> turn.message().role()
          == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.ASSISTANT).toList();
      if (!assistants.isEmpty()) {
        body.append("Previous assistant question or options (check completion status): ")
            .append(DRAFT_MAPPER.valueToTree(assistants.getLast()).toString()).append('\n');
      }
      body.append("Earlier source references: ").append(source.activeTurns().stream()
          .filter(turn -> turn.message().role()
              == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
              && turn.consumedDraftReference() != null)
          .map(turn -> turn.id() + " consumed by " + turn.consumedDraftReference()).toList()).append('\n');
    }
    if (!currentIncluded) {
      body.append("Current author message:\n").append(currentText == null ? "" : currentText).append('\n');
    }
    return body.append("</requirement-source>\n").toString();
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
        + " sender key; resolveApiOperation is that classification. A named operation on a named"
        + " catalog system leaves its outbound capability absent, including result delivery."
        + " Resolve each such interaction; do not guess HTTP paths or replace lookup with a sender."
        + " Ambiguous HTTP or outbound"
        + " needs one question; do not search. mcp-trigger skips catalog lookup; capture"
        + " participant and optional operation, and do not call resolveApiOperation for it."
        + " Do not search the catalog or API Hub for direct elements.";
  }

  /**
   * When the reader already attached specs and approved import, discovery must not block on a
   * catalog miss or search API Hub for those operations. Import runs after this stage.
   */
  private String uploadedSpecGuidance(String conversationId) {
    if (!hasUploadedSpecifications(conversationId)) {
      return "";
    }
    return " This conversation has uploaded API specifications that are already approved for"
        + " catalog import after discovery. Do not search API Hub for operations from those specs,"
        + " and do not ask the reader to import or bind them. Capture the business flow from the"
        + " attached document, including participants and operations, without ENDPOINT or"
        + " SERVICE_CALL facts. Let the server decide readiness when the requirements are complete."
        + " Catalog lookup may miss until import runs. When capture returns nextAction RUN_IMPORT,"
        + " call finishRequirementDiscoveryTurn with CONTINUE even if readiness is PARTIAL."
        + " The import stage owns approval and binds each interaction to its imported service.";
  }

  private boolean hasUploadedSpecifications(String conversationId) {
    return conversationService != null && conversationId != null && !conversationId.isBlank()
        && org.qubership.integration.platform.ai.plan.workdocument.source.AttachmentRoles.hasSpecifications(
            conversationService.getAllowedAttachmentKeys(conversationId));
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

  private static String incompleteDraftBlock(Optional<RequirementDraft> draft, Set<String> uploadedInteractions) {
    if (draft.isEmpty()) {
      return "";
    }
    RequirementDraft current = draft.get();
    List<RequirementCaptureProjection.PendingWork> pending = current.authoredDraft() == null
        ? List.of()
        : RequirementCaptureProjection.pending(
            current.authoredDraft(),
            current.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(current), uploadedInteractions);
    if (RequirementCaptureProjection.onlyUploadedSpecImportsRemain(pending)) {
      return """

          <pending-uploaded-spec-import>
          The business requirements are complete. nextAction=RUN_IMPORT: operations await upload import.
          Read the saved draft and finish with CONTINUE when the author asks to proceed.
          Do not search APIHub or ask the author to provide catalog bindings before import.
          </pending-uploaded-spec-import>
          """;
    }
    if (pending.isEmpty() && current.readyForPlan()) {
      return "";
    }
    String gaps = pending.isEmpty()
        ? current.readinessError().orElse("The saved draft is still partial.")
        : pending.stream()
            .map(work -> "- " + work.message())
            .reduce("", (left, right) -> left + right + "\n");
    return """

        <incomplete-requirement-draft>
        The saved draft is still partial. Read it with readRequirementDraft and use updateRequirementDraft to fill the missing fields before CONTINUE. Do not tell the user the draft is ready.
        %s</incomplete-requirement-draft>
        """.formatted(gaps);
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

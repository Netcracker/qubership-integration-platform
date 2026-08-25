package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.llm.agent.DesignGeneratorSkillAgent;
import org.qubership.integration.platform.ai.llm.agent.DesignInputPromptAgent;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;

/**
 * Shared create-chain@2 design-input capability for {@code ids-entry} and {@code design-input}
 * stages. Branches on {@link StageExecutionContext#stageId()}.
 */
@ApplicationScoped
public class DesignInputCapability implements StageCapability {

  public static final String CAPABILITY_ID = "design-input";
  public static final String GENERATOR_SKILL_ID = "cip-design-generator";

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(DesignInputCapability.class);

  private static final String IDS_FLOW_MARKER = "Integration flow for CIP Chain";
  private static final Pattern MAPPING_RULE_HINT =
      Pattern.compile("^(?:(?:\\d+)\\s*[:.)]\\s*|[$/]).*(?:->|→).+$");

  private final IdsDocumentParser idsDocumentParser;
  private final NormalizedDesignFlowValidator flowValidator;
  private final MinimalIdsRenderer minimalIdsRenderer;
  private final BriefFlowExtractor briefFlowExtractor;
  private final DesignRequirementBriefCoverageValidator designCoverageValidator;
  private final GeneratedIdsAuthoringAdapter generatedIdsAuthoringAdapter;
  private final DesignInputIdsPathPrompts idsPathPrompts;

  @Inject
  public DesignInputCapability(
      DesignGeneratorSkillAgent designGeneratorSkillAgent,
      DesignInputPromptAgent designInputPromptAgent) {
    this(
        new IdsDocumentParser(),
        new NormalizedDesignFlowValidator(),
        new MinimalIdsRenderer(),
        new BriefFlowExtractor(),
        new DesignRequirementBriefCoverageValidator(),
        (brief, repairNote) ->
            designGeneratorSkillAgent
                .chat(
                    "design-generator-" + brief.goal(),
                    authoringPrompt(brief) + repairInstruction(repairNote))
                .collect()
                .asList()
                .await()
                .indefinitely()
                .stream()
                .reduce("", String::concat),
        new DesignInputIdsPathPrompts(designInputPromptAgent));
  }

  /** Unit/IT helper without prompt LLM (English fallback + keyword routing). */
  public DesignInputCapability(DesignGeneratorSkillAgent designGeneratorSkillAgent) {
    this(designGeneratorSkillAgent, null);
  }

  DesignInputCapability(
      IdsDocumentParser idsDocumentParser,
      NormalizedDesignFlowValidator flowValidator,
      MinimalIdsRenderer minimalIdsRenderer,
      BriefFlowExtractor briefFlowExtractor,
      DesignRequirementBriefCoverageValidator designCoverageValidator,
      BiFunction<RequirementBrief, String, String> idsGenerator) {
    this(
        idsDocumentParser,
        flowValidator,
        minimalIdsRenderer,
        briefFlowExtractor,
        designCoverageValidator,
        idsGenerator,
        new DesignInputIdsPathPrompts());
  }

  DesignInputCapability(
      IdsDocumentParser idsDocumentParser,
      NormalizedDesignFlowValidator flowValidator,
      MinimalIdsRenderer minimalIdsRenderer,
      BriefFlowExtractor briefFlowExtractor,
      DesignRequirementBriefCoverageValidator designCoverageValidator,
      BiFunction<RequirementBrief, String, String> idsGenerator,
      DesignInputIdsPathPrompts idsPathPrompts) {
    this.idsDocumentParser = Objects.requireNonNull(idsDocumentParser, "idsDocumentParser");
    this.flowValidator = Objects.requireNonNull(flowValidator, "flowValidator");
    this.minimalIdsRenderer = Objects.requireNonNull(minimalIdsRenderer, "minimalIdsRenderer");
    this.briefFlowExtractor = Objects.requireNonNull(briefFlowExtractor, "briefFlowExtractor");
    this.designCoverageValidator =
        Objects.requireNonNull(designCoverageValidator, "designCoverageValidator");
    this.generatedIdsAuthoringAdapter =
        new GeneratedIdsAuthoringAdapter(Objects.requireNonNull(idsGenerator, "idsGenerator"));
    this.idsPathPrompts = Objects.requireNonNull(idsPathPrompts, "idsPathPrompts");
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    // GENERATE blocks on DesignGeneratorSkillAgent; must not run on the Vert.x event loop.
    // Surface cip-design-generator activity when GENERATE will author IDS (same channel as
    // brainstorming).
    String progressSkillId = progressSkillId(context);
    if (progressSkillId == null) {
      return Uni.createFrom()
          .item(() -> (CapabilitySignal) new CapabilitySignal.Completed(runStage(context)))
          .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
          .toMulti();
    }
    var turnEmit = SkillActivitySupport.captureTurnEmit(context.conversationId());
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(progressSkillId)),
            Uni.createFrom()
                .item(
                    () -> {
                      SkillActivitySupport.bindWorker(progressSkillId, turnEmit);
                      try {
                        return SkillActivitySupport.wrapTerminal(
                            progressSkillId,
                            List.of(new CapabilitySignal.Completed(runStage(context))));
                      } finally {
                        SkillActivitySupport.unbindWorker(turnEmit);
                      }
                    })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem()
                .transformToMulti(signals -> Multi.createFrom().iterable(signals)));
  }

  private StageOutcome runStage(StageExecutionContext context) {
    return switch (context.stageId()) {
      case "ids-entry" -> enterRoute(context);
      case "design-input" -> prepareDesign(context);
      default ->
          StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "unsupported design-input stage");
    };
  }

  /**
   * Skill id for chat activity, or null when the stage will not run a generator skill. Uses keyword
   * + pending mode only (no LLM) so peek stays on the event loop.
   */
  private String progressSkillId(StageExecutionContext context) {
    if (!"design-input".equals(context.stageId())) {
      return null;
    }
    DesignEntryRoute route = designEntryRoute(context);
    if (route == DesignEntryRoute.PROVIDE) {
      return null;
    }
    String userText = context.attributeAsString("userText");
    DesignMode chosen = DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords(userText);
    DesignMode mode = chosen != null ? chosen : pendingDesignMode(context);
    return mode == DesignMode.GENERATE ? GENERATOR_SKILL_ID : null;
  }

  private StageOutcome enterRoute(StageExecutionContext context) {
    String userText = context.attributeAsString("userText");
    if (userText != null && userText.contains(IDS_FLOW_MARKER)) {
      IdsDocument document =
          provisionalIdsDocument(userText, IdsDocument.Mode.PROVIDED, "user-ids", "ids-entry@1");
      return new StageOutcome(
          StageOutcomeClass.SUCCEEDED,
          List.of(
              new ArtifactCandidate(Kind.DESIGN_ENTRY_ROUTE, DesignEntryRoute.PROVIDE, List.of()),
              new ArtifactCandidate(Kind.IDS_DOCUMENT, document, List.of())),
          "provide route selected",
          null);
    }
    return new StageOutcome(
        StageOutcomeClass.SUCCEEDED,
        List.of(
            new ArtifactCandidate(Kind.DESIGN_ENTRY_ROUTE, DesignEntryRoute.STANDARD, List.of())),
        "standard route selected",
        null);
  }

  private StageOutcome prepareDesign(StageExecutionContext context) {
    DesignEntryRoute route = designEntryRoute(context);
    if (route == DesignEntryRoute.PROVIDE) {
      return prepareProvide(context);
    }
    RequirementBrief brief = requirementBrief(context);
    String userText = context.attributeAsString("userText");
    String discoveryText = context.attributeAsString("discoveryUserText");
    String responseLocale =
        context.runManifest() == null ? "en" : context.runManifest().responseLocale();
    // Worker-pool path: allow LLM classify for non-English IDS choice. Stale discovery text must
    // not silently select DERIVE/GENERATE after brief Agree (stage-approval tokens return null).
    DesignMode chosen =
        echoesDiscovery(userText, discoveryText)
            ? null
            : idsPathPrompts.resolveIdsPathChoice(userText);
    DesignMode pending = pendingDesignMode(context);
    DesignMode mode = chosen != null ? chosen : pending;

    if (mode == null) {
      return waitingForIdsChoice(brief, userText, discoveryText, responseLocale);
    }

    RequirementBrief effectiveBrief =
        brief == null ? null : DesignRequirementDataMappingNormalizer.normalize(brief);
    if (effectiveBrief != null
        && !designCoverageValidator.listMissingEdges(effectiveBrief).isEmpty()) {
      try {
        if (pending != null && hasMappingRuleSyntax(userText)) {
          effectiveBrief =
              designCoverageValidator.withExplicitMappingsForMissingEdges(effectiveBrief, userText);
        } else {
          // Missing SERVICE_CALL topology edges default to PASS_THROUGH. GENERATE used to wait
          // for a pass_through confirmation whenever leftover capture rows survived
          // normalization, which blocked design after brief approval.
          effectiveBrief = designCoverageValidator.withPassThroughForMissingEdges(effectiveBrief);
        }
      } catch (IllegalArgumentException ex) {
        return mappingAnswerError(effectiveBrief, ex.getMessage());
      }
    }

    return switch (mode) {
      case GENERATE -> prepareGenerate(effectiveBrief, mode, userText, discoveryText, responseLocale);
      case DERIVE -> prepareDerive(effectiveBrief, mode, userText, discoveryText, responseLocale);
      case PROVIDE ->
          StageOutcome.of(
              StageOutcomeClass.CONTRACT_FAILURE, "PROVIDE is not a standard-route IDS choice");
    };
  }

  /**
   * Reports whether the turn carries the discovery text again rather than an answer to the IDS
   * question.
   *
   * <p>After the brief is approved the stage re-runs with the text that opened the run still in
   * hand. Classifying that text finds the words that described the chain and reads them as a
   * choice, so the stage picks a path the caller was never asked about. Only a reply that differs
   * from what discovery already saw counts as an answer.
   */
  private static boolean echoesDiscovery(String userText, String discoveryText) {
    if (userText == null || discoveryText == null) {
      return false;
    }
    return userText.trim().equalsIgnoreCase(discoveryText.trim());
  }

  private static boolean hasMappingRuleSyntax(String userText) {
    if (userText == null) {
      return false;
    }
    List<String> lines = userText.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    return !lines.isEmpty()
        && lines.stream().allMatch(line -> MAPPING_RULE_HINT.matcher(line).matches());
  }

  /**
   * Appends what the previous attempt got wrong, so the author repairs instead of guessing again.
   *
   * <p>The agent keeps chat memory under the same id, so this arrives alongside the document it
   * refers to.
   */
  private static String repairInstruction(String repairNote) {
    if (repairNote == null || repairNote.isBlank()) {
      return "";
    }
    return "\n\nYour previous document could not be read: "
        + repairNote
        + "\nRewrite it in full and fix exactly that. Keep everything else as it was.";
  }

  /**
   * Authors the IDS, once more with the failure attached when the first document cannot be read.
   *
   * <p>The structure the parser needs — the flow heading, the sequence diagram it derives
   * participants and steps from — is asked for in prose and therefore not guaranteed. A caller can
   * do nothing about a document they never wrote, so a formatting miss is repaired here rather
   * than surfaced as a question.
   */
  private AuthoredDesign authorDesign(RequirementBrief brief) {
    IllegalArgumentException firstFailure;
    try {
      String markdown = generatedIdsAuthoringAdapter.generate(brief);
      NormalizedDesignFlow parsed = idsDocumentParser.parseFirstFlow(markdown);
      return new AuthoredDesign(markdown, briefFlowExtractor.withMappings(brief, parsed));
    } catch (IllegalArgumentException ex) {
      firstFailure = ex;
      if (isInternalMappingOverlayError(ex.getMessage())) {
        throw ex;
      }
      LOG.infof("Authored IDS could not be read (%s); asking for a repair", ex.getMessage());
    }
    String markdown = generatedIdsAuthoringAdapter.generate(brief, firstFailure.getMessage());
    NormalizedDesignFlow parsed = idsDocumentParser.parseFirstFlow(markdown);
    return new AuthoredDesign(markdown, briefFlowExtractor.withMappings(brief, parsed));
  }

  private record AuthoredDesign(String markdown, NormalizedDesignFlow flow) {}

  private StageOutcome prepareProvide(StageExecutionContext context) {
    IdsDocument supplied = idsDocument(context);
    if (supplied == null || supplied.markdown() == null || supplied.markdown().isBlank()) {
      return StageOutcome.of(
          StageOutcomeClass.MISSING_MANDATORY_INPUT, "PROVIDE route requires IdsDocument");
    }
    try {
      NormalizedDesignFlow flow = idsDocumentParser.parseFirstFlow(supplied.markdown());
      flowValidator.validate(flow);
      IdsDocument document =
          finalizedIdsDocument(
              supplied.markdown(),
              IdsDocument.Mode.PROVIDED,
              supplied.sourceReference(),
              supplied.sourceHash(),
              flow,
              "ids-document-parser@1");
      return succeededDesign(DesignMode.PROVIDE, document, flow, "provided IDS normalized");
    } catch (IllegalArgumentException ex) {
      return StageOutcome.of(StageOutcomeClass.VALIDATION_FAILURE, ex.getMessage());
    }
  }

  private StageOutcome prepareGenerate(
      RequirementBrief brief,
      DesignMode pendingMode,
      String userText,
      String discoveryText,
      String responseLocale) {
    if (brief == null) {
      return StageOutcome.of(
          StageOutcomeClass.MISSING_MANDATORY_INPUT, "GENERATE requires RequirementBrief");
    }
    StageOutcome mappingWait =
        mappingCoverageOrWait(brief, pendingMode, userText, discoveryText, responseLocale);
    if (mappingWait != null) {
      return mappingWait;
    }
    try {
      AuthoredDesign authored = authorDesign(brief);
      String markdown = authored.markdown();
      NormalizedDesignFlow flow = authored.flow();
      flowValidator.validate(flow);
      IdsDocument document =
          finalizedIdsDocument(
              markdown,
              IdsDocument.Mode.GENERATED,
              "requirement-brief",
              DesignContentHashes.sha256(RequirementBriefText.format(brief)),
              flow,
              "cip-design-generator@1");
      return new StageOutcome(
          StageOutcomeClass.CANDIDATE,
          designCandidates(DesignMode.GENERATE, document, flow),
          "generated IDS ready for approval",
          null);
    } catch (IllegalArgumentException ex) {
      // Ask for what the design is missing rather than failing the stage. A VALIDATION_FAILURE
      // reopens the previous approval, which drops the caller back onto the requirement brief and
      // loses the turn; an author short of one explicit fact is the far likelier case.
      return StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, userFacingAuthoringWait(ex));
    }
  }

  private StageOutcome prepareDerive(
      RequirementBrief brief,
      DesignMode pendingMode,
      String userText,
      String discoveryText,
      String responseLocale) {
    if (brief == null) {
      return StageOutcome.of(
          StageOutcomeClass.MISSING_MANDATORY_INPUT, "DERIVE requires RequirementBrief");
    }
    StageOutcome mappingWait =
        mappingCoverageOrWait(brief, pendingMode, userText, discoveryText, responseLocale);
    if (mappingWait != null) {
      return mappingWait;
    }
    try {
      BriefFlowExtractor.ExtractionResult extracted = briefFlowExtractor.extract(brief);
      if (extracted instanceof BriefFlowExtractor.ExtractionResult.NeedsInput needsInput) {
        return StageOutcome.of(
            StageOutcomeClass.NEEDS_INPUT,
            "Cannot derive the design flow because required facts are missing: "
                + String.join(", ", needsInput.missingFacts()));
      }
      NormalizedDesignFlow flow =
          ((BriefFlowExtractor.ExtractionResult.Complete) extracted).flow();
      flowValidator.validate(flow);
      String markdown = minimalIdsRenderer.render(flow);
      IdsDocument document =
          finalizedIdsDocument(
              markdown,
              IdsDocument.Mode.DERIVED,
              "requirement-brief",
              DesignContentHashes.sha256(RequirementBriefText.format(brief)),
              flow,
              MinimalIdsRenderer.RENDERER_VERSION);
      return succeededDesign(DesignMode.DERIVE, document, flow, "IDS ready");
    } catch (IllegalArgumentException ex) {
      // Ask for what the design is missing rather than failing the stage. A VALIDATION_FAILURE
      // reopens the previous approval, which drops the caller back onto the requirement brief and
      // loses the turn; an author short of one explicit fact is the far likelier case.
      return StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, userFacingAuthoringWait(ex));
    }
  }

  private StageOutcome mappingCoverageOrWait(
      RequirementBrief brief,
      DesignMode pendingMode,
      String userText,
      String discoveryText,
      String responseLocale) {
    List<String> missing = designCoverageValidator.listMissingEdges(brief);
    if (missing.isEmpty()) {
      try {
        designCoverageValidator.validate(brief);
      } catch (IllegalArgumentException ex) {
        return StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, userFacingAuthoringWait(ex));
      }
      return null;
    }
    return StageOutcome.of(
        StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.retag(
            PipelineGates.MAPPING_GAP,
            DesignInputIdsPathPrompts.encodeMappingGapWait(
                idsPathPrompts.mappingGapPrompt(
                    responseLocale, brief, pendingMode, missing, userText, discoveryText),
                designCoverageValidator.listReadableMissingEdges(brief))));
  }

  private StageOutcome mappingAnswerError(RequirementBrief brief, String message) {
    return StageOutcome.of(
        StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.retag(
            PipelineGates.MAPPING_GAP,
            DesignInputIdsPathPrompts.encodeMappingGapWait(
                userFacingAuthoringWait(message),
                designCoverageValidator.listReadableMissingEdges(brief))));
  }

  /**
   * Overlay and capture leftovers must never reach chat as fact-id hashes or {@code intent refs}
   * lines. Coverage-gap waits stay as written: they already name the missing outbound call.
   */
  static String userFacingAuthoringWait(IllegalArgumentException ex) {
    return userFacingAuthoringWait(ex == null ? null : ex.getMessage());
  }

  static String userFacingAuthoringWait(String message) {
    if (message == null || message.isBlank()) {
      return "The authored IDS is missing a required outbound service call from the requirements. "
          + "Generate an IDS that includes each outbound call, or choose Pass through for the"
          + " listed mapping edges.";
    }
    if (isInternalMappingOverlayError(message)) {
      return "Some captured mapping rows do not match the trigger or outbound calls in the"
          + " requirements. Use Pass through for the listed edges, or generate an IDS that"
          + " includes each outbound call.";
    }
    return message;
  }

  private static boolean isInternalMappingOverlayError(String message) {
    return message != null
        && (message.contains("intent refs") || looksLikeFactDigestDump(message));
  }

  private static boolean looksLikeFactDigestDump(String message) {
    return message.chars().filter(ch -> ch == '→' || ch == '>').count() >= 1
        && message.matches("(?s).*\\b[a-f0-9]{32,}\\b.*");
  }

  private StageOutcome waitingForIdsChoice(
      RequirementBrief brief, String userText, String discoveryText, String responseLocale) {
    return StageOutcome.of(
        StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.retag(
            PipelineGates.IDS_PATH_CHOICE,
            idsPathPrompts.idsPathChoicePrompt(responseLocale, brief, userText, discoveryText)));
  }

  /**
   * Technical missing-fact list (English OK). Not a conversational CTA — callers should have asked
   * for IDS path / mappings before reaching DERIVE extraction.
   */

  private static DesignMode pendingDesignMode(StageExecutionContext context) {
    Object value = context.attributes().get(DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR);
    if (value instanceof DesignMode mode) {
      return mode;
    }
    if (value instanceof String text) {
      try {
        return DesignMode.valueOf(text.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }
    return null;
  }

  private static StageOutcome succeededDesign(
      DesignMode mode, IdsDocument document, NormalizedDesignFlow flow, String message) {
    return new StageOutcome(
        StageOutcomeClass.SUCCEEDED, designCandidates(mode, document, flow), message, null);
  }

  private static List<ArtifactCandidate> designCandidates(
      DesignMode mode, IdsDocument document, NormalizedDesignFlow flow) {
    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(new ArtifactCandidate(Kind.DESIGN_MODE, mode, List.of()));
    candidates.add(new ArtifactCandidate(Kind.IDS_DOCUMENT, document, List.of()));
    candidates.add(new ArtifactCandidate(Kind.NORMALIZED_DESIGN_FLOW, flow, List.of()));
    return List.copyOf(candidates);
  }

  private static IdsDocument provisionalIdsDocument(
      String markdown, IdsDocument.Mode mode, String sourceReference, String rendererVersion) {
    String sourceHash = DesignContentHashes.sha256(markdown);
    return new IdsDocument(
        "1",
        mode,
        sourceReference,
        sourceHash,
        "pending-normalization",
        rendererVersion,
        markdown);
  }

  private static IdsDocument finalizedIdsDocument(
      String markdown,
      IdsDocument.Mode mode,
      String sourceReference,
      String sourceHash,
      NormalizedDesignFlow flow,
      String rendererVersion) {
    return new IdsDocument(
        "1",
        mode,
        sourceReference,
        sourceHash,
        DesignContentHashes.sha256(flow.toString()),
        rendererVersion,
        markdown);
  }

  private static DesignEntryRoute designEntryRoute(StageExecutionContext context) {
    Object value = context.attributes().get("designEntryRoute");
    if (value instanceof DesignEntryRoute route) {
      return route;
    }
    return DesignEntryRoute.STANDARD;
  }

  private static IdsDocument idsDocument(StageExecutionContext context) {
    Object value = context.attributes().get("idsDocument");
    return value instanceof IdsDocument document ? document : null;
  }

  private static RequirementBrief requirementBrief(StageExecutionContext context) {
    Object value = context.attributes().get("requirementBrief");
    return value instanceof RequirementBrief brief ? brief : null;
  }

  /**
   * Builds the authoring request for {@code cip-design-generator}.
   *
   * <p>This is the only channel that can carry rules to that agent. Its system message is the
   * upstream template, which must not be edited here, and it holds no knowledge tool, so the addon
   * overlay never reaches it. Anything the author has to obey belongs in this string.
   *
   * <p>The heading rule is not styling. {@link IdsDocumentParser} anchors on {@code ### Integration
   * flow for CIP Chain - <name>} and can read nothing without it, so a document that renames or
   * re-levels that line is unusable no matter how good its content is.
   */
  static String authoringPrompt(RequirementBrief brief) {
    StringBuilder prompt = new StringBuilder();
    prompt
        .append("Author a full IDS from this approved structured requirement brief:\n\n")
        .append(RequirementBriefText.format(brief))
        .append("\n\nReturn the document only: no preamble, no closing remarks, no ``` fence")
        .append(" around the whole answer.")
        .append("\nKeep the template's section headings exactly as they are and write each of")
        .append(" them once. The flow heading keeps its three hash marks and its wording, with")
        .append(" the chain name after the dash; the document cannot be read without it.")
        .append("\nUse only Mermaid sequenceDiagram with autonumber.")
        .append("\nDo not invent operationId, packageId, path, method, or mapping rules.")
        .append("\nName a participant only when the requirements name the system it stands for.");
    if (hasPositiveServiceCall(brief)) {
      prompt.append(
          "\nThe brief lists SERVICE_CALL facts. The sequence diagram must include each outbound"
              + " call as CIP -> that external participant. Do not collapse those calls into a"
              + " script-only Client -> CIP diagram. Their catalog/API resolution already happened"
              + " before this stage: do not search API Hub, import an API, or replace an existing"
              + " service binding.");
    } else {
      prompt
          .append(" A chain that answers from its own logic talks to no external system: its")
          .append(" diagram holds the caller and CIP alone, and the response comes from a script")
          .append(" step. Do not add an external participant to fill out the diagram.");
      prompt.append(
          "\nIf the brief forbids service calls or APIHub, model HTTP GET/POST paths as the CIP "
              + "chain trigger (Client -> CIP), and return the response from a script — never as an "
              + "outbound CIP -> external GET/POST service-call.");
    }
    return prompt.toString();
  }

  private static boolean hasPositiveServiceCall(RequirementBrief brief) {
    if (brief.facts() == null) {
      return false;
    }
    return brief.facts().stream()
        .filter(Objects::nonNull)
        .anyMatch(
            fact ->
                fact.polarity() == RequirementFactPolarity.POSITIVE
                    && fact.kind() == RequirementFactKind.SERVICE_CALL);
  }
}

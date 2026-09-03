package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.llm.agent.ChainSemanticDesignAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Shared create-chain@2 design-input capability for {@code ids-entry} and {@code design-input}.
 * Design-input captures a typed semantic revision and renders IDS as an approval view.
 */
@ApplicationScoped
public class DesignInputCapability implements StageCapability {

  private static final Logger LOG = Logger.getLogger(DesignInputCapability.class);

  public static final String CAPABILITY_ID = "design-input";
  public static final String SKILL_ID = "chain-semantic-design";
  public static final String PROVIDED_IDS_REJECTED =
      "IDS is an approval view; provide requirements that can produce a semantic revision";

  private static final String IDS_FLOW_MARKER = "Integration flow for CIP Chain";

  private final ChainSemanticDesignAgent designAgent;
  private final ChainSemanticIdsRenderer idsRenderer;
  private final CompilerContractRepository contractRepository;
  private final BiFunction<String, String, Multi<String>> designRunner;

  @Inject
  public DesignInputCapability(
      ChainSemanticDesignAgent designAgent,
      ChainSemanticIdsRenderer idsRenderer,
      CompilerContractRepository contractRepository) {
    this(designAgent, idsRenderer, contractRepository, null);
  }

  public DesignInputCapability(
      BiFunction<String, String, Multi<String>> designRunner,
      ChainSemanticIdsRenderer idsRenderer) {
    this(null, idsRenderer, new ClasspathCompilerContractRepository(), designRunner);
  }

  private DesignInputCapability(
      ChainSemanticDesignAgent designAgent,
      ChainSemanticIdsRenderer idsRenderer,
      CompilerContractRepository contractRepository,
      BiFunction<String, String, Multi<String>> designRunner) {
    this.designAgent = designAgent;
    this.idsRenderer = Objects.requireNonNull(idsRenderer, "idsRenderer");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
    this.designRunner = designRunner;
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
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

  private String progressSkillId(StageExecutionContext context) {
    if (!"design-input".equals(context.stageId())) {
      return null;
    }
    return SKILL_ID;
  }

  private StageOutcome enterRoute(StageExecutionContext context) {
    String userText = context.attributeAsString("userText");
    if (userText != null && userText.contains(IDS_FLOW_MARKER)) {
      return StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, PROVIDED_IDS_REJECTED);
    }
    return StageOutcome.of(StageOutcomeClass.SUCCEEDED, "standard route selected");
  }

  private StageOutcome prepareDesign(StageExecutionContext context) {
    RequirementBrief brief = requirementBrief(context);
    if (brief == null) {
      return StageOutcome.of(
          StageOutcomeClass.MISSING_MANDATORY_INPUT,
          "design-input requires an approved RequirementBrief");
    }
    List<Transition> uncovered = MappingGapCoverage.uncovered(brief);
    if (MappingGapCoverage.shouldAsk(uncovered)) {
      String tagged =
          PipelineGates.tag(
              PipelineGates.MAPPING_GAP,
              MappingGapWait.encode(
                  MappingGapWait.FALLBACK_QUESTION, MappingGapCoverage.readableEdges(uncovered)));
      return StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, tagged);
    }
    AtomicReference<ChainSemanticRevision> captured = new AtomicReference<>();
    ToolSession.bind(context.conversationId());
    ProductCapabilityCaptureContext.bindDesign(
        context.runId(),
        context.conversationId(),
        brief,
        payload -> {
          if (payload instanceof ChainSemanticRevision revision) {
            captured.set(revision);
          }
        });
    CompilerContract contract = contractRepository.require(CompilerContract.V1);
    String agentText;
    try {
      agentText =
          runDesignAgent(context.conversationId(), authoringPrompt(brief, contract, context));
    } finally {
      ProductCapabilityCaptureContext.unbind(context.conversationId());
      ToolSession.clear();
    }
    ChainSemanticRevision revision = captured.get();
    if (revision == null) {
      String message = captureFailureMessage(agentText);
      LOG.warnf(
          "design-input captured nothing: runId=%s, conversationId=%s, agentText=%s",
          context.runId(),
          context.conversationId(),
          AiTraceLog.previewOneLine(agentText, AiTraceLog.DEFAULT_TOOL_RESULT_CHARS));
      // CONTRACT_FAILURE, not NEEDS_INPUT: the reader cannot type a revision. Recovery retries
      // this stage once, then can reopen requirement-analysis with this message as halt evidence.
      return StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE,
          message,
          new RecoveryCause(
              RecoveryCauseCode.CONTRACT_SHAPE,
              List.of(new PlanValidationFinding("CONTRACT_SHAPE", message, true)),
              ""));
    }
    IdsDocument ids = idsRenderer.render(revision, contract);
    // The stage carries no approval policy of its own: the topology is approved together with the
    // implementation plan, so design-input completes instead of opening a gate. The IDS document
    // stays a planner input either way; whether a reader sees it is decided at the plan gate.
    return new StageOutcome(
        StageOutcomeClass.SUCCEEDED,
        List.of(
            new ArtifactCandidate(Kind.CHAIN_SEMANTIC_REVISION, revision, List.of()),
            new ArtifactCandidate(Kind.IDS_DOCUMENT, ids, List.of())),
        "semantic revision ready for planning",
        null);
  }

  private String runDesignAgent(String conversationId, String prompt) {
    // Quarkus LangChain4j treats @UserMessage as a Qute template. Mapping snippets such as
    // {subRequestType} must be escaped or render fails before the design agent can capture.
    String safePrompt = QuteUserMessageEscaping.escapeForAiServiceUserMessage(prompt);
    Context toolSessionContext = ToolSession.attachedContext();
    Multi<String> stream;
    if (designRunner != null) {
      stream = designRunner.apply(conversationId, safePrompt);
    } else if (designAgent != null) {
      stream = designAgent.chat(conversationId, safePrompt);
    } else {
      stream = Multi.createFrom().empty();
    }
    return String.join(
        "",
        ToolSession.propagateBinding(toolSessionContext, stream)
            .collect()
            .asList()
            .await()
            .indefinitely());
  }

  static String authoringPrompt(RequirementBrief brief, CompilerContract contract) {
    return authoringPrompt(brief, contract, "");
  }

  static String authoringPrompt(
      RequirementBrief brief, CompilerContract contract, StageExecutionContext context) {
    return authoringPrompt(brief, contract, repairSection(context));
  }

  static String authoringPrompt(
      RequirementBrief brief, CompilerContract contract, String repairSection) {
    StringBuilder prompt = new StringBuilder();
    prompt
        .append("Capture one ChainSemanticRevision from this approved requirement brief.\n\n")
        .append(RequirementBriefText.format(brief))
        .append("\n\nCompiler contract version: ")
        .append(contract.contractVersion())
        .append("\nSemantic schema version: ")
        .append(contract.semanticSchemaVersion())
        .append(
            "\n\nExternal interaction anchors are server-owned. Reference these node ids from"
                + " edges:");
    boolean anyAnchor = false;
    for (var entryPoint : brief.entryPoints()) {
      anyAnchor = true;
      prompt
          .append("\n- nodeId=")
          .append(entryPoint.entryPointId())
          .append(" role=INBOUND capabilityKey=")
          .append(entryPoint.capabilityKey());
    }
    Set<String> triggerFactIds = ChainSemanticCaptureAdapter.triggerFactIds(brief.entryPoints());
    for (RequirementServiceCall call : brief.serviceCalls()) {
      CatalogBindingHint hint = call.catalogBinding();
      if (hint == null
          || !ChainSemanticCaptureAdapter.materializesServiceCallNode(call, triggerFactIds)) {
        continue;
      }
      anyAnchor = true;
      prompt
          .append("\n- nodeId=")
          .append(call.serviceCallId())
          .append(" role=OUTBOUND operation=")
          .append(call.operation())
          .append(" integrationOperationId=")
          .append(hint.integrationOperationId());
    }
    if (!anyAnchor) {
      prompt.append("\n- none");
    }
    if (!brief.flow().transitions().isEmpty()) {
      prompt.append("\nApproved business transitions:");
      for (var transition : brief.flow().transitions()) {
        prompt
            .append("\n- ")
            .append(transition.sourceInteractionId())
            .append(" -> ")
            .append(transition.targetInteractionId());
      }
    }
    prompt.append(
        """

        Call captureChainSemanticRevision once. Copy sourceFactIds from the brief, taking the\
         value after each matching `=` sign. Copy mappingIntentId the same way when Mapping\
         intents include a mappingIntentId= token; otherwise omit it on every edge. Field-mapping\
         shells use elementType script. Do not mint a mapping id and do not reuse a\
         sourceFactId as mappingIntentId. External interaction anchors are server-owned.\
         Reference these node ids from edges, but do not list them under operations. Preserve\
         every approved business transition. You may insert internal processing nodes between\
         its source and target, but you may not reverse, omit, or add an external interaction\
         transition. Do not mint occurrence ids. The server derives the revision id, every edge\
         id, both versions above, the catalog capability behind each entry point, and every\
         service call node, so leave them out. List each internal node you do author under\
         operations, and each control-flow region under the list that matches its kind; omit\
         the region lists when the chain is linear.""");
    if (repairSection != null && !repairSection.isBlank()) {
      prompt.append(repairSection);
    }
    return prompt.toString();
  }

  private static String repairSection(StageExecutionContext context) {
    StageRepairEvidence repair = StageRepairEvidence.from(context);
    if (repair == null || !repair.hasEvidence()) {
      return "";
    }
    String rejection =
        repair.findings() != null && !repair.findings().isBlank()
            ? repair.findings()
            : repair.errorEvidence();
    StringBuilder extra = new StringBuilder();
    extra.append("\n\nThe previous capture was rejected:\n").append(rejection);
    if (repair.haltFollowUpText() != null && !repair.haltFollowUpText().isBlank()) {
      extra.append("\n\nAuthor correction:\n").append(repair.haltFollowUpText().trim());
    }
    extra.append(
        "\nRebuild the topology so this rejection cannot recur. Call captureChainSemanticRevision"
            + " once.");
    return extra.toString();
  }

  static String captureFailureMessage(String agentText) {
    String explained = agentText == null ? "" : agentText.strip();
    if (explained.isBlank()) {
      return "Design did not capture a chain semantic revision.";
    }
    return AiTraceLog.preview(explained, AiTraceLog.DEFAULT_TOOL_RESULT_CHARS);
  }

  private static RequirementBrief requirementBrief(StageExecutionContext context) {
    Object value = context.attributes().get("requirementBrief");
    return value instanceof RequirementBrief brief ? brief : null;
  }
}

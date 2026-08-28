package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.llm.agent.ChainSemanticDesignAgent;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Shared create-chain@2 design-input capability for {@code ids-entry} and {@code design-input}.
 * Design-input captures a typed semantic revision and renders IDS as an approval view.
 */
@ApplicationScoped
public class DesignInputCapability implements StageCapability {

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
    try {
      runDesignAgent(context.conversationId(), authoringPrompt(brief, contract));
    } finally {
      ProductCapabilityCaptureContext.unbind();
      ToolSession.clear();
    }
    ChainSemanticRevision revision = captured.get();
    if (revision == null) {
      return StageOutcome.of(
          StageOutcomeClass.NEEDS_INPUT,
          "Design did not capture a chain semantic revision. The agent must call"
              + " captureChainSemanticRevision before finishing.");
    }
    IdsDocument ids = idsRenderer.render(revision, contract);
    return new StageOutcome(
        StageOutcomeClass.CANDIDATE,
        List.of(
            new ArtifactCandidate(Kind.CHAIN_SEMANTIC_REVISION, revision, List.of()),
            new ArtifactCandidate(Kind.IDS_DOCUMENT, ids, List.of())),
        "semantic revision ready for approval",
        null);
  }

  private void runDesignAgent(String conversationId, String prompt) {
    Multi<String> stream;
    if (designRunner != null) {
      stream = designRunner.apply(conversationId, prompt);
    } else if (designAgent != null) {
      stream = designAgent.chat(conversationId, prompt);
    } else {
      stream = Multi.createFrom().empty();
    }
    stream.collect().asList().await().indefinitely();
  }

  static String authoringPrompt(RequirementBrief brief, CompilerContract contract) {
    StringBuilder prompt = new StringBuilder();
    prompt
        .append("Capture one ChainSemanticRevision from this approved requirement brief.\n\n")
        .append(RequirementBriefText.format(brief))
        .append("\n\nCompiler contract version: ")
        .append(contract.contractVersion())
        .append("\nSemantic schema version: ")
        .append(contract.semanticSchemaVersion())
        .append("\n\nResolved catalog bindings:");
    boolean anyBinding = false;
    for (RequirementServiceCall call : brief.serviceCalls()) {
      CatalogBindingHint hint = call.catalogBinding();
      if (hint == null) {
        continue;
      }
      anyBinding = true;
      prompt
          .append("\n- ")
          .append(call.serviceCallId())
          .append(" systemId=")
          .append(hint.systemId())
          .append(" specificationId=")
          .append(hint.specificationId())
          .append(" integrationOperationId=")
          .append(hint.integrationOperationId());
    }
    if (!anyBinding) {
      prompt.append("\n- none");
    }
    prompt.append(
        "\n\nCall captureChainSemanticRevision once. Copy entryPointId, sourceFactIds, and"
            + " serviceCallId from the brief. Do not mint occurrence ids.");
    return prompt.toString();
  }

  private static RequirementBrief requirementBrief(StageExecutionContext context) {
    Object value = context.attributes().get("requirementBrief");
    return value instanceof RequirementBrief brief ? brief : null;
  }
}

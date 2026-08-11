package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import io.smallrye.mutiny.Context;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsAgentCall;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;

/**
 * Product-pipeline requirement discovery stage. Uses brainstorming capture only; analysis never
 * receives the raw transcript from this capability.
 */
@ApplicationScoped
public class RequirementDiscoveryCapability implements StageCapability {

  public static final String CAPABILITY_ID = "requirement-discovery";

  private final GatherRequirementsAgent gatherRequirementsAgent;
  private final RequirementDraftStore draftStore;
  private final GatherRequirementsPromptBuilder promptBuilder;
  private final BiFunction<String, String, Multi<ChatEvent>> gatherRunner;
  private final CatalogBindingMatcher catalogBindingMatcher;

  @Inject
  public RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      CatalogBindingMatcher catalogBindingMatcher) {
    this(gatherRequirementsAgent, draftStore, promptBuilder, null, catalogBindingMatcher);
  }

  /** Test helper without catalog binding matcher. */
  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder) {
    this(gatherRequirementsAgent, draftStore, promptBuilder, null, null);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner) {
    this(gatherRequirementsAgent, draftStore, null, gatherRunner, null);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner) {
    this(gatherRequirementsAgent, draftStore, promptBuilder, gatherRunner, null);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner,
      CatalogBindingMatcher catalogBindingMatcher) {
    this.gatherRequirementsAgent = gatherRequirementsAgent;
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
    this.promptBuilder = promptBuilder;
    this.gatherRunner = gatherRunner;
    this.catalogBindingMatcher = catalogBindingMatcher;
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    String conversationId = context.conversationId();
    String userMessage =
        context.attributeAsString("userText") == null ? "" : context.attributeAsString("userText");
    // startOrResume advances the first stage before acceptInput attaches userText. LangChain4j
    // rejects blank @UserMessage, so wait for input instead of calling the gather agent.
    if (userMessage.isBlank()) {
      // Silent wait: first-stage advance has no userText yet. Do not surface agent capture jargon.
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
    }
    AtomicReference<RequirementDraft> captured = new AtomicReference<>();
    ToolSession.bind(conversationId);
    Context toolSessionContext = ToolSession.attachedContext();
    ProductCapabilityCaptureContext.bindDiscovery(
        context.runId(), conversationId, payload -> {
          if (payload instanceof RequirementDraft draft) {
            captured.set(draft);
          }
        });
    draftStore.beginTurn(conversationId);

    String skillId = RequirementDraftTool.SOURCE_SKILL_ID;
    SkillActivitySupport.bindParents(skillId);
    Multi<ChatEvent> agentStream =
        ToolSession.propagateBinding(toolSessionContext, runGather(conversationId, userMessage));
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(skillId)),
            agentStream
                .onItem()
                .transform(
                    event -> (CapabilitySignal) new CapabilitySignal.Message(tokenText(event)))
                .onCompletion()
                .switchTo(
                    () -> {
                      try {
                        return Multi.createFrom()
                            .iterable(
                                SkillActivitySupport.wrapTerminal(
                                    skillId, List.of(completeDiscovery(context, captured))));
                      } finally {
                        SkillActivitySupport.clearParents();
                        ProductCapabilityCaptureContext.unbind();
                        ToolSession.clear();
                      }
                    })
                .onFailure()
                .recoverWithMulti(
                    error -> {
                      SkillActivitySupport.clearParents();
                      ProductCapabilityCaptureContext.unbind();
                      ToolSession.clear();
                      return Multi.createFrom()
                          .items(
                              SkillActivitySupport.error(skillId),
                              new CapabilitySignal.Completed(
                                  StageOutcome.of(
                                      StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                      error.getMessage() == null
                                          ? "requirement discovery failed"
                                          : error.getMessage())));
                    }));
  }

  private CapabilitySignal.Completed completeDiscovery(
      StageExecutionContext context,
      AtomicReference<RequirementDraft> captured) {
    String conversationId = context.conversationId();
    RequirementDraft draft =
        captured.get() != null ? captured.get() : draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.NEEDS_INPUT,
              RequirementDraftTool.CAPTURE_MISSING_USER_GUIDANCE));
    }
    // Pending APIHub import hands off to import-stage (ADR 0001) even when the draft
    // is still NEEDS_INPUT with the pinned import-confirm open question.
    boolean pendingImportHandoff = draft.hasPendingImport() && !draft.facts().isEmpty();
    if (!draft.readyForPlan() && !pendingImportHandoff) {
      // Blank on purpose: the gather agent already streamed the clarifying question. Emitting an
      // internal draft-state sentence glued it to that text and leaked READY_FOR_PLAN into chat.
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    if (draft.facts().isEmpty()) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.CONTRACT_FAILURE,
              "READY_FOR_PLAN draft must include explicit facts"));
    }
    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(
        new ArtifactCandidate(
            CompilationArtifacts.Kind.REQUIREMENT_DRAFT, draft, context.inputRefs()));
    // Read-only exact catalog hits only — never APIHub or import. Emit hints only when the active
    // stage declares catalog-binding-hint (optionalProduces or produces). create-chain@1 does not,
    // so it stays on REQUIREMENT_DRAFT alone and avoids unknown-candidate CONTRACT_FAILURE.
    if (stageDeclaresCatalogBindingHint(context)) {
      candidates.addAll(exactCatalogBindingHints(draft));
    }
    // create-chain@1 gates on draft approval (CANDIDATE). create-chain@2 has no discovery
    // approval, so SUCCEEDED advances to requirement-analysis without WAITING_FOR_APPROVAL.
    StageOutcomeClass outcomeClass =
        stageRequiresApproval(context)
            ? StageOutcomeClass.CANDIDATE
            : StageOutcomeClass.SUCCEEDED;
    return new CapabilitySignal.Completed(
        new StageOutcome(
            outcomeClass, candidates, "Requirement draft candidate ready", null));
  }

  /** True when the active profile stage declares an approval gate for this capability run. */
  static boolean stageRequiresApproval(StageExecutionContext context) {
    if (context == null || context.profile() == null || context.stageId() == null) {
      // Null profile is the unit-test / legacy path that still expects a draft candidate.
      return true;
    }
    return context.profile().stages().stream()
        .anyMatch(
            stage ->
                context.stageId().equals(stage.stageId()) && stage.approval() != null);
  }

  /**
   * True when the active profile stage declares {@code catalog-binding-hint} as a produced type
   * ({@code optionalProduces} or {@code produces}).
   */
  static boolean stageDeclaresCatalogBindingHint(StageExecutionContext context) {
    if (context == null || context.profile() == null || context.stageId() == null) {
      return false;
    }
    return context.profile().stages().stream()
        .filter(stage -> context.stageId().equals(stage.stageId()))
        .anyMatch(RequirementDiscoveryCapability::declaresCatalogBindingHintProduce);
  }

  private static boolean declaresCatalogBindingHintProduce(ProfileStage stage) {
    if (stage == null) {
      return false;
    }
    return declaresKind(stage.optionalProduces(), CompilationArtifacts.Kind.CATALOG_BINDING_HINT)
        || declaresKind(stage.produces(), CompilationArtifacts.Kind.CATALOG_BINDING_HINT);
  }

  private static boolean declaresKind(
      List<ArtifactTypeRef> refs, CompilationArtifacts.Kind kind) {
    if (refs == null || refs.isEmpty()) {
      return false;
    }
    for (ArtifactTypeRef ref : refs) {
      if (ref != null && ref.matches(kind)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Emits {@code CATALOG_BINDING_HINT} only when the local catalog has exactly one reusable match
   * for a {@link RequirementFactKind#SERVICE_CALL} fact.
   */
  List<ArtifactCandidate> exactCatalogBindingHints(RequirementDraft draft) {
    if (catalogBindingMatcher == null || draft == null || draft.facts().isEmpty()) {
      return List.of();
    }
    List<ArtifactCandidate> hints = new ArrayList<>();
    for (RequirementFact fact : draft.facts()) {
      if (fact == null || fact.polarity() != RequirementFactPolarity.POSITIVE) {
        continue;
      }
      if (fact.kind() != RequirementFactKind.SERVICE_CALL) {
        continue;
      }
      String operationQuery = fact.text() == null ? "" : fact.text().trim();
      if (operationQuery.isEmpty()) {
        continue;
      }
      NormalizedDesignFlow probe = probeFlowForFact(fact, operationQuery);
      CatalogBindingMatcher.MatchResult match =
          catalogBindingMatcher.match(probe, probe.steps().getFirst());
      if (!(match instanceof CatalogBindingMatcher.MatchResult.Exact exact)) {
        continue;
      }
      CatalogBindingMatcher.CatalogMatch hit = exact.match();
      CatalogBindingHint hint =
          new CatalogBindingHint(
              "1",
              fact.sourceFactId(),
              operationQuery,
              hit.systemId(),
              hit.specificationGroupId(),
              hit.specificationId(),
              hit.integrationOperationId(),
              "catalog",
              Instant.now(),
              hit.evidenceRef());
      hints.add(
          new ArtifactCandidate(CompilationArtifacts.Kind.CATALOG_BINDING_HINT, hint, List.of()));
    }
    return hints;
  }

  private static NormalizedDesignFlow probeFlowForFact(RequirementFact fact, String operationQuery) {
    String participantId = "svc";
    String label =
        fact.capabilityKey() == null || fact.capabilityKey().isBlank()
            ? "service"
            : fact.capabilityKey();
    return new NormalizedDesignFlow(
        "1",
        "hint-probe",
        "hint-probe",
        "",
        new NormalizedDesignFlow.Trigger(
            "http", "client", null, null, null, List.of(fact.sourceFactId())),
        List.of(
            new NormalizedDesignFlow.Participant("client", "Client", "EXTERNAL", List.of()),
            new NormalizedDesignFlow.Participant(
                participantId, label, "EXTERNAL", List.of(fact.sourceFactId()))),
        List.of(
            new NormalizedDesignFlow.Step(
                "call-1",
                "service-call",
                "client",
                participantId,
                operationQuery,
                "",
                List.of(fact.sourceFactId()))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private Multi<ChatEvent> runGather(String conversationId, String userMessage) {
    if (gatherRunner != null) {
      return gatherRunner.apply(conversationId, userMessage);
    }
    if (gatherRequirementsAgent == null) {
      return Multi.createFrom().empty();
    }
    String agentInput =
        promptBuilder != null
            ? promptBuilder.wrap(conversationId, userMessage)
            : (userMessage == null ? "" : userMessage);
    return GatherRequirementsAgentCall.run(gatherRequirementsAgent, conversationId, agentInput);
  }

  private static String tokenText(ChatEvent event) {
    if (event instanceof ChatEvent.Token token) {
      return token.text() == null ? "" : token.text();
    }
    return "";
  }
}

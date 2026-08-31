package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.edit.ChainEditClarificationStore;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompiler;
import org.qubership.integration.platform.ai.chain.edit.ChainEditEscalationStore;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ChainEditOutcome;
import org.qubership.integration.platform.ai.chain.edit.ChainEditRequest;
import org.qubership.integration.platform.ai.chain.edit.ChainEditSkillProgress;
import org.qubership.integration.platform.ai.chain.edit.StructuralBindingContinuation;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.patch.ChainEditProposalAssembler;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchStore;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchSummary;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.patch.PatchedChain;
import org.qubership.integration.platform.ai.chain.patch.ProposedChainPatch;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.LastAssistantTurn;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContext;
import org.qubership.integration.platform.ai.chat.failure.CatalogOperation;
import org.qubership.integration.platform.ai.chat.failure.KnownFailure;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.AnswerShape;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.InfoNeed;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.TurnReferent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/**
 * Changes part of a chain the user already has in the catalog.
 *
 * <p>Reads the chain, asks the model for a patch against it, and offers that patch as a decision
 * card. Nothing reaches the catalog until the reader answers the card, and what is written is
 * bounded twice: by the ownership policy, which holds whatever the reader answered, and by the
 * writer, which sends only the elements the patch names.
 */
@ApplicationScoped
@ForScenario(ScenarioType.COMPARE_AND_PATCH)
public class ChainPatchScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ChainPatchScenario.class);

  private final ChainContextExtractor chainContextExtractor;
  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter importer;
  private final ChainEditCompiler editCompiler;
  private final ChainEditEscalationStore escalationStore;
  private final ChainEditClarificationStore clarificationStore;
  private final ChainEditProposalAssembler assembler;
  private final ChainPatchStore patchStore;
  private final ChainPatchWriter writer;
  private final CanonicalGraphDigest canonicalGraphDigest;
  private final KnownFailureMapper knownFailureMapper;
  private final PinnedFailureStore pinnedFailureStore;
  private final ChainQuestionScenario questionScenario;

  @Inject
  public ChainPatchScenario(
      ChainContextExtractor chainContextExtractor,
      ChainCatalogFactsService factsService,
      ChainPlanGraphImporter importer,
      ChainEditCompiler editCompiler,
      ChainEditEscalationStore escalationStore,
      ChainEditClarificationStore clarificationStore,
      ChainEditProposalAssembler assembler,
      ChainPatchStore patchStore,
      ChainPatchWriter writer,
      CanonicalGraphDigest canonicalGraphDigest,
      KnownFailureMapper knownFailureMapper,
      PinnedFailureStore pinnedFailureStore,
      @ForScenario(ScenarioType.ASK_CHAIN) ChainQuestionScenario questionScenario) {
    this.chainContextExtractor = Objects.requireNonNull(chainContextExtractor);
    this.factsService = Objects.requireNonNull(factsService);
    this.importer = Objects.requireNonNull(importer);
    this.editCompiler = Objects.requireNonNull(editCompiler);
    this.escalationStore = Objects.requireNonNull(escalationStore);
    this.clarificationStore = Objects.requireNonNull(clarificationStore);
    this.assembler = Objects.requireNonNull(assembler);
    this.patchStore = Objects.requireNonNull(patchStore);
    this.writer = Objects.requireNonNull(writer);
    this.canonicalGraphDigest = Objects.requireNonNull(canonicalGraphDigest);
    this.knownFailureMapper = Objects.requireNonNull(knownFailureMapper);
    this.pinnedFailureStore = Objects.requireNonNull(pinnedFailureStore);
    this.questionScenario = Objects.requireNonNull(questionScenario);
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    ChatDecisionCommand decision = request == null ? null : request.getDecision();
    if (decision != null && ChatEvent.APPLY_CHAIN_PATCH_ACTION.equals(decision.getAction())) {
      return streamCompile(progress -> applyAnsweredPatch(conversationId, decision));
    }
    if (decision != null && ChatEvent.IMPORT_ACTION.equals(decision.getAction())) {
      return streamCompile(progress -> resumeAfterImport(request, conversationId, progress));
    }
    if (decision != null && ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION.equals(decision.getAction())) {
      request.setResolvedEffectiveUserText(
          "Fix the deployment failure described in the safe failure summary.");
    }
    return streamCompile(progress -> proposePatch(request, conversationId, progress));
  }

  /**
   * Compiles after the SSE turn has subscribed, so skill/tool {@code event: step} frames reach the
   * same activity timeline CREATE uses.
   *
   * <p>Chat SSE binds the scenario on the Vert.x event loop. Catalog RestClient is blocking, so
   * the compile (including {@code getChain}) runs on the Mutiny worker pool.
   */
  private static Multi<ChatEvent> streamCompile(
      Function<BiConsumer<String, String>, Multi<ChatEvent>> work) {
    return Multi.createFrom()
        .<ChatEvent>emitter(
            emitter -> {
              try {
                work.apply(ChainEditSkillProgress.toChat(emitter::emit))
                    .subscribe()
                    .with(emitter::emit, emitter::fail, emitter::complete);
              } catch (RuntimeException failure) {
                SkillActivitySupport.clearParents();
                emitter.fail(failure);
              }
            })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  private Multi<ChatEvent> proposePatch(
      ChatRequest request, String conversationId, BiConsumer<String, String> skillProgress) {
    String chainId = chainContextExtractor.resolveChainId(request, conversationId).orElse(null);
    if (chainId == null) {
      return message(
          "No chain context found. Open the chain you want to change, then say what to change.");
    }

    ImportedChainPlan imported;
    try {
      OpenChainTurnContext turn = request == null ? null : request.getOpenChainTurnContext();
      ChainCatalogFacts facts =
          turn == null || turn.chainFacts().isEmpty()
              ? factsService.load(chainId)
              : turn.chainFacts().get();
      imported = importer.importChain(facts);
    } catch (RuntimeException e) {
      return knownOrRethrow(e, conversationId, chainId);
    }

    // The last proposal is cleared first so a turn that proposes nothing cannot be answered with it.
    // An unanswered import escalation goes with it: declining it is saying something else next.
    patchStore.clearProposal(conversationId);
    escalationStore.clear(conversationId);
    // A held clarification is read once, and only for the chain it was asked about: switching to a
    // different chain without answering is saying something else, the same as an unrelated request.
    Optional<ChainEditClarificationStore.PendingClarification> heldClarification =
        clarificationStore.take(conversationId).filter(pending -> chainId.equals(pending.chainId()));

    String userMessage = request == null ? "" : request.getEffectiveUserText();
    try {
      ChainEditOutcome outcome =
          heldClarification.isPresent()
              ? resumeClarification(
                  conversationId,
                  chainId,
                  imported,
                  userMessage,
                  heldClarification.get(),
                  request,
                  skillProgress)
              : compileEdit(conversationId, chainId, imported, userMessage, request, skillProgress);
      return fromCompiler(request, conversationId, chainId, imported, outcome);
    } catch (RuntimeException e) {
      return knownOrRethrow(e, conversationId, chainId);
    }
  }

  /** Compiles the request through the owning skill. */
  private ChainEditOutcome compileEdit(
      String conversationId,
      String chainId,
      ImportedChainPlan imported,
      String userMessage,
      ChatRequest chatRequest,
      BiConsumer<String, String> skillProgress) {
    return editCompiler.compile(
        editRequest(conversationId, chainId, imported, userMessage, chatRequest),
        skillProgress);
  }

  /**
   * Continues an edit whose classifier stopped to ask which element or aspect the reader meant.
   *
   * <p>The held capture and the question travel with this turn's message, so the classifier can
   * complete the same request rather than resolving the message with no record of having asked. If
   * the message turns out to be unrelated, the classifier resolves it as its own new request.
   */
  private ChainEditOutcome resumeClarification(
      String conversationId,
      String chainId,
      ImportedChainPlan imported,
      String userMessage,
      ChainEditClarificationStore.PendingClarification pending,
      ChatRequest chatRequest,
      BiConsumer<String, String> skillProgress) {
    ChainEditRequest request =
        editRequest(conversationId, chainId, imported, userMessage, chatRequest);
    return pending.continuation() == null
        ? editCompiler.resumeAfterClarification(
            request, pending.heldIntent(), pending.question(), skillProgress)
        : editCompiler.resumeAfterClarification(
            request,
            pending.heldIntent(),
            pending.question(),
            pending.continuation(),
            skillProgress);
  }

  private Multi<ChatEvent> fromCompiler(
      ChatRequest request,
      String conversationId,
      String chainId,
      ImportedChainPlan imported,
      ChainEditOutcome outcome) {
    return switch (outcome) {
      case ChainEditOutcome.Proposal proposal ->
          offer(conversationId, chainId, imported, proposal.netPatch());
      case ChainEditOutcome.Clarification(
          String question,
          List<String> choices,
          ChainEditIntent heldIntent,
          StructuralBindingContinuation continuation) -> {
        clarificationStore.put(
            conversationId,
            new ChainEditClarificationStore.PendingClarification(
                chainId, heldIntent, question, continuation));
        yield message(
            choices.isEmpty()
                ? question
                : question + "\n" + choices.stream().map(c -> "- " + c).collect(joining("\n")));
      }
      case ChainEditOutcome.ResolutionFailure(String text) -> message(text);
      case ChainEditOutcome.NoChange ignored -> answerAsQuestion(request, conversationId);
      case ChainEditOutcome.CompilationFailure(String text) -> message(text);
      case ChainEditOutcome.Escalation escalation -> escalate(conversationId, chainId, escalation);
      case ChainEditOutcome.Unsupported(var action) ->
          message("No compiler skill owns a " + action + " edit, so I did not change anything.");
    };
  }

  /**
   * Offers the import as its own decision, and holds the edit until it is answered.
   *
   * <p>An import is a real change to the catalog, so it gets a decision rather than prose. Nothing
   * happens if the reader says nothing: the next turn clears the hold and starts over.
   */
  private Multi<ChatEvent> escalate(
      String conversationId, String chainId, ChainEditOutcome.Escalation escalation) {
    String candidateId =
        escalation.refs().packageId() + ":" + escalation.refs().version();
    escalationStore.put(
        conversationId,
        new ChainEditEscalationStore.PendingChainEdit(
            chainId,
            "",
            escalation.intent(),
            escalation.refs(),
            candidateId,
            escalation.continuation()));
    return Multi.createFrom()
        .item(
            ChatEvent.importDecision(
                candidateId,
                escalation.message() + " Import it and continue with the change?"));
  }

  /** Continues the held edit once the reader has approved the import. */
  private Multi<ChatEvent> resumeAfterImport(
      ChatRequest chatRequest, String conversationId, BiConsumer<String, String> skillProgress) {
    ChainEditEscalationStore.PendingChainEdit pendingEdit =
        escalationStore.take(conversationId).orElse(null);
    if (pendingEdit == null) {
      return message("There is no change waiting on an import. Say what to change.");
    }
    ImportedChainPlan imported;
    try {
      imported = importer.importChain(factsService.load(pendingEdit.chainId()));
    } catch (RuntimeException e) {
      return knownOrRethrow(e, conversationId, pendingEdit.chainId());
    }
    ChainEditRequest request =
        editRequest(
            conversationId,
            pendingEdit.chainId(),
            imported,
            pendingEdit.userRequest(),
            chatRequest);
    ChainEditOutcome resumed =
        pendingEdit.continuation() == null
            ? editCompiler.resumeAfterImport(
                request, pendingEdit.intent(), pendingEdit.refs(), skillProgress)
            : editCompiler.resumeAfterImport(
                request,
                pendingEdit.intent(),
                pendingEdit.refs(),
                pendingEdit.continuation(),
                skillProgress);
    return fromCompiler(chatRequest, conversationId, pendingEdit.chainId(), imported, resumed);
  }

  private Multi<ChatEvent> answerAsQuestion(ChatRequest request, String conversationId) {
    request.setOpenChainTurnPlan(
        new OpenChainTurnPlan.Ask(
            TurnReferent.LAST_TURN, Set.of(InfoNeed.FACTS), AnswerShape.EXPLAIN));
    return questionScenario.handle(request, conversationId, ScenarioType.ASK_CHAIN);
  }

  private Multi<ChatEvent> offer(
      String conversationId, String chainId, ImportedChainPlan imported, GraphPatch proposed) {
    ChainEditProposalAssembler.Assembled assembled =
        assembler.assemble(imported, chainId, proposed, true);
    if (assembled instanceof ChainEditProposalAssembler.Assembled.Refused(String reason, var kind)) {
      return message(reason);
    }
    ChainEditProposalAssembler.Assembled.Ready ready =
        (ChainEditProposalAssembler.Assembled.Ready) assembled;

    String patchHash = canonicalGraphDigest.sha256(ready.patched().graph());
    String summary = ChainPatchSummary.describe(imported.graph(), ready.patch());
    patchStore.putProposal(
        conversationId,
        new ProposedChainPatch(
            chainId,
            ready.patch(),
            ready.patched(),
            patchHash,
            imported.baseGraphDigest(),
            summary));

    return Multi.createFrom().item(ChatEvent.chainPatchDecision(patchHash, summary));
  }

  private Multi<ChatEvent> applyAnsweredPatch(String conversationId, ChatDecisionCommand decision) {
    Optional<ProposedChainPatch> proposal = patchStore.findProposal(conversationId);
    if (proposal.isEmpty() || !proposal.get().patchHash().equals(decision.getArtifactHash())) {
      return message("That change is no longer the one on offer. Ask for the change again.");
    }

    ProposedChainPatch proposed = proposal.get();
    // The chain is read again rather than trusted: between the card and the answer someone may have
    // edited the very element this patch rewrites, and a merge would drop their work silently.
    String currentDigest;
    try {
      currentDigest = importer.importChain(factsService.load(proposed.chainId())).baseGraphDigest();
    } catch (RuntimeException e) {
      return knownOrRethrow(e, conversationId, proposed.chainId());
    }
    if (!currentDigest.equals(proposed.baseGraphDigest())) {
      patchStore.clearProposal(conversationId);
      return message(
          "The chain changed since I proposed this, so I did not write anything."
              + " Ask for the change again against the chain as it stands now.");
    }

    patchStore.clearProposal(conversationId);
    ChainPatchWriteResult result = writer.write(proposed.patched(), proposed.patch());
    String text = describe(result, proposed);
    if (result.succeeded()) {
      pinnedFailureStore.clear(conversationId, proposed.chainId());
      return Multi.createFrom()
          .item(ChatEvent.token(text, LastAssistantTurn.Kind.PATCH_WRITE_OK));
    }
    pinnedFailureStore.put(
        new PinnedFailure(
            conversationId,
            proposed.chainId(),
            text,
            result.error() == null ? "chain patch write failed" : result.error()));
    return Multi.createFrom()
        .item(ChatEvent.token(text, LastAssistantTurn.Kind.PATCH_WRITE_FAILED));
  }

  private String describe(ChainPatchWriteResult result, ProposedChainPatch proposed) {
    List<String> changed =
        result.changedElementIds().stream()
            .map(elementId -> elementName(proposed.patched().graph(), elementId))
            .toList();
    // Removed elements are named from the chain as it was: the patched graph no longer holds them.
    List<String> removed =
        result.removedElementIds().stream()
            .map(elementId -> elementName(proposed.patched().before(), elementId))
            .toList();
    if (result.succeeded()) {
      StringBuilder done = new StringBuilder();
      if (!removed.isEmpty()) {
        done.append("Removed ").append(String.join(", ", removed)).append(" from the chain.");
      }
      if (!changed.isEmpty()) {
        if (!done.isEmpty()) {
          done.append(" ");
        }
        done.append("Changed ").append(String.join(", ", changed)).append(" in the chain.");
      }
      return done.isEmpty() ? "Nothing needed changing." : done.toString();
    }
    List<String> failed =
        result.failedElementIds().stream()
            .map(elementId -> elementName(proposed.patched().graph(), elementId))
            .toList();
    StringBuilder text = new StringBuilder();
    if (!changed.isEmpty()) {
      text.append("Changed ").append(String.join(", ", changed)).append(". ");
    }
    if (failed.isEmpty()) {
      text.append("Could not finish the requested chain change.");
    } else {
      text.append("Could not change ").append(String.join(", ", failed)).append(".");
    }
    text.append(" The catalog did not confirm the requested write.");
    String rollback = describeRollback(result, removed);
    if (rollback != null) {
      text.append(" ").append(rollback);
    }
    return text.toString();
  }

  /** What became of the part that did land, which is the only thing the reader can act on. */
  private static String describeRollback(ChainPatchWriteResult result, List<String> removed) {
    return switch (result.rollback()) {
      case COMPLETED -> "I put the chain back as it was.";
      case PARTIAL ->
          "I put back what I could, but not all of it — read the chain before changing it again.";
      case REFUSED ->
          "I could not put the chain back: "
              + String.join(", ", removed)
              + " had already been removed, and removing cannot be undone.";
      case NOT_ATTEMPTED -> null;
    };
  }

  private static String elementName(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .map(node -> node.label() == null || node.label().isBlank() ? node.nodeId() : node.label())
        .findFirst()
        .orElse(nodeId);
  }

  private static Multi<ChatEvent> message(String text) {
    return Multi.createFrom().item(ChatEvent.token(text));
  }

  private static ChainEditRequest editRequest(
      String conversationId,
      String chainId,
      ImportedChainPlan imported,
      String userMessage,
      ChatRequest chatRequest) {
    return new ChainEditRequest(
        conversationId,
        chainId,
        conversationId + "-edit-" + UUID.randomUUID(),
        imported,
        userMessage,
        null,
        transcriptWindow(chatRequest),
        pinnedFailureSafeText(chatRequest));
  }

  private static String transcriptWindow(ChatRequest request) {
    OpenChainTurnContext turn = request == null ? null : request.getOpenChainTurnContext();
    return turn == null ? "" : turn.transcriptWindow();
  }

  private static String pinnedFailureSafeText(ChatRequest request) {
    OpenChainTurnContext turn = request == null ? null : request.getOpenChainTurnContext();
    if (turn == null) {
      return "";
    }
    Optional<PinnedFailure> pin = turn.pinnedFailure();
    return pin.map(ChainPatchScenario::pinnedFailureForEdit).orElse("");
  }

  /**
   * The reader-facing summary plus the catalog runtime diagnostic. The chat token stays on
   * {@code safeText}; the compiler needs the diagnostic to choose an element and a property.
   */
  static String pinnedFailureForEdit(PinnedFailure pin) {
    String safe = pin.safeText() == null ? "" : pin.safeText().trim();
    String diagnostic = pin.diagnosticDetail() == null ? "" : pin.diagnosticDetail().trim();
    if (diagnostic.isEmpty() || safe.contains(diagnostic)) {
      return safe;
    }
    if (safe.isEmpty()) {
      return diagnostic;
    }
    return safe + "\n" + diagnostic;
  }

  private Multi<ChatEvent> knownOrRethrow(Throwable error, String conversationId, String chainId) {
    Optional<KnownFailure> known = knownFailureMapper.tryMap(error, CatalogOperation.FACTS);
    if (known.isEmpty()) {
      return Multi.createFrom().failure(error);
    }
    KnownFailure failure = known.get();
    LOG.warnf(error, "Chain read failed conversationId=%s chainId=%s", conversationId, chainId);
    if (chainId != null && !chainId.isBlank()) {
      pinnedFailureStore.put(
          new PinnedFailure(
              conversationId, chainId, failure.safeText(), failure.diagnosticDetail()));
    }
    return Multi.createFrom().item(ChatEvent.token(failure.safeText()));
  }
}

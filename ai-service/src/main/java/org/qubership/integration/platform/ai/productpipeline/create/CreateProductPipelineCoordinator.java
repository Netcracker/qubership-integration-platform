package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainMaterializedSummary;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.llm.agent.ApprovalIntentAgent;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Browser chat coordinator for product CREATE turns.
 *
 * <p>Maps {@code ChatRequest}/{@code ChatEvent} onto {@link ProductPipelineRuntime} commands and
 * durable run evidence. Transport-neutral callers (A2A) must use {@link
 * org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade}
 * instead of this coordinator. Browser convenience approval ({@link #approveCurrent} and free-form
 * Agree text) stays here.
 */
@ApplicationScoped
public class CreateProductPipelineCoordinator {

  private static final Pattern IMPLEMENT_COMMAND =
      Pattern.compile("^Implement ([0-9a-f]{64})$");

  private final CreateRunSelectionService selectionService;
  private final CreateRunBindingStore bindingStore;
  private final ProductPipelineRuntime runtime;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final ApprovalPrompts approvalPrompts;
  private final ApprovalIntentAgent approvalIntentAgent;

  /** Null in unit tests, which build the coordinator without a blob store. */
  @Inject ApprovalQuestionStore approvalQuestions;

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(CreateProductPipelineCoordinator.class);

  @Inject
  public CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ApprovalPromptAgent approvalPromptAgent,
      ApprovalIntentAgent approvalIntentAgent) {
    this(
        selectionService,
        bindingStore,
        runtime,
        runStore,
        profileCatalog,
        new ApprovalPrompts(approvalPromptAgent),
        approvalIntentAgent);
  }

  /** Test helper without approval-prompt LLM (English fallback CTAs). */
  public CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog, new ApprovalPrompts());
  }

  CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ApprovalPrompts approvalPrompts) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog, approvalPrompts, null);
  }

  CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ApprovalPrompts approvalPrompts,
      ApprovalIntentAgent approvalIntentAgent) {
    this.approvalIntentAgent = approvalIntentAgent;
    this.selectionService = Objects.requireNonNull(selectionService, "selectionService");
    this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.approvalPrompts = Objects.requireNonNull(approvalPrompts, "approvalPrompts");
  }

  CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      boolean unused) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog);
  }

  public Optional<ProductPipelineRunDocument> loadRun(String conversationId) {
    return runStore.loadByConversation(conversationId);
  }

  public Multi<ChatEvent> handle(ChatRequest request, String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    selectionService.selectOrCreate(conversationId);
    CreateRunBinding binding =
        bindingStore
            .load(conversationId)
            .orElseThrow(
                () -> new IllegalStateException("missing durable binding for " + conversationId));

    Optional<ProductPipelineRunDocument> existing = runStore.loadByConversation(conversationId);
    String text = request != null ? request.getEffectiveUserText() : "";
    ScenarioType hint = request != null ? request.getScenarioHint() : null;

    if (existing.isEmpty()) {
      Multi<ChatEvent> started =
          mapSignals(
              runtime.startOrResume(
                  new StartOrResumeCommand(
                      conversationId,
                      binding.productRunId(),
                      profileCatalog.require(
                          binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                      binding.runManifest())),
              conversationId);
      return started
          .onCompletion()
          .switchTo(
              () -> {
                ProductPipelineRunDocument created =
                    runStore.loadByConversation(conversationId).orElse(null);
                if (created != null
                    && created.run().status() == RunStatus.WAITING_FOR_INPUT
                    && text != null
                    && !text.isBlank()) {
                  return mapSignals(
                      runtime.acceptInput(
                          new AcceptInputCommand(created.run().runId(), text)),
                      conversationId);
                }
                return Multi.createFrom().empty();
              });
    }

    ProductPipelineRunDocument doc = existing.get();
    RunStatus status = doc.run().status();
    if (status == RunStatus.PLAN_APPROVED) {
      if (hint == ScenarioType.IMPLEMENT_CHAIN
          || (text != null && text.toLowerCase(Locale.ROOT).contains("implement"))) {
        return Multi.createFrom()
            .item(
                ChatEvent.token(
                    "This CREATE flow stops after the plan is approved. Implement and assembly are"
                        + " out of scope for this slice."));
      }
      return Multi.createFrom()
          .item(ChatEvent.token("Implementation plan approved."));
    }
    if (status == RunStatus.CHAIN_MATERIALIZED) {
      return Multi.createFrom()
          .item(ChatEvent.token(chainMaterializedChatSummary(doc.run().runId())));
    }
    if (status == RunStatus.FAILED) {
      return Multi.createFrom()
          .item(
              ChatEvent.error(
                  "Product CREATE run failed"
                      + (doc.attempts().isEmpty()
                          ? ""
                          : ": " + doc.attempts().get(doc.attempts().size() - 1).failureEvidence())));
    }
    if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
      String hash = resolveImplementHash(doc.run().runId(), text);
      if (hash != null) {
        return mapSignals(
            runtime.implement(
                new ImplementCommand(doc.run().runId(), hash, doc.run().runRevision())),
            conversationId);
      }
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  approvalPrompts.implementContinuationPrompt(languageReference(doc))));
    }
    if (status == RunStatus.WAITING_FOR_APPROVAL && isApproval(text)) {
      // Only a literal token carries a deliberate confirmation through to materialization. A
      // classified approval advances the stage and then stops at the implement gate.
      boolean literal = isLiteralApproval(text);
      Reference candidate = approvableReference(doc);
      return mapSignals(
              runtime.approve(
                  new ApproveCommand(doc.run().runId(), candidate, doc.run().runRevision())),
              conversationId)
          .onCompletion()
          .switchTo(() -> autoImplementAfterPlanApproval(conversationId, literal));
    }
    if (status == RunStatus.WAITING_FOR_INPUT || status == RunStatus.WAITING_FOR_APPROVAL) {
      return mapSignals(
          runtime.acceptInput(new AcceptInputCommand(doc.run().runId(), text == null ? "" : text)),
          conversationId);
    }
    if (status == RunStatus.RUNNING) {
      // Resume an in-flight run (e.g. previous SSE aborted mid-stage). Do not acceptInput —
      // that is only valid at WAITING_FOR_INPUT / WAITING_FOR_APPROVAL.
      return mapSignals(
          runtime.startOrResume(
              new StartOrResumeCommand(
                  conversationId,
                  binding.productRunId(),
                  profileCatalog.require(
                      binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                  binding.runManifest())),
          conversationId);
    }
    return mapSignals(
        runtime.startOrResume(
            new StartOrResumeCommand(
                conversationId,
                binding.productRunId(),
                profileCatalog.require(
                    binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                binding.runManifest())),
        conversationId);
  }

  public Multi<ChatEvent> approveCurrent(String conversationId) {
    ProductPipelineRunDocument doc =
        loadRun(conversationId)
            .orElseThrow(() -> new IllegalStateException("no run for " + conversationId));
    Reference candidate = approvableReference(doc);
    return mapSignals(
            runtime.approve(
                new ApproveCommand(doc.run().runId(), candidate, doc.run().runRevision())),
            conversationId)
        .onCompletion()
        .switchTo(() -> autoImplementAfterPlanApproval(conversationId, true));
  }

  /**
   * After the planning stage is approved, create-chain waits at an implementation gate. Chat users
   * should not see content hashes — Agree on the plan continues straight into materialization.
   */
  private Multi<ChatEvent> autoImplementAfterPlanApproval(
      String conversationId, boolean confirmedLiterally) {
    ProductPipelineRunDocument after = runStore.loadByConversation(conversationId).orElse(null);
    if (after == null || after.run().status() != RunStatus.WAITING_FOR_IMPLEMENT) {
      return Multi.createFrom().empty();
    }
    if (!confirmedLiterally) {
      // Materialization writes a chain into the catalog and nothing here removes it again, so it
      // is the one step that does not run on a classified intent. The run stays at the implement
      // gate and the caller is asked for a word this code can check literally.
      LOG.infof(
          "Plan approved by classified intent; holding at the implement gate for an explicit"
              + " confirmation runId=%s",
          after.run().runId());
      return Multi.createFrom()
          .item(ChatEvent.token(approvalPrompts.implementContinuationPrompt(languageReference(after))));
    }
    Optional<String> hash =
        runtime.approvedPlanContentHash(after.run().runId()).filter(h -> !h.isBlank());
    if (hash.isEmpty()) {
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  approvalPrompts.implementContinuationPrompt(languageReference(after))));
    }
    return mapSignals(
        runtime.implement(
            new ImplementCommand(
                after.run().runId(), hash.get(), after.run().runRevision())),
        conversationId);
  }

  private static Reference approvableReference(ProductPipelineRunDocument doc) {
    StageSnapshot stage =
        doc.run().stages().stream()
            .filter(snapshot -> snapshot.stageId().equals(doc.run().currentStageId()))
            .findFirst()
            .orElseThrow();
    if (stage.approvableReference() != null) {
      return stage.approvableReference();
    }
    if (stage.status() == StageStatus.WAITING_FOR_APPROVAL && !stage.outputRefs().isEmpty()) {
      return stage.outputRefs().get(stage.outputRefs().size() - 1);
    }
    throw new IllegalStateException(
        "no approvable reference for stage " + doc.run().currentStageId());
  }

  private Multi<ChatEvent> mapSignals(Multi<PipelineSignal> signals, String conversationId) {
    return signals
        .onItem()
        .transformToMultiAndConcatenate(
            signal -> {
              if (signal instanceof PipelineSignal.Message message) {
                return Multi.createFrom().item(ChatEvent.token(message.text()));
              }
              if (signal instanceof PipelineSignal.Progress progress) {
                return Multi.createFrom()
                    .item(
                        ChatEvent.step(
                            "stage:" + progress.stageId(),
                            "pipeline",
                            "running",
                            progress.label(),
                            null));
              }
              if (signal instanceof PipelineSignal.SkillProgress skillProgress) {
                return Multi.createFrom()
                    .item(
                        ChatEvent.skillStep(skillProgress.skillId(), skillProgress.status()));
              }
              if (signal instanceof PipelineSignal.WaitingForInput waiting) {
                // Prefer the stage outcome prompt (e.g. design-input IDS path choice). Discovery /
                // analysis often already streamed assistant tokens before NEEDS_INPUT — blank /
                // internal-status prompts stay silent so we do not glue enum jargon to LLM text.
                String prompt = PipelineChatWaitView.forChatWait(waiting.prompt());
                if (prompt.isBlank()) {
                  return Multi.createFrom().empty();
                }
                return Multi.createFrom().item(ChatEvent.token(prompt));
              }
              if (signal instanceof PipelineSignal.WaitingForApproval waiting) {
                return Multi.createFrom().item(approvalDecision(conversationId, waiting));
              }
              if (signal instanceof PipelineSignal.WaitingForImplement) {
                // Chat auto-continues into implement; the materialization Message is the user-facing
                // summary. Do not show an intermediate "Creating the chain..." banner.
                return Multi.createFrom().empty();
              }
              if (signal instanceof PipelineSignal.Failed failed) {
                // Do not prefix StageOutcomeClass enum names into chat errors.
                String detail =
                    failed.message() == null || failed.message().isBlank()
                        ? "Something went wrong."
                        : failed.message();
                return Multi.createFrom().item(ChatEvent.error(detail));
              }
              if (signal instanceof PipelineSignal.Completed) {
                // CHAIN_MATERIALIZED: summary already streamed as PipelineSignal.Message.
                // Do not leak RunStatus enum names (CHAIN_MATERIALIZED, PLAN_APPROVED, …) into chat.
                return Multi.createFrom().empty();
              }
              return Multi.createFrom().empty();
            });
  }

  /**
   * Turns an approval wait into the decision card the reader answers.
   *
   * <p>Emitted even when the wait carries no prompt: a gate the reader cannot see is worse than a
   * card with a short question. Durable question text arrives with the pending-decision query.
   */
  private ChatEvent approvalDecision(
      String conversationId, PipelineSignal.WaitingForApproval waiting) {
    long revision =
        runStore
            .loadByConversation(conversationId)
            .map(doc -> doc.run().runRevision())
            .orElse(0L);
    String hash = waiting.candidate().contentHash();
    String question = durableQuestion(conversationId, hash, waiting.prompt());
    PendingAction pending =
        new CreateChainPendingAction.Approve(
            CreateChainPublicArtifactTypes.toApprovalType(waiting.candidate().kind()),
            hash,
            revision,
            question);
    return ChatEvent.decision(pending, revision, "");
  }

  /**
   * Question for this gate: the fresh prompt when the run just stopped, the stored one when it was
   * resumed. A resumed wait carries no prompt, and a blank card tells the reader nothing.
   */
  private String durableQuestion(String conversationId, String artifactHash, String prompt) {
    String question = PipelineChatWaitView.forChatWait(prompt).strip();
    if (approvalQuestions == null) {
      return question;
    }
    if (question.isBlank()) {
      return approvalQuestions.find(conversationId, artifactHash).orElse("");
    }
    approvalQuestions.save(conversationId, artifactHash, question);
    return question;
  }

  private String languageReference(ProductPipelineRunDocument doc) {
    if (doc == null) {
      return "";
    }
    // Prefer durable transition reason from the last approval wait when present; otherwise empty
    // so ApprovalPrompts uses its English fallback after LLM failure.
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_APPROVAL)
        .reduce((a, b) -> b)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .filter(reason -> !reason.isBlank() && !reason.equals("approved"))
        .orElse("");
  }

  private String chainMaterializedChatSummary(String runId) {
    Optional<ChainCatalogFacts> facts = runtime.latestCatalogChainSnapshot(runId);
    if (facts.isPresent()) {
      return ChainMaterializedSummary.format(facts.get());
    }
    return "Chain is ready.";
  }

  private String resolveImplementHash(String runId, String text) {
    Matcher implement = IMPLEMENT_COMMAND.matcher(text == null ? "" : text.trim());
    if (implement.matches()) {
      return implement.group(1);
    }
    if (isImplementShortcut(text)) {
      return runtime.approvedPlanContentHash(runId).filter(h -> !h.isBlank()).orElse(null);
    }
    return null;
  }

  private static boolean isImplementShortcut(String text) {
    if (text == null) {
      return false;
    }
    String normalized = text.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("implement")
        || normalized.equals("agree")
        || normalized.equals("approve")
        || normalized.equals("approved")
        || normalized.equals("yes");
  }

  /**
   * Decides whether a reply to an approval question accepts the candidate.
   *
   * <p>The literal comparison below stays first and settles the common case without a model call.
   * It cannot settle the rest: the question is authored in the language of the conversation, so a
   * reply arrives in that language, and an agent relaying a person's approval writes a sentence
   * rather than the bare word. Both read as "not an approval" to a literal check, and the stage is
   * then re-run with the approval as its input — the loop this method exists to end.
   *
   * <p>A model failure means not approved. Advancing a stage on a classification that did not
   * happen is the one outcome worth ruling out.
   */
  private boolean isApproval(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    if (isLiteralApproval(text)) {
      LOG.debugf("Approval accepted by literal token");
      return true;
    }
    if (approvalIntentAgent == null) {
      return false;
    }
    try {
      String verdict = approvalIntentAgent.classifyApproval(text);
      String normalized = verdict == null ? "" : verdict.trim().toUpperCase(Locale.ROOT);
      boolean approved = normalized.startsWith("APPROVED");
      // Logged because the decision is not readable from the code the way the literal check is.
      LOG.infof("Approval intent verdict=%s approved=%s replyChars=%d", normalized, approved, text.length());
      return approved;
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Approval intent classification failed; treating the reply as not an approval");
      return false;
    }
  }

  private static boolean isLiteralApproval(String text) {
    String normalized = text.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("agree")
        || normalized.equals("approve")
        || normalized.equals("approved")
        || normalized.equals("yes");
  }
}

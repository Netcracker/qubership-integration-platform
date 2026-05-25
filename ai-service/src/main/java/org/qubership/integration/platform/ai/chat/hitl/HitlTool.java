package org.qubership.integration.platform.ai.chat.hitl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ImplementGateCoordinator;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * LangChain4j tool that enables the AI to request human confirmation at any point during a
 * conversation.
 *
 * <p>When the LLM calls this tool, a HITL checkpoint is emitted to the SSE stream via {@link
 * HitlStreamRegistry}, and the tool blocks until the user responds (or the configured timeout
 * expires). The user's answer is returned to the LLM as the tool result, allowing it to continue
 * accordingly.
 *
 * <p>This tool does <strong>not</strong> take {@code @MemoryId}: some LangChain4j runtimes pass an
 * unrelated UUID (e.g. a catalog {@code chainId}) into tool {@code @MemoryId}, which can attach
 * tool results to the wrong chat memory and make the model repeat {@code requestConfirmation} in a
 * loop. The conversation key is always {@link ChatMdc#CONVERSATION_ID} from MDC (set on the routed
 * chat stream).
 */
@ApplicationScoped
public class HitlTool {

  private static final Logger LOG = Logger.getLogger(HitlTool.class);

  /**
   * After the user agrees to create a chain, the model may call this tool again with the same
   * question because tool results were not merged into the visible transcript; suppress duplicate
   * HITL for the same prompt briefly.
   */
  private static final long CHAIN_CREATE_HITL_DEDUP_MS = 90_000L;

  private static final Pattern CHAIN_CREATE_HITL_QUESTION =
      Pattern.compile(
          "(?ius)create\\s+(the\\s+)?new\\s+chain|\\bcreateChain\\b|proceeding\\s+to\\s+create");

  private static final Pattern AFFIRMATIVE_ANSWER =
      Pattern.compile("(?ius)\\b(agree|yes|yep|yeah|ok|confirm|confirmed|proceed|\\+)\\b");

  private final ConcurrentHashMap<String, Long> chainCreateHitlRecentAgree =
      new ConcurrentHashMap<>();

  @Inject HitlService hitlService;

  @Inject HitlStreamRegistry registry;

  @Inject ActiveChainPlanService activeChainPlanService;

  @Inject ConversationPlanningDiaryService planningDiaryService;

  @Tool(
      "Request human confirmation or ask the user a clarifying question. In CREATE_CHAIN_PLAN:"
          + " during Phase A (service discovery), use requestConfirmation only for binding choices"
          + " (e.g. which catalog system when several match, unresolved operation,"
          + " user_accepted_unbound). Questions must not imply catalog execution or a finished plan"
          + " before a complete fenced ChainImplementationPlan JSON was emitted in your assistant"
          + " reply and captured server-side (no execute/implement plan, no"
          + " proceed-with-creating-chain, no createChain, no Yes/No-only \"execute plan\""
          + " prompts). After the fenced plan is in your assistant reply, call requestConfirmation"
          + " ONCE with options \"Agree,Modify plan\" for plan approval — never replace this with"
          + " a manual \"Next Steps\" / Agree-Modify-plan prose block. In other roles use ONCE after"
          + " presenting a"
          + " plan and BEFORE the first destructive catalog tool (createChain, createElement,"
          + " transferElements, updateElement, createConnection, deleteElement). After the user"
          + " agrees to execute that plan, do NOT call this tool again for routine patch retries,"
          + " server-side merge/validation, or ordinary updateElement calls while executing the"
          + " approved plan. Call again only when user input is missing, choices are ambiguous"
          + " (multiple valid schema branches), the plan meaningfully changes, or the tool result"
          + " says repair budget exhausted / asks for HITL. Returns the user's response text.")
  public String requestConfirmation(
      @P(
              "Clear question for the user describing what you are about to do or what you need"
                  + " clarified")
          String question,
      @P(
              "Comma-separated suggested options for the user, e.g. 'Agree,Cancel' or 'Option"
                  + " A,Option B,Option C'")
          String options) {

    String conversationId = resolveConversationIdFromMdc();
    if (conversationId.isBlank()) {
      LOG.warn("HITL requestConfirmation: missing conversationId on MDC — cannot run HITL");
      return "Error: missing chat session context for confirmation. Stop and ask the user to retry"
                 + " the message.";
    }

    List<String> optionList = parseOptions(options);

    boolean hasSubstantivePlan =
        activeChainPlanService
            .getActive(conversationId)
            .map(
                s ->
                    s.plan() != null
                        && s.plan().getElements() != null
                        && !s.plan().getElements().isEmpty())
            .orElse(false);
    boolean planApproved = activeChainPlanService.isApproved(conversationId);
    String planningGuardReject =
        HitlPlanningPhaseGuard.rejectOrNull(question, hasSubstantivePlan, planApproved);
    if (planningGuardReject != null) {
      LOG.warnf(
          "HITL requestConfirmation blocked by planning phase guard: conversationId=%s,"
              + " questionPreview=%s",
          conversationId,
          AiTraceLog.previewOneLine(question, AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));
      return planningGuardReject;
    }

    if (isChainCreateConfirmationQuestion(question)) {
      String dedupKey = chainCreateDedupKey(conversationId, question);
      Long lastAgree = chainCreateHitlRecentAgree.get(dedupKey);
      if (lastAgree != null
          && System.currentTimeMillis() - lastAgree < CHAIN_CREATE_HITL_DEDUP_MS) {
        LOG.infof(
            "HITL requestConfirmation: short-circuit duplicate chain-create confirmation"
                + " (conversationId=%s, dedupKeyHash=%08x)",
            conversationId, dedupKey.hashCode());
        if (!planApproved) {
          return "Agree — confirmation was already received for this step. Continue in"
                     + " CREATE_CHAIN_PLAN: emit a complete fenced ChainImplementationPlan JSON in"
                     + " your assistant reply if activePlan is not captured yet, then call"
                     + " requestConfirmation with options \"Agree,Modify plan\" only. Do not call"
                     + " createChain from this role.";
        }
        return "Agree — confirmation was already received for this chain-creation step in this"
                   + " session. Proceed immediately with createChain (or the next catalog tool); do"
                   + " not call requestConfirmation again for the same operation.";
      }
    }

    LOG.infof(
        "HITL tool requestConfirmation: conversationId=%s, questionPreview=%s, options=%s",
        conversationId,
        AiTraceLog.previewOneLine(question, AiTraceLog.DEFAULT_USER_PREVIEW_CHARS),
        optionList);

    HitlCheckpoint checkpoint = hitlService.createCheckpoint(conversationId, question, optionList);
    String checkpointJson = hitlService.serializeCheckpoint(checkpoint);

    if (!registry.emit(conversationId, checkpointJson)) {
      LOG.warnf(
          "Failed to emit HITL checkpoint for conversation %s — no emitter registered",
          conversationId);
      return "Error: could not reach the user interface. Proceed with your best judgment.";
    }

    planningDiaryService.recordHitlCheckpointOpened(
        conversationId, checkpoint.getCheckpointId(), question, optionList);

    try {
      HitlCheckpointAnswer answer = hitlService.awaitAnswer(checkpoint.getCheckpointId());
      LOG.infof(
          "HITL tool completed: conversationId=%s, answerPreview=%s",
          conversationId,
          AiTraceLog.preview(answer.getAnswer(), AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));
      String answerText = answer.getAnswer();
      planningDiaryService.recordHitlCheckpointResolved(
          conversationId, checkpoint.getCheckpointId(), answerText);
      if (optionsSuggestPlanApproval(optionList) && isAffirmativeAnswer(answerText)) {
        activeChainPlanService.applyHitlAgreeOptionChosen(conversationId);
      }
      if (ImplementGateCoordinator.optionsSuggestImplementGate(optionList)
          && ImplementGateCoordinator.isStartImplementationAnswer(answerText)) {
        activeChainPlanService.acknowledgeImplementGate(conversationId);
      }
      if (isChainCreateConfirmationQuestion(question) && isAffirmativeAnswer(answerText)) {
        chainCreateHitlRecentAgree.put(
            chainCreateDedupKey(conversationId, question), System.currentTimeMillis());
      }
      return answerText;
    } catch (TimeoutException e) {
      LOG.warnf(
          "HITL timeout for conversation %s, checkpoint %s",
          conversationId, checkpoint.getCheckpointId());
      return "The user did not respond in time. Proceed with your best judgment or stop.";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "Request was interrupted. Stop the current operation.";
    }
  }

  private static String resolveConversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }

  private static boolean isChainCreateConfirmationQuestion(String question) {
    return question != null && CHAIN_CREATE_HITL_QUESTION.matcher(question).find();
  }

  private static boolean isAffirmativeAnswer(String answer) {
    return answer != null && AFFIRMATIVE_ANSWER.matcher(answer.trim()).find();
  }

  private static String chainCreateDedupKey(String conversationId, String question) {
    String q = question == null ? "" : question.trim().replaceAll("\\s+", " ");
    return conversationId + "|" + q;
  }

  /**
   * True when this checkpoint is the Phase C plan approval (Agree + Modify plan), not Phase A
   * binding picks.
   */
  static boolean optionsSuggestPlanApproval(List<String> optionList) {
    boolean hasAgree = false;
    boolean hasModifyPlan = false;
    for (String o : optionList) {
      if (o == null) {
        continue;
      }
      String t = o.trim();
      if ("agree".equalsIgnoreCase(t)) {
        hasAgree = true;
      }
      if (t.toLowerCase(Locale.ROOT).startsWith("modify plan")) {
        hasModifyPlan = true;
      }
    }
    return hasAgree && hasModifyPlan;
  }

  private List<String> parseOptions(String options) {
    if (options == null || options.isBlank()) {
      return List.of();
    }
    return Arrays.stream(options.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }
}

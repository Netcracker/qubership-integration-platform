package org.qubership.integration.platform.ai.chat.chainplan;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.hitl.HitlCheckpoint;
import org.qubership.integration.platform.ai.chat.hitl.HitlCheckpointAnswer;
import org.qubership.integration.platform.ai.chat.hitl.HitlService;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Gate 2: blocking server HITL after plan approval, before routing to {@link
 * ScenarioType#IMPLEMENT_CHAIN}.
 */
@ApplicationScoped
public class ImplementGateCoordinator {

  private static final Logger LOG = Logger.getLogger(ImplementGateCoordinator.class);

  public static final String GATE_QUESTION =
      "The chain implementation plan is approved. Start catalog implementation of this chain now?";

  public static final String OPTION_START = "Yes, start implementation";

  public static final String OPTION_MODIFY = "Modify plan";

  public static final List<String> GATE_OPTIONS = List.of(OPTION_START, OPTION_MODIFY);

  public static final String IMPLEMENT_USER_MESSAGE =
      "Implement the approved ChainImplementationPlan in the catalog now.";

  private final HitlService hitlService;
  private final HitlStreamRegistry hitlStreamRegistry;
  private final ActiveChainPlanService activeChainPlanService;
  private final ConversationPlanningDiaryService planningDiaryService;
  private final ScenarioRouter scenarioRouter;

  @Inject
  public ImplementGateCoordinator(
      HitlService hitlService,
      HitlStreamRegistry hitlStreamRegistry,
      ActiveChainPlanService activeChainPlanService,
      ConversationPlanningDiaryService planningDiaryService,
      ScenarioRouter scenarioRouter) {
    this.hitlService = hitlService;
    this.hitlStreamRegistry = hitlStreamRegistry;
    this.activeChainPlanService = activeChainPlanService;
    this.planningDiaryService = planningDiaryService;
    this.scenarioRouter = scenarioRouter;
  }

  /** Runs Gate 2 HITL on a worker thread, then continues with {@link ScenarioRouter#route}. */
  public Multi<String> runGateThenRoute(ChatRequest request, String conversationId) {
    return Multi.createFrom()
        .<String>emitter(
            outer -> {
              hitlStreamRegistry.register(conversationId, outer);
              Infrastructure.getDefaultExecutor()
                  .execute(
                      () -> {
                        try {
                          ChatRequest next = runBlockingGate(request, conversationId);
                          hitlStreamRegistry.unregister(conversationId);
                          scenarioRouter
                              .route(next, conversationId)
                              .subscribe()
                              .with(
                                  outer::emit,
                                  err -> {
                                    hitlStreamRegistry.unregister(conversationId);
                                    outer.fail(err);
                                  },
                                  () -> {
                                    hitlStreamRegistry.unregister(conversationId);
                                    outer.complete();
                                  });
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          hitlStreamRegistry.unregister(conversationId);
                          outer.fail(e);
                        } catch (TimeoutException e) {
                          hitlStreamRegistry.unregister(conversationId);
                          LOG.warnf(
                              "Implement gate HITL timed out — continuing without IMPLEMENT_CHAIN:"
                                  + " conversationId=%s",
                              conversationId);
                          activeChainPlanService.clearImplementGatePending(conversationId);
                          scenarioRouter
                              .route(request, conversationId)
                              .subscribe()
                              .with(
                                  outer::emit,
                                  err -> {
                                    hitlStreamRegistry.unregister(conversationId);
                                    outer.fail(err);
                                  },
                                  () -> {
                                    hitlStreamRegistry.unregister(conversationId);
                                    outer.complete();
                                  });
                        } catch (Exception e) {
                          hitlStreamRegistry.unregister(conversationId);
                          outer.fail(e);
                        }
                      });
            });
  }

  private ChatRequest runBlockingGate(ChatRequest request, String conversationId)
      throws TimeoutException, InterruptedException {
    LOG.infof(
        "Implement gate HITL starting: conversationId=%s, planId=%s",
        conversationId,
        activeChainPlanService.getActive(conversationId).map(s -> s.planId()).orElse("(none)"));

    HitlCheckpoint checkpoint =
        hitlService.createCheckpoint(conversationId, GATE_QUESTION, GATE_OPTIONS);
    String checkpointJson = hitlService.serializeCheckpoint(checkpoint);
    if (!hitlStreamRegistry.emit(conversationId, checkpointJson)) {
      LOG.warnf("Implement gate HITL emit failed (no emitter): conversationId=%s", conversationId);
      return request;
    }

    planningDiaryService.recordHitlCheckpointOpened(
        conversationId, checkpoint.getCheckpointId(), GATE_QUESTION, GATE_OPTIONS);

    HitlCheckpointAnswer answer = hitlService.awaitAnswer(checkpoint.getCheckpointId());
    String answerText = answer.getAnswer();
    planningDiaryService.recordHitlCheckpointResolved(
        conversationId, checkpoint.getCheckpointId(), answerText);

    LOG.infof(
        "Implement gate HITL completed: conversationId=%s, answerPreview=%s",
        conversationId, AiTraceLog.preview(answerText, AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));

    if (isStartImplementationAnswer(answerText)) {
      if (activeChainPlanService.acknowledgeImplementGate(conversationId)) {
        request.setScenarioHint(ScenarioType.IMPLEMENT_CHAIN);
        request.setMessage(IMPLEMENT_USER_MESSAGE);
        request.setResolvedEffectiveUserText(IMPLEMENT_USER_MESSAGE);
      } else {
        LOG.warnf(
            "Implement gate start blocked (open plan debt): conversationId=%s", conversationId);
      }
      return request;
    }
    if (isModifyPlanAnswer(answerText)) {
      activeChainPlanService.clearImplementGatePending(conversationId);
      return request;
    }

    LOG.warnf(
        "Implement gate HITL: unrecognized answer, treating as modify: conversationId=%s",
        conversationId);
    activeChainPlanService.clearImplementGatePending(conversationId);
    return request;
  }

  public static boolean optionsSuggestImplementGate(List<String> optionList) {
    if (optionList == null || optionList.size() < 2) {
      return false;
    }
    boolean hasStart = false;
    boolean hasModify = false;
    for (String o : optionList) {
      if (o == null) {
        continue;
      }
      String t = o.trim();
      if (OPTION_START.equalsIgnoreCase(t)
          || t.toLowerCase(Locale.ROOT).contains("start implementation")) {
        hasStart = true;
      }
      if (OPTION_MODIFY.equalsIgnoreCase(t)
          || UserIntentPatterns.matchesImplementGateModifyAnswer(t)) {
        hasModify = true;
      }
    }
    return hasStart && hasModify;
  }

  public static boolean isStartImplementationAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    String t = answer.trim();
    if (OPTION_START.equalsIgnoreCase(t)) {
      return true;
    }
    return UserIntentPatterns.matchesImplementGateAffirmative(t);
  }

  public static boolean isModifyPlanAnswer(String answer) {
    return UserIntentPatterns.matchesImplementGateModifyAnswer(answer);
  }
}

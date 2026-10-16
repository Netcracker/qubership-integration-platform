package org.qubership.integration.platform.ai.llm.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.AnswerShape;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.DeployOp;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.InfoNeed;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.TurnReferent;

/** Names one open-chain action and the catalog evidence required to answer it. */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface OpenChainTurnPlanner {

  @SystemMessage(fromResource = "prompts/roles/open-chain-turn-planner.md")
  @UserMessage(
      """
      Last assistant turn:
      {lastAssistantTurn}

      Recent conversation (oldest first):
      {recentConversation}

      Latest user message:
      {message}
      """)
  Capture plan(String lastAssistantTurn, String recentConversation, String message);

  /** Flat structured output from the model. Java converts it to a valid plan variant. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Capture(
      @Description("ASK, PATCH, or DEPLOY. ASK never mutates the chain.") Kind kind,
      @Description("LAST_TURN for a follow-up about the assistant reply; otherwise OPEN_CHAIN.")
          TurnReferent referent,
      @Description("Catalog reads needed for ASK. Use FACTS, SNAPSHOTS, or DEPLOYMENTS.")
          List<InfoNeed> needs,
      @Description("Mutation for DEPLOY. Use NONE for ASK and PATCH.") DeployOp deployOp,
      @Description("Requested answer format. Use EXPLAIN unless the user names another format.")
          AnswerShape answerShape) {

    public Capture {
      kind = kind == null ? Kind.ASK : kind;
      referent = referent == null ? TurnReferent.OPEN_CHAIN : referent;
      needs = needs == null ? List.of() : needs.stream().filter(Objects::nonNull).toList();
      deployOp = deployOp == null ? DeployOp.NONE : deployOp;
      answerShape = answerShape == null ? AnswerShape.EXPLAIN : answerShape;
    }
  }

  enum Kind {
    ASK,
    PATCH,
    DEPLOY
  }

  /** Converts model output to a fail-closed plan. Invalid mutations become read-only questions. */
  static OpenChainTurnPlan validate(Capture capture) {
    if (capture == null) {
      return new OpenChainTurnPlan.Ask(
          TurnReferent.OPEN_CHAIN, Set.of(InfoNeed.FACTS), AnswerShape.EXPLAIN);
    }
    return switch (capture.kind()) {
      case ASK ->
          new OpenChainTurnPlan.Ask(
              capture.referent(), Set.copyOf(capture.needs()), capture.answerShape());
      case PATCH -> new OpenChainTurnPlan.Patch();
      case DEPLOY ->
          capture.deployOp() == DeployOp.NONE
              ? new OpenChainTurnPlan.Ask(
                  capture.referent(), Set.copyOf(capture.needs()), capture.answerShape())
              : new OpenChainTurnPlan.Deploy(capture.deployOp());
    };
  }
}

package org.qubership.integration.platform.ai.a2a;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCard;
import org.qubership.integration.platform.ai.a2a.protocol.A2aAgentCardFactory;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;

/**
 * Serves the Agent Card on both well-known paths with Netcracker-compatible wire fields.
 *
 * <p>The A2A SDK already exposes {@code agent-card.json}, but its {@link AgentCard} record has no
 * top-level {@code protocolVersion}, and clients that match the Invoice Report / WeatherForcaster
 * cards expect one alongside {@code url}, {@code preferredTransport}, and {@code
 * additionalInterfaces}. {@code agent.json} is the path DCA requests: {@code
 * Agent.get_card_by_url} appends it and never retries with {@code agent-card.json}. Both paths
 * return the same body. This route runs after {@link A2aDisabledRouteFilter} and before the SDK
 * handler so the discovery document matches that shape.
 */
@ApplicationScoped
public class A2aAgentCardDiscoveryRoute {

  private final Instance<AgentCard> agentCard;

  @Inject
  public A2aAgentCardDiscoveryRoute(@PublicAgentCard Instance<AgentCard> agentCard) {
    this.agentCard = agentCard;
  }

  void setupRoutes(@Observes Router router) {
    if (!agentCard.isResolvable()) {
      return;
    }
    for (String path :
        List.of(A2aProtocolConstants.AGENT_CARD_PATH, A2aProtocolConstants.AGENT_JSON_PATH)) {
      router
          .get(path)
          .order(-1500)
          .produces(APPLICATION_JSON)
          .handler(
              ctx ->
                  ctx.response()
                      .setStatusCode(200)
                      .putHeader("Content-Type", "application/json")
                      .end(A2aAgentCardFactory.toDiscoveryJson(agentCard.get())));
    }
  }
}

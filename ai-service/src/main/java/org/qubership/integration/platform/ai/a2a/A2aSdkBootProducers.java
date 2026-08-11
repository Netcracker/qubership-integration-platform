package org.qubership.integration.platform.ai.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.compat03.spec.AgentCard_v0_3;
import org.a2aproject.sdk.spec.AgentCard;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aAgentCardFactory;
import org.qubership.integration.platform.ai.a2a.protocol.A2aLegacyAgentCardFactory;
import org.qubership.integration.platform.ai.a2a.transport.A2aDispatchCrashGate;
import org.qubership.integration.platform.ai.a2a.transport.A2aTaskSnapshotPersister;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aAgentExecutor;
import org.qubership.integration.platform.ai.a2a.transport.QipA2aAgentExecutor;
import org.qubership.integration.platform.ai.a2a.transport.QipAssistA2aAgentExecutor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;

/**
 * CDI producers for the A2A REST Agent Card and create-chain {@link AgentExecutor}.
 *
 * <p>Beans stay packaged so Helm can flip {@code qip.ai.a2a.enabled} at runtime. HTTP access is
 * gated by {@link A2aDisabledRouteFilter} when the flag is off.
 */
@ApplicationScoped
public class A2aSdkBootProducers {

  @Inject CreateChainApplicationFacade facade;
  @Inject A2aTaskSnapshotPersister persister;
  @Inject A2aMessageReceiptRepository receiptRepository;
  @Inject CallerContextProvider callerContextProvider;
  @Inject TaskAccessPolicy accessPolicy;
  @Inject A2aFeatureGate featureGate;
  @Inject A2aDispatchCrashGate crashGate;
  @Inject org.qubership.integration.platform.ai.a2a.transport.DispatchLeaseHeartbeat leaseHeartbeat;
  @Inject AppConfig appConfig;
  @Inject ScenarioRouter scenarioRouter;
  @Inject ConversationService conversationService;

  @ConfigProperty(name = "quarkus.http.port", defaultValue = "3001")
  int httpPort;

  @Produces
  @PublicAgentCard
  public AgentCard agentCard() {
    return A2aAgentCardFactory.createChainMvpCard(resolvePublicBaseUrl());
  }

  /** Same card in the 0.3 shape, required by the compatibility JSON-RPC handler. */
  @Produces
  @PublicAgentCard
  public AgentCard_v0_3 legacyAgentCard() {
    return A2aLegacyAgentCardFactory.fromCurrent(agentCard());
  }

  @Produces
  @ApplicationScoped
  public AgentExecutor agentExecutor() {
    return new QipA2aAgentExecutor(
        new CreateChainA2aAgentExecutor(
            facade,
            persister,
            receiptRepository,
            callerContextProvider,
            accessPolicy,
            featureGate,
            crashGate,
            leaseHeartbeat),
        new QipAssistA2aAgentExecutor(
            scenarioRouter,
            conversationService,
            callerContextProvider,
            accessPolicy,
            featureGate,
            appConfig.a2a().assistTurnBudget()));
  }

  String resolvePublicBaseUrl() {
    String configured = appConfig.a2a().publicBaseUrl();
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    return "http://localhost:" + httpPort;
  }
}

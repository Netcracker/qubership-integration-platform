package org.qubership.integration.platform.ai.a2a.protocol;

import java.util.List;
import org.a2aproject.sdk.compat03.spec.AgentCapabilities_v0_3;
import org.a2aproject.sdk.compat03.spec.AgentCard_v0_3;
import org.a2aproject.sdk.compat03.spec.AgentInterface_v0_3;
import org.a2aproject.sdk.compat03.spec.AgentSkill_v0_3;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;

/**
 * Mirrors the Agent Card into the 0.3 shape the compatibility JSON-RPC handler requires.
 *
 * <p>The 0.3 handler is what serves {@code message/send}, and it injects a card of its own type.
 * This card is not published: discovery always serves the 1.0 document from {@link
 * A2aAgentCardFactory}. It exists so the handler has the agent identity and skills it reports on
 * its own card methods, derived from the same source rather than written twice.
 */
public final class A2aLegacyAgentCardFactory {

  /** Wire protocolVersion the compatibility dialect advertises. */
  public static final String LEGACY_PROTOCOL_VERSION = "0.3";

  private A2aLegacyAgentCardFactory() {}

  public static AgentCard_v0_3 fromCurrent(AgentCard card) {
    return new AgentCard_v0_3.Builder()
        .name(card.name())
        .description(card.description())
        .version(card.version())
        .url(card.url())
        .protocolVersion(LEGACY_PROTOCOL_VERSION)
        .preferredTransport(card.preferredTransport())
        .additionalInterfaces(interfaces(card))
        .capabilities(
            new AgentCapabilities_v0_3.Builder()
                .streaming(card.capabilities().streaming())
                .pushNotifications(card.capabilities().pushNotifications())
                .build())
        .defaultInputModes(card.defaultInputModes())
        .defaultOutputModes(card.defaultOutputModes())
        .skills(card.skills().stream().map(A2aLegacyAgentCardFactory::skill).toList())
        .supportsAuthenticatedExtendedCard(false)
        .build();
  }

  private static List<AgentInterface_v0_3> interfaces(AgentCard card) {
    return card.supportedInterfaces().stream()
        .map(A2aLegacyAgentCardFactory::agentInterface)
        .toList();
  }

  private static AgentInterface_v0_3 agentInterface(AgentInterface source) {
    return new AgentInterface_v0_3(source.protocolBinding(), source.url());
  }

  private static AgentSkill_v0_3 skill(AgentSkill source) {
    return new AgentSkill_v0_3.Builder()
        .id(source.id())
        .name(source.name())
        .description(source.description())
        .tags(source.tags())
        .inputModes(source.inputModes())
        .outputModes(source.outputModes())
        .build();
  }
}

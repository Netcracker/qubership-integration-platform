package org.qubership.integration.platform.ai.a2a.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface;

/**
 * Builds the Agent Card for the A2A surface.
 *
 * <p>Two transports are advertised. JSON-RPC comes first and is the preferred one because clients
 * built on the Python {@code a2a-sdk} negotiate transports before their first HTTP call, and a
 * REST-only card makes them fail with {@code no compatible transports found}. REST stays on the
 * card for the ADK lab and the A2A Inspector.
 */
public final class A2aAgentCardFactory {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private A2aAgentCardFactory() {}

  public static AgentCard createChainMvpCard(String publicBaseUrl) {
    String base = trimTrailingSlash(publicBaseUrl);
    String jsonRpcUrl = base + A2aProtocolConstants.JSONRPC_PATH;
    return AgentCard.builder()
        .name(A2aProtocolConstants.AGENT_NAME)
        .description("Answer integration questions and build chains over A2A.")
        .version("0.5.0-SNAPSHOT")
        .supportedInterfaces(
            List.of(
                new AgentInterface(
                    A2aProtocolConstants.JSONRPC_PROTOCOL_BINDING,
                    jsonRpcUrl,
                    null,
                    A2aProtocolConstants.PROTOCOL_VERSION),
                new AgentInterface(
                    A2aProtocolConstants.REST_PROTOCOL_BINDING,
                    base,
                    null,
                    A2aProtocolConstants.PROTOCOL_VERSION)))
        // Clients that predate supportedInterfaces read url + preferredTransport instead.
        .url(jsonRpcUrl)
        .preferredTransport(A2aProtocolConstants.JSONRPC_PROTOCOL_BINDING)
        .additionalInterfaces(
            List.of(
                new Legacy_0_3_AgentInterface(
                    A2aProtocolConstants.JSONRPC_PROTOCOL_BINDING, jsonRpcUrl),
                new Legacy_0_3_AgentInterface(A2aProtocolConstants.REST_PROTOCOL_BINDING, base)))
        .capabilities(
            AgentCapabilities.builder()
                .streaming(true)
                .pushNotifications(false)
                .extensions(List.of(exactApprovalExtension()))
                .build())
        .defaultInputModes(
            List.of(A2aProtocolConstants.TEXT_MODE, A2aProtocolConstants.STRUCTURED_DATA_MODE))
        .defaultOutputModes(
            List.of(A2aProtocolConstants.TEXT_MODE, A2aProtocolConstants.STRUCTURED_DATA_MODE))
        .skills(List.of(assistSkill(), createChainSkill()))
        .build();
  }

  /**
   * Renders the card for the discovery endpoints.
   *
   * <p>The SDK record carries {@code protocolVersion} per interface but not at the top level, and
   * peers that match the Netcracker card shape read it there. Adding it here keeps the SDK type
   * unforked.
   */
  public static String toDiscoveryJson(AgentCard card) {
    try {
      ObjectNode json = (ObjectNode) MAPPER.readTree(JsonUtil.toJson(card));
      json.put("protocolVersion", A2aProtocolConstants.PROTOCOL_VERSION);
      return MAPPER.writeValueAsString(json);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to render the Agent Card discovery document", e);
    }
  }

  private static AgentSkill assistSkill() {
    return AgentSkill.builder()
        .id(A2aProtocolConstants.ASSIST_SKILL_ID)
        .name("Ask QIP")
        .description(
            "Answer a question about integration chains, plans, and the platform. Returns text in"
                + " one round trip.")
        .tags(List.of("question", "assist", "conversation"))
        .inputModes(List.of(A2aProtocolConstants.TEXT_MODE))
        .outputModes(List.of(A2aProtocolConstants.TEXT_MODE))
        .build();
  }

  private static AgentSkill createChainSkill() {
    return AgentSkill.builder()
        .id(A2aProtocolConstants.CREATE_CHAIN_SKILL_ID)
        .name("Create chain")
        .description("Build one integration chain from requirements or a provided IDS.")
        .tags(List.of("create-chain", "pipeline"))
        .inputModes(
            List.of(A2aProtocolConstants.TEXT_MODE, A2aProtocolConstants.STRUCTURED_DATA_MODE))
        .outputModes(
            List.of(A2aProtocolConstants.TEXT_MODE, A2aProtocolConstants.STRUCTURED_DATA_MODE))
        .build();
  }

  /**
   * Declares structured approval as optional rather than enforcing it silently.
   *
   * <p>{@code required} stays false so a client that cannot build data parts still reaches a
   * terminal state through the text approval token.
   */
  private static AgentExtension exactApprovalExtension() {
    return AgentExtension.builder()
        .uri(A2aProtocolConstants.EXACT_APPROVAL_EXTENSION_URI)
        .required(false)
        .description(
            "Approve with a data part carrying action, artifactType, and artifactHash. Without this"
                + " extension, approval is the token printed in the input-required status message.")
        .build();
  }

  private static String trimTrailingSlash(String url) {
    String trimmed = url == null ? "" : url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}

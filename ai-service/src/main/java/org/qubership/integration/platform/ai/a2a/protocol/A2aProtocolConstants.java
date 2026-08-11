package org.qubership.integration.platform.ai.a2a.protocol;

import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;

/**
 * Wire-level A2A 1.0.1 constants shared by later transport prompts.
 * Keep A2A SDK types inside {@code ai.a2a} packages only.
 */
public final class A2aProtocolConstants {

  /** Wire protocolVersion for A2A 1.0.x (including 1.0.1). */
  public static final String PROTOCOL_VERSION = AgentInterface.CURRENT_PROTOCOL_VERSION;

  public static final String REST_PROTOCOL_BINDING = TransportProtocol.HTTP_JSON.asString();

  public static final String JSONRPC_PROTOCOL_BINDING = TransportProtocol.JSONRPC.asString();

  /**
   * JSON-RPC path advertised as the Agent Card {@code url}.
   *
   * <p>The SDK registers JSON-RPC at {@code POST /}. Netcracker Agentic AI Platform and DCA
   * advertise {@code /rpc}, so {@link
   * org.qubership.integration.platform.ai.a2a.A2aJsonRpcPathAlias} mirrors the handler there.
   */
  public static final String JSONRPC_PATH = "/rpc";

  /** Discovery path the A2A 1.0 spec and the SDK use. */
  public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

  /**
   * Discovery path DCA requests.
   *
   * <p>{@code Agent.get_card_by_url} appends {@code agent.json} and never retries with {@code
   * agent-card.json}, so both paths must serve the same body.
   */
  public static final String AGENT_JSON_PATH = "/.well-known/agent.json";

  public static final String TEXT_MODE = "text/plain";

  public static final String STRUCTURED_DATA_MODE = "application/json";

  public static final String CREATE_CHAIN_SKILL_ID = "create-chain@2";

  /**
   * Text-in / text-out skill for peers that expect one round trip and a plain answer.
   *
   * <p>Backed by the same scenario router the chat surface uses, so a new scenario becomes
   * reachable over A2A without touching the transport.
   */
  public static final String ASSIST_SKILL_ID = "qip-assist@1";

  /** Message metadata key a caller may set to pick a skill explicitly. */
  public static final String SKILL_ID_METADATA_KEY = "skillId";

  public static final String AGENT_NAME = "QIP AI Service";

  /**
   * Opts a client into structured-only approval.
   *
   * <p>A2A defines no approval primitive, so the requirement is negotiated instead of assumed. A
   * client that activates this extension must approve with a data part carrying the artifact hash.
   * A client that does not activate it approves by replying with the token printed in the status
   * message, which is all a client can send when its resume path builds text parts only.
   */
  public static final String EXACT_APPROVAL_EXTENSION_URI =
      "https://qubership.org/a2a/extensions/exact-approval/v1";

  /** Length of the {@code artifactHash} prefix that the text approval echoes back. */
  public static final int APPROVAL_TOKEN_LENGTH = 8;

  private A2aProtocolConstants() {}
}

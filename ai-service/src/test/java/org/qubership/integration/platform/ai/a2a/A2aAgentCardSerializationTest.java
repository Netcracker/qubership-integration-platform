package org.qubership.integration.platform.ai.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aAgentCardFactory;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;

/**
 * Slice 2: Agent Card serialization for the A2A 1.0.1 MVP surface.
 */
class A2aAgentCardSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void serializesCardWithBothBindingsAndRequiredModes() throws Exception {
    AgentCard card = A2aAgentCardFactory.createChainMvpCard("http://localhost:3001");
    JsonNode json = objectMapper.readTree(JsonUtil.toJson(card));

    assertEquals(A2aProtocolConstants.AGENT_NAME, json.path("name").asText());

    JsonNode capabilities = json.path("capabilities");
    assertTrue(capabilities.path("streaming").asBoolean());
    assertFalse(capabilities.path("pushNotifications").asBoolean());

    JsonNode interfaces = json.path("supportedInterfaces");
    assertTrue(interfaces.isArray());
    assertEquals(2, interfaces.size());
    assertEquals(
        A2aProtocolConstants.JSONRPC_PROTOCOL_BINDING,
        interfaces.get(0).path("protocolBinding").asText());
    assertEquals("1.0", interfaces.get(0).path("protocolVersion").asText());
    assertEquals("1.0", A2aProtocolConstants.PROTOCOL_VERSION);
    assertEquals("http://localhost:3001/rpc", interfaces.get(0).path("url").asText());
    assertEquals(
        A2aProtocolConstants.REST_PROTOCOL_BINDING,
        interfaces.get(1).path("protocolBinding").asText());
    assertEquals("http://localhost:3001", interfaces.get(1).path("url").asText());
    assertEquals("http://localhost:3001/rpc", json.path("url").asText());
    assertEquals(
        A2aProtocolConstants.JSONRPC_PROTOCOL_BINDING, json.path("preferredTransport").asText());

    List<String> inputModes =
        objectMapper.convertValue(
            json.path("defaultInputModes"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    List<String> outputModes =
        objectMapper.convertValue(
            json.path("defaultOutputModes"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    assertTrue(inputModes.contains(A2aProtocolConstants.TEXT_MODE));
    assertTrue(inputModes.contains(A2aProtocolConstants.STRUCTURED_DATA_MODE));
    assertTrue(outputModes.contains(A2aProtocolConstants.TEXT_MODE));
    assertTrue(outputModes.contains(A2aProtocolConstants.STRUCTURED_DATA_MODE));

    JsonNode skills = json.path("skills");
    assertEquals(2, skills.size());
    assertEquals(A2aProtocolConstants.ASSIST_SKILL_ID, skills.get(0).path("id").asText());
    assertEquals(A2aProtocolConstants.CREATE_CHAIN_SKILL_ID, skills.get(1).path("id").asText());
    List<String> skillInputModes =
        objectMapper.convertValue(
            skills.get(1).path("inputModes"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    assertTrue(
        skillInputModes.containsAll(
            List.of(A2aProtocolConstants.TEXT_MODE, A2aProtocolConstants.STRUCTURED_DATA_MODE)));
  }

  /** Peers that match the Netcracker card shape read protocolVersion at the top level. */
  @Test
  void discoveryJsonCarriesTopLevelProtocolVersion() throws Exception {
    AgentCard card = A2aAgentCardFactory.createChainMvpCard("http://localhost:3001/");
    JsonNode json = objectMapper.readTree(A2aAgentCardFactory.toDiscoveryJson(card));

    assertEquals(
        A2aProtocolConstants.PROTOCOL_VERSION, json.path("protocolVersion").asText());
    assertEquals("http://localhost:3001/rpc", json.path("url").asText());
  }
}

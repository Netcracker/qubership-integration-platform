package org.qubership.integration.platform.ai.a2a;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;

/**
 * Compatibility gate for A2A on Quarkus 3.33.3. Slice 1 proves dependency convergence and boot.
 */
@QuarkusTest
class A2aSdkCompatibilityTest {

  private static final String EXPECTED_QUARKUS_VERSION = "3.33.3";

  @Inject
  @PublicAgentCard
  Instance<AgentCard> agentCard;

  @Test
  void quarkusPlatformRemainsAt3333AndServiceBoots() {
    assertEquals(LaunchMode.TEST, LaunchMode.current(), "ai-service must boot in test mode");
    assertEquals(
        EXPECTED_QUARKUS_VERSION,
        LaunchMode.class.getPackage().getImplementationVersion(),
        "Quarkus must stay pinned at 3.33.3");
  }

  @Test
  void a2aProtocolBoundaryTypesAreAvailable() throws ClassNotFoundException {
    Class.forName("org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants");
    Class.forName("org.qubership.integration.platform.ai.a2a.protocol.A2aAgentCardFactory");
    Class.forName("org.qubership.integration.platform.ai.a2a.protocol.A2aStreamingEventSupport");

    Package protocolPackage =
        Class.forName("org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants")
            .getPackage();
    assertNotNull(protocolPackage);
    assertTrue(protocolPackage.getName().startsWith("org.qubership.integration.platform.ai.a2a"));
    assertFalse(protocolPackage.getName().contains("productpipeline"));
  }

  @Test
  void sdkAgentCardDiscoveryBootsOnBothBindings() {
    AgentCard card = agentCard.get();
    assertEquals(A2aProtocolConstants.AGENT_NAME, card.name());
    assertTrue(card.capabilities().streaming());
    assertFalse(card.capabilities().pushNotifications());
    assertEquals(2, card.supportedInterfaces().size());
    // JSON-RPC leads: a client that negotiates transports before its first call treats the first
    // entry as preferred, and a JSON-RPC-only client finds no match on a REST-only card.
    assertEquals(
        TransportProtocol.JSONRPC.asString(),
        card.supportedInterfaces().get(0).protocolBinding());
    assertEquals(
        TransportProtocol.HTTP_JSON.asString(),
        card.supportedInterfaces().get(1).protocolBinding());
    assertEquals(TransportProtocol.JSONRPC.asString(), card.preferredTransport());
    assertEquals("1.0", card.supportedInterfaces().get(0).protocolVersion());
    assertEquals("1.0", A2aProtocolConstants.PROTOCOL_VERSION);

    given()
        .when()
        .get("/.well-known/agent-card.json")
        .then()
        .statusCode(200)
        .body("name", equalTo(A2aProtocolConstants.AGENT_NAME))
        .body("capabilities.streaming", equalTo(true))
        .body("capabilities.pushNotifications", equalTo(false))
        .body("skills.id", hasItem(A2aProtocolConstants.CREATE_CHAIN_SKILL_ID))
        .body("skills.id", hasItem(A2aProtocolConstants.ASSIST_SKILL_ID))
        .body("supportedInterfaces[0].protocolBinding", equalTo("JSONRPC"))
        .body("supportedInterfaces[0].protocolVersion", equalTo("1.0"));
  }
}

package org.qubership.integration.platform.ai.a2a;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;

/**
 * HTTP Agent Card contract: discovery paths, advertised transports, and skills.
 */
@QuarkusTest
class A2aAgentCardContractIT {

  @Test
  void agentCardAdvertisesBothTransportsAndBothSkills() {
    given()
        .when()
        .get(A2aProtocolConstants.AGENT_CARD_PATH)
        .then()
        .statusCode(200)
        .body("name", equalTo(A2aProtocolConstants.AGENT_NAME))
        .body("protocolVersion", equalTo(A2aProtocolConstants.PROTOCOL_VERSION))
        .body("capabilities.streaming", equalTo(true))
        .body("capabilities.pushNotifications", equalTo(false))
        .body("supportedInterfaces", hasSize(2))
        .body("supportedInterfaces[0].protocolBinding", equalTo("JSONRPC"))
        .body("supportedInterfaces[0].protocolVersion", equalTo("1.0"))
        .body("supportedInterfaces[0].url", equalTo("http://a2a.test.local/rpc"))
        .body("supportedInterfaces[1].protocolBinding", equalTo("HTTP+JSON"))
        .body("supportedInterfaces[1].url", equalTo("http://a2a.test.local"))
        .body("preferredTransport", equalTo("JSONRPC"))
        .body("url", equalTo("http://a2a.test.local/rpc"))
        .body("defaultInputModes", hasItem(A2aProtocolConstants.TEXT_MODE))
        .body("defaultInputModes", hasItem(A2aProtocolConstants.STRUCTURED_DATA_MODE))
        .body("defaultOutputModes", hasItem(A2aProtocolConstants.TEXT_MODE))
        .body("defaultOutputModes", hasItem(A2aProtocolConstants.STRUCTURED_DATA_MODE))
        .body("skills", hasSize(2))
        .body("skills.id", hasItem(A2aProtocolConstants.ASSIST_SKILL_ID))
        .body("skills.id", hasItem(A2aProtocolConstants.CREATE_CHAIN_SKILL_ID));
  }

  /**
   * A client that appends {@code agent.json} never retries with {@code agent-card.json}, so the
   * alias is the difference between discovery working and failing outright.
   */
  @Test
  void agentJsonAliasServesTheSameCard() {
    String canonical =
        given().when().get(A2aProtocolConstants.AGENT_CARD_PATH).then().statusCode(200)
            .extract()
            .asString();
    String alias =
        given().when().get(A2aProtocolConstants.AGENT_JSON_PATH).then().statusCode(200)
            .extract()
            .asString();
    org.junit.jupiter.api.Assertions.assertEquals(canonical, alias);
  }
}

package org.qubership.integration.platform.ai.integration.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogPatchPreparationService;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogChainTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogConnectionTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogElementTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;
import org.slf4j.MDC;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests: verifies outbound HTTP from catalog {@code @Tool} beans against WireMock (no LLM,
 * no real catalog).
 */
@QuarkusTest
@QuarkusTestResource(WireMockCatalogResource.class)
class CatalogApiToolsContractTest {

  private static final String ONE_TRIGGER_PLAN_ASSISTANT =
      """
      ```json
      {
        "chain": { "name": "ContractBatch", "description": "" },
        "elements": [
          { "clientId": "t1", "type": "http-trigger" }
        ],
        "connections": []
      }
      ```
      """;

  @Inject CatalogChainTools catalogChainTools;

  @Inject CatalogElementTools catalogElementTools;

  @Inject CatalogConnectionTools catalogConnectionTools;

  @Inject CatalogSystemTools catalogSystemTools;

  @Inject CatalogPatchPreparationService catalogPatchPreparationService;

  @Inject ActiveChainPlanService activeChainPlanService;

  @Inject ObjectMapper objectMapper;

  @Inject ConversationCatalogCache conversationCatalogCache;

  private static final String CONTRACT_CONVERSATION_ID = "catalog-contract-test";

  private String t(String toolOut) {
    return CatalogToolResultTestSupport.combinedText(objectMapper, toolOut);
  }

  private static final String COND10_FLAT_ELEMENTS =
      "[{\"id\":\"http-tr-1\",\"type\":\"http-trigger\",\"parentElementId\":null,\"properties\":{}},"
          + "{\"id\":\"cond-1\",\"type\":\"condition\",\"parentElementId\":null,\"properties\":{}},"
          + "{\"id\":\"if-even-1\",\"type\":\"if\",\"parentElementId\":\"cond-1\",\"properties\":{}},"
          + "{\"id\":\"if-zero-1\",\"type\":\"if\",\"parentElementId\":\"cond-1\",\"properties\":{}},"
          + "{\"id\":\"else-1\",\"type\":\"else\",\"parentElementId\":\"cond-1\",\"properties\":{}},"
          + "{\"id\":\"scr-g\",\"type\":\"script\",\"parentElementId\":\"if-even-1\",\"properties\":{}},"
          + "{\"id\":\"scr-c\",\"type\":\"script\",\"parentElementId\":\"if-zero-1\",\"properties\":{}},"
          + "{\"id\":\"scr-h\",\"type\":\"script\",\"parentElementId\":\"else-1\",\"properties\":{}}"
          + "]";

  @BeforeEach
  void resetWireMock() {
    com.github.tomakehurst.wiremock.client.WireMock.configureFor(
        "localhost", WireMockCatalogResource.SERVER.port());
    WireMockCatalogResource.SERVER.resetAll();
    conversationCatalogCache.clearConversation(CONTRACT_CONVERSATION_ID);
    MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID,
        CONTRACT_CONVERSATION_ID);
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void createChainPostsExpectedJson() {
    stubFor(
        post(urlEqualTo("/v1/chains"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain.json"))));

    String result = catalogChainTools.createChain("CN", "CD");

    assertTrue(t(result).contains("chain-1"));
    verify(
        postRequestedFor(urlEqualTo("/v1/chains"))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/create-chain.json"),
                    true,
                    true)));
  }

  @Test
  void getChainUsesGetWithChainId() {
    stubFor(
        get(urlEqualTo("/v1/chains/c99"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-get.json"))));

    String out = catalogChainTools.getChain("c99");

    assertTrue(t(out).contains("c99"));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c99")));
  }

  @Test
  void createElementPostsToElementsPath() {
    stubFor(
        post(urlEqualTo("/v1/chains/c1/elements"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-created-element.json"))));

    String out = catalogElementTools.createElement("c1", "http-trigger", "", "");

    assertTrue(t(out).contains("e1"));
    verify(
        postRequestedFor(urlEqualTo("/v1/chains/c1/elements"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/create-element.json"),
                    true,
                    true)));
  }

  @Test
  void createElementReusesExistingElseUnderConditionWithoutPost() {
    stubFor(
        get(urlEqualTo("/v1/chains/reb-c1/elements/cond-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-condition-with-else.json"))));

    String out = catalogElementTools.createElement("reb-c1", "else", "cond-1", "");

    assertTrue(t(out).contains("else-1"), out);
    verify(0, postRequestedFor(urlEqualTo("/v1/chains/reb-c1/elements")));
    verify(getRequestedFor(urlEqualTo("/v1/chains/reb-c1/elements/cond-1")));
  }

  @Test
  void updateElementUsesPatch() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-log-record-partial.json"))));
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String out = catalogElementTools.updateElement("c1", "e1", "{\"logLevel\":\"Info\"}");

    assertTrue(t(out).contains("e1"));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
    verify(
        patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/patch-element-log-record-info.json"),
                    true,
                    true)));
  }

  @Test
  void updateElementMergesPartialPropertiesWithCatalog() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-log-record-partial.json"))));
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String patchJson = "{\"properties\":{\"logLevel\":\"Warning\"}}";
    String out = catalogElementTools.updateElement("c1", "e1", patchJson);

    assertTrue(t(out).contains("e1"));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
    verify(
        patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/patch-element-log-record-merged.json"),
                    true,
                    true)));
  }

  @Test
  void updateElementPreservesParentElementIdWhenPatchingProperties() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "e1",
                          "name": "Log Record",
                          "type": "log-record",
                          "parentElementId": "parent-1",
                          "swimlaneId": "swimlane-1",
                          "properties": {
                            "logLevel": "Error",
                            "message": "base-msg"
                          },
                          "mandatoryChecksPassed": true
                        }
                        """)));
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String out =
        catalogElementTools.updateElement(
            "c1", "e1", "{\"properties\":{\"logLevel\":\"Warning\"}}");

    assertTrue(t(out).contains("e1"));
    verify(
        patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1"))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "parentElementId": "parent-1",
                      "swimlaneId": "swimlane-1",
                      "properties": {
                        "logLevel": "Warning",
                        "message": "base-msg"
                      }
                    }
                    """,
                    true,
                    true)));
  }

  @Test
  void updateElementAcceptsSwimlaneAndMandatoryChecksTopLevel() {
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String out =
        catalogElementTools.updateElement(
            "c1", "e1", "{\"swimlaneId\":\"swimlane-2\",\"mandatoryChecksPassed\":true}");

    assertTrue(t(out).contains("e1"));
    verify(0, getRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
    verify(
        patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1"))
            .withRequestBody(
                equalToJson(
                    "{\"swimlaneId\":\"swimlane-2\",\"mandatoryChecksPassed\":true}", true, true)));
  }

  @Test
  void patchPreparationNameOnlySkipsCatalogFetch() {
    CatalogPatchPreparationService.PreparedUpdateBody prepared =
        catalogPatchPreparationService.prepareUpdateElementBody(
            "c1", "e1", "{\"name\":\"Renamed\"}");
    assertFalse(prepared.mergedWithCatalog());
    assertFalse(prepared.schemaValidated());
    verify(0, getRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
  }

  @Test
  void patchPreparationHttpTriggerWithContextPathPassesDescriptorValidation() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-http-trigger-empty.json"))));
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String patchJson =
        "{\"properties\":{\"contextPath\":\"/api/hello\",\"httpMethodRestrict\":\"POST\",\"accessControlType\":\"NONE\"}}";
    String out = catalogElementTools.updateElement("c1", "e1", patchJson);

    assertTrue(t(out).contains("e1"));
    verify(patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
  }

  @Test
  void updateElementNameOnlyDoesNotFetchElement() {
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String out = catalogElementTools.updateElement("c1", "e1", "{\"name\":\"Renamed\"}");

    assertTrue(t(out).contains("e1"));
    verify(0, getRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
    verify(
        patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1"))
            .withRequestBody(equalToJson("{\"name\":\"Renamed\"}", true, true)));
  }

  @Test
  void updateElementRetriesOnceAfterCatalogHttp400() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-log-record-partial.json"))));

    String errBody = "{\"serviceName\":\"Catalog\",\"errorMessage\":\"bad request\"}";
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .inScenario("updRetry")
            .whenScenarioStateIs(STARTED)
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errBody))
            .willSetStateTo("second"));

    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .inScenario("updRetry")
            .whenScenarioStateIs("second")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-updated-element.json"))));

    String out = catalogElementTools.updateElement("c1", "e1", "{\"logLevel\":\"Warning\"}");

    assertTrue(t(out).contains("e1"));
    verify(2, patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
  }

  @Test
  void updateElementExhaustedRepairsReturnsHitlHint() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/e1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-log-record-partial.json"))));

    String errBody = "{\"serviceName\":\"Catalog\",\"errorMessage\":\"still bad\"}";
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .inScenario("updEx")
            .whenScenarioStateIs(STARTED)
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errBody))
            .willSetStateTo("one"));
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .inScenario("updEx")
            .whenScenarioStateIs("one")
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errBody))
            .willSetStateTo("two"));
    stubFor(
        patch(urlEqualTo("/v1/chains/c1/elements/e1"))
            .inScenario("updEx")
            .whenScenarioStateIs("two")
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errBody)));

    String out = catalogElementTools.updateElement("c1", "e1", "{\"logLevel\":\"Info\"}");

    assertTrue(t(out).contains("400"));
    assertTrue(t(out).contains("Repair budget exhausted"));
    assertTrue(t(out).contains("requestConfirmation"));
    verify(3, patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
  }

  @Test
  void deleteElementUsesDelete() {
    stubFor(
        delete(urlEqualTo("/v1/chains/c1/elements/e1")).willReturn(aResponse().withStatus(204)));

    catalogElementTools.deleteElement("c1", "e1");

    verify(deleteRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
  }

  @Test
  void createElementIfOrElseWithoutParentDoesNotCallCatalog() {
    String outIf = catalogElementTools.createElement("c1", "if", "", "");
    assertTrue(t(outIf).contains("must be created under a parent"), outIf);
    String outElse = catalogElementTools.createElement("c1", "else", "", "");
    assertTrue(t(outElse).contains("must be created under a parent"), outElse);
    verify(0, postRequestedFor(urlPathMatching("/v1/chains/.+/elements")));
  }

  @Test
  void createElementTry2WithoutParentDoesNotCallCatalog() {
    String out = catalogElementTools.createElement("c1", "try-2", "", "");
    assertTrue(t(out).contains("must be created under a parent"), out);
    assertTrue(t(out).contains("try-catch-finally-2"), out);
    verify(0, postRequestedFor(urlPathMatching("/v1/chains/.+/elements")));
  }

  @Test
  void createElementHttpTriggerUnderConditionContainerDoesNotCallCreate() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/cond-root"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-condition-only.json"))));

    String out = catalogElementTools.createElement("c1", "http-trigger", "cond-root", "");

    assertTrue(t(out).contains("inbound trigger"), out);
    verify(0, postRequestedFor(urlEqualTo("/v1/chains/c1/elements")));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c1/elements/cond-root")));
  }

  @Test
  void transferElementsInboundTriggerUnderConditionDoesNotCallTransfer() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/cond-root"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-condition-only.json"))));
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/trig-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get-http-trigger-empty.json"))));

    String out = catalogElementTools.transferElements("c1", "cond-root", "[\"trig-1\"]", "");

    assertTrue(t(out).contains("inbound trigger"), out);
    assertTrue(t(out).contains("How to fix"), out);
    assertTrue(t(out).contains("createConnection"), out);
    verify(0, postRequestedFor(urlEqualTo("/v1/chains/c1/elements/transfer")));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c1/elements/cond-root")));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c1/elements/trig-1")));
  }

  @Test
  void transferElementsScriptUnderConditionRejectedByAllowedChildren() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/cond-root"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": "cond-root",
                          "type": "condition",
                          "children": [
                            {"id": "if-shell-1", "type": "if"},
                            {"id": "else-shell-1", "type": "else"}
                          ]
                        }
                        """)));
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/script-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"script-1\",\"type\":\"script\",\"mandatoryChecksPassed\":true}")));

    String out = catalogElementTools.transferElements("c1", "cond-root", "[\"script-1\"]", "");

    assertTrue(t(out).contains("only allows children"), out);
    assertTrue(t(out).contains("How to fix"), out);
    assertTrue(t(out).contains("if=if-shell-1"), out);
    assertTrue(t(out).contains("else=else-shell-1"), out);
    verify(0, postRequestedFor(urlEqualTo("/v1/chains/c1/elements/transfer")));
  }

  @Test
  void transferElementsPostsTransferBody() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/4793a18c-3c74-4e07-a30b-a723e1c4b4c1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"4793a18c-3c74-4e07-a30b-a723e1c4b4c1\",\"type\":\"if\",\"mandatoryChecksPassed\":true}")));
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/12568fbd-a3e1-43cc-bc0f-30a91ff9bc3e"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"12568fbd-a3e1-43cc-bc0f-30a91ff9bc3e\",\"type\":\"script\",\"mandatoryChecksPassed\":true}")));
    stubFor(
        post(urlEqualTo("/v1/chains/c1/elements/transfer"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-transfer.json"))));

    String idsJson = "[\"12568fbd-a3e1-43cc-bc0f-30a91ff9bc3e\"]";
    String out =
        catalogElementTools.transferElements(
            "c1", "4793a18c-3c74-4e07-a30b-a723e1c4b4c1", idsJson, "");

    assertTrue(t(out).contains("12568fbd-a3e1-43cc-bc0f-30a91ff9bc3e"));
    verify(
        postRequestedFor(urlEqualTo("/v1/chains/c1/elements/transfer"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/transfer-elements.json"),
                    true,
                    true)));
  }

  @Test
  void createConnectionPostsDependenciesWithFromTo() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/a"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"a\",\"type\":\"http-trigger\",\"mandatoryChecksPassed\":true}")));
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/b"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"b\",\"type\":\"condition\",\"mandatoryChecksPassed\":true}")));
    stubFor(
        post(urlEqualTo("/v1/chains/c1/dependencies"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/chain-diff-created-dependency.json"))));

    String out = catalogConnectionTools.createConnection("c1", "a", "b");

    assertTrue(t(out).contains("d1"));
    verify(
        postRequestedFor(urlEqualTo("/v1/chains/c1/dependencies"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/create-dependency.json"),
                    true,
                    true)));
  }

  @Test
  void createConnectionConditionToIfDoesNotCallCatalogPost() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/cond"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"cond\",\"type\":\"condition\",\"mandatoryChecksPassed\":true}")));
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/if-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"if-1\",\"type\":\"if\",\"mandatoryChecksPassed\":true}")));

    String out = catalogConnectionTools.createConnection("c1", "cond", "if-1");

    assertTrue(t(out).contains("inputEnabled=false"), out);
    verify(0, postRequestedFor(urlEqualTo("/v1/chains/c1/dependencies")));
  }

  @Test
  void createConnectionIfToScriptDoesNotCallCatalogPost() {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/if-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"if-1\",\"type\":\"if\",\"mandatoryChecksPassed\":true}")));
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/script-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"script-1\",\"type\":\"script\",\"mandatoryChecksPassed\":true}")));

    String out = catalogConnectionTools.createConnection("c1", "if-1", "script-1");

    assertTrue(t(out).contains("outputEnabled=false"), out);
    verify(0, postRequestedFor(urlEqualTo("/v1/chains/c1/dependencies")));
  }

  @Test
  void createSystemPostsNameAndType() {
    stubFor(
        post(urlEqualTo("/v1/systems"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/system.json"))));

    String out = catalogSystemTools.createSystem("Sys", "EXTERNAL");

    assertTrue(t(out).contains("s1"));
    verify(
        postRequestedFor(urlEqualTo("/v1/systems"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/create-system.json"),
                    true,
                    true)));
  }

  @Test
  void getApiSpecificationsUsesModelsWithSystemIdQuery() {
    stubFor(
        get(urlPathEqualTo("/v1/models"))
            .withQueryParam("systemId", equalTo("sys-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/models.json"))));

    String out = catalogSystemTools.getApiSpecifications("sys-1");

    assertTrue(t(out).contains("m1"));
    verify(
        getRequestedFor(urlPathEqualTo("/v1/models")).withQueryParam("systemId", equalTo("sys-1")));
  }

  @Test
  void listCatalogOperationsUsesOperationsWithModelIdPagination() {
    String modelId = "m1-swagger-1.0";
    stubFor(
        get(urlPathEqualTo("/v1/operations"))
            .withQueryParam("modelId", equalTo(modelId))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("count", equalTo("500"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/operations.json"))));

    String out = catalogSystemTools.listCatalogOperations(modelId, null, null);

    assertTrue(t(out).contains("op1"));
    verify(
        getRequestedFor(urlPathEqualTo("/v1/operations"))
            .withQueryParam("modelId", equalTo(modelId))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("count", equalTo("500")));
  }

  @Test
  void listCatalogOperationsFiltersInMemoryWithoutCatalogSearchFilterParam() {
    String modelId = "m2-swagger-1.0";
    stubFor(
        get(urlPathEqualTo("/v1/operations"))
            .withQueryParam("modelId", equalTo(modelId))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("count", equalTo("500"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/operations.json"))));

    String out = catalogSystemTools.listCatalogOperations(modelId, null, "op1");

    assertTrue(t(out).contains("op1"));
    verify(
        getRequestedFor(urlPathEqualTo("/v1/operations"))
            .withQueryParam("modelId", equalTo(modelId))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("count", equalTo("500")));
    verify(
        0,
        getRequestedFor(urlPathEqualTo("/v1/operations"))
            .withQueryParam("searchFilter", equalTo("op1")));
  }

  @Test
  void searchCatalogSystemsPostsSearchCondition() {
    stubFor(
        post(urlEqualTo("/v1/systems/search"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/systems-search.json"))));

    String out = catalogSystemTools.searchCatalogSystems("Quote");

    assertTrue(t(out).contains("sys-1"));
    verify(
        postRequestedFor(urlEqualTo("/v1/systems/search"))
            .withRequestBody(
                equalToJson(
                    CatalogContractFixtures.resourceUtf8(
                        "catalog-contract/requests/search-systems.json"),
                    true,
                    true)));
  }

  @Test
  void catalogSystemToolsReadEndpointsWorkWithMdc() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID,
        "catalog-contract-lookup");
    String modelId = "m9-swagger-2.0";
    stubFor(
        post(urlEqualTo("/v1/systems/search"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
    stubFor(
        get(urlPathEqualTo("/v1/models"))
            .withQueryParam("systemId", equalTo("s1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
    stubFor(
        get(urlPathEqualTo("/v1/operations"))
            .withQueryParam("modelId", equalTo(modelId))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("count", equalTo("500"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));

    assertFalse(CatalogToolResult.isError(objectMapper, catalogSystemTools.searchCatalogSystems("q")));
    assertFalse(CatalogToolResult.isError(objectMapper, catalogSystemTools.getApiSpecifications("s1")));
    assertFalse(
        CatalogToolResult.isError(
            objectMapper, catalogSystemTools.listCatalogOperations(modelId, null, "x")));
    verify(
        getRequestedFor(urlPathEqualTo("/v1/operations"))
            .withQueryParam("modelId", equalTo(modelId))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("count", equalTo("500")));
  }

  @Test
  void updateElementInvalidJsonDoesNotCallCatalog() throws Exception {
    String out = catalogElementTools.updateElement("c1", "e1", "not-json");
    var err = CatalogToolResultTestSupport.requireError(objectMapper, out);
    assertEquals("updateElement", err.get("tool").asText());
    assertEquals("INVALID_ARGUMENT", err.get("error").get("code").asText());
    verify(0, patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/e1")));
  }

  @Test
  void updateElementSchemaValidationFailureAppendsRequestConfirmationHint() throws Exception {
    stubFor(
        get(urlEqualTo("/v1/chains/c1/elements/if-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"if-1\",\"type\":\"if\",\"parentElementId\":\"cond-1\","
                            + "\"properties\":{\"priority\":0},\"mandatoryChecksPassed\":true}")));

    String out =
        catalogElementTools.updateElement("c1", "if-1", "{\"properties\":{\"priority\":1}}");

    var err = CatalogToolResultTestSupport.requireError(objectMapper, out);
    assertEquals("CATALOG_SCHEMA_VALIDATION_ERROR", err.get("error").get("code").asText(), out);
    assertTrue(err.get("error").get("message").asText().contains("Schema validation failed"), out);
    assertTrue(err.get("error").get("hint").asText().contains("requestConfirmation"), out);
    verify(0, patchRequestedFor(urlEqualTo("/v1/chains/c1/elements/if-1")));
  }

  @Test
  void getElementsUsesListPath() {
    stubFor(
        get(urlEqualTo("/v1/chains/c2/elements"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/elements-list.json"))));

    String out = catalogElementTools.getElements("c2");

    assertTrue(t(out).contains("e1"));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c2/elements")));
  }

  @Test
  void getElementUsesElementPath() {
    stubFor(
        get(urlEqualTo("/v1/chains/c2/elements/e9"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/element-get.json"))));

    String out = catalogElementTools.getElement("c2", "e9");

    assertTrue(t(out).contains("e1"));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c2/elements/e9")));
  }

  @Test
  void getDependenciesUsesDependenciesPath() {
    stubFor(
        get(urlEqualTo("/v1/chains/c2/dependencies"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        CatalogContractFixtures.resourceUtf8(
                            "catalog-contract/responses/dependencies-list.json"))));

    String out = catalogConnectionTools.getDependencies("c2");

    assertTrue(t(out).contains("d1"));
    verify(getRequestedFor(urlEqualTo("/v1/chains/c2/dependencies")));
  }

  @Test
  void comparePlanToCatalogStubDoesNotCallCatalog() {
    String plan =
        "{\"elements\":[{\"clientId\":\"t1\",\"elementId\":\"e1\",\"type\":\"http-trigger\","
            + "\"displayName\":\"HTTP Trigger\",\"expectedProperties\":{\"httpMethod\":\"POST\"}}],"
            + "\"connections\":[]}";
    String out = catalogConnectionTools.comparePlanToCatalogStub("c3", plan, "{}");

    assertTrue(t(out).contains("\"ok\":false") || out.contains("\"ok\" : false"), out);
    assertTrue(t(out).contains("disabled") || out.contains("stub"), out);
    verify(0, getRequestedFor(urlEqualTo("/v1/chains/c3/elements")));
    verify(0, getRequestedFor(urlEqualTo("/v1/chains/c3/dependencies")));
    verify(0, getRequestedFor(urlEqualTo("/v1/chains/c3")));
  }

  @Test
  void createElementsByJsonStubReturnsEmptyMapWithoutCatalogCalls() throws Exception {
    String out = catalogElementTools.createElementsByJson("c-stub", "{}");

    var root = CatalogToolResultTestSupport.requireSuccess(objectMapper, out);
    assertEquals("createElementsByJson", root.get("tool").asText());
    assertTrue(root.get("data").isObject());
    assertTrue(root.get("data").isEmpty());
    verify(0, postRequestedFor(urlMatching("/v1/chains/.*/elements")));
  }

  @Test
  void createElementsByJsonFromActivePlanCreatesRootElementOnce() {
    String conv = UUID.randomUUID().toString();
    MDC.put(ChatMdc.CONVERSATION_ID, conv);
    activeChainPlanService.captureFromAssistantText(conv, ONE_TRIGGER_PLAN_ASSISTANT);

    String chainId = "c-batch-one";
    stubListDependencies(chainId, "[]");
    stubListElements(chainId, "[]");
    stubFor(
        post(urlEqualTo("/v1/chains/" + chainId + "/elements"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"e-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    String out = catalogElementTools.createElementsByJson(chainId, "{}");
    assertTrue(t(out).contains("t1") && out.contains("e-1"), out);
    verify(1, postRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/elements")));
  }

  @Test
  void createElementsByJsonSecondCallFromActiveIsIdempotentNoExtraPosts() {
    String conv = UUID.randomUUID().toString();
    MDC.put(ChatMdc.CONVERSATION_ID, conv);
    activeChainPlanService.captureFromAssistantText(conv, ONE_TRIGGER_PLAN_ASSISTANT);

    String chainId = "c-batch-idem";
    stubListDependencies(chainId, "[]");
    stubListElements(chainId, "[]");
    stubFor(
        post(urlEqualTo("/v1/chains/" + chainId + "/elements"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"e-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    String out1 = catalogElementTools.createElementsByJson(chainId, "{}");
    assertTrue(t(out1).contains("e-1"), out1);
    String out2 = catalogElementTools.createElementsByJson(chainId, "{}");
    assertTrue(t(out2).contains("e-1"), out2);
    verify(1, postRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/elements")));
  }

  @Test
  void createElementsByJsonBindsReusableElseFromParentReadBack() {
    String chainId = "c-batch-condition";
    String plan =
        """
        {
          "elements": [
            { "clientId": "t1", "type": "http-trigger" },
            {
              "clientId": "cond1",
              "type": "condition",
              "children": [
                { "clientId": "else1", "type": "else" }
              ]
            }
          ],
          "connections": []
        }
        """;

    stubListDependencies(chainId, "[]");
    stubListElements(chainId, "[]");

    stubFor(
        post(urlEqualTo("/v1/chains/" + chainId + "/elements"))
            .inScenario("condition-materialize")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("trigger-created")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo("/v1/chains/" + chainId + "/elements"))
            .inScenario("condition-materialize")
            .whenScenarioStateIs("trigger-created")
            .willSetStateTo("condition-created")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        get(urlEqualTo("/v1/chains/" + chainId + "/elements/cond-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{},\"children\":["
                            + "{\"id\":\"else-1\",\"type\":\"else\",\"properties\":{},\"children\":[]}]"
                            + "}")));

    String out = catalogElementTools.createElementsByJson(chainId, plan);

    assertTrue(t(out).contains("\"cond1\":\"cond-1\""), out);
    assertTrue(t(out).contains("\"else1\":\"else-1\""), out);
    verify(2, postRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/elements")));
    verify(getRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/elements/cond-1")));
  }

  /**
   * Catalog seeds one {@code if} and one {@code else} under {@code condition} (read-back). The
   * first plan {@code if} binds to the seeded shell; the second {@code if} is created via POST;
   * {@code else} binds from read-back. When the condition create diff lists only the container (not
   * auto shells), expect six POSTs to {@code /elements} (trigger, condition, second {@code if},
   * three scripts).
   */
  @Test
  void createElementsByJsonConditionTwoIfElseWithScriptsUnderBranches() throws Exception {
    String chainId = "c-cond10-nested";
    String plan =
        """
{
  "chain": { "name": "Condition test 10", "description": "Chain to return greetings based on the current minute" },
  "elements": [
    {
      "clientId": "http-trigger-1",
      "type": "http-trigger",
      "expectedProperties": { "contextPath": "/condition-test", "httpMethodRestrict": "GET" }
    },
    {
      "clientId": "condition-1",
      "type": "condition",
      "expectedProperties": {},
      "children": [
        {
          "clientId": "if-even",
          "type": "if",
          "expectedProperties": { "condition": "currentMinute % 2 == 0", "priority": 0 },
          "children": [
            {
              "clientId": "script-greetings",
              "type": "script",
              "expectedProperties": { "script": "return 'Greetings!';" }
            }
          ]
        },
        {
          "clientId": "if-zero",
          "type": "if",
          "expectedProperties": { "condition": "currentMinute == 0", "priority": 1 },
          "children": [
            {
              "clientId": "script-congratulations",
              "type": "script",
              "expectedProperties": { "script": "return 'Congratulations!';" }
            }
          ]
        },
        {
          "clientId": "else-default",
          "type": "else",
          "expectedProperties": {},
          "children": [
            {
              "clientId": "script-hello",
              "type": "script",
              "expectedProperties": { "script": "return 'Hello!';" }
            }
          ]
        }
      ]
    }
  ],
  "connections": [
    { "fromClientId": "http-trigger-1", "toClientId": "condition-1" }
  ]
}
""";

    String postPath = "/v1/chains/" + chainId + "/elements";
    stubListDependencies(chainId, "[]");
    stubListElements(chainId, COND10_FLAT_ELEMENTS);
    stubPostDependencyOk(chainId);
    stubPatchElementsOk(chainId);
    stubCond10ElementGets(chainId);

    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("cond10nested")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("after-trigger")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"http-tr-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("cond10nested")
            .whenScenarioStateIs("after-trigger")
            .willSetStateTo("after-condition")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("cond10nested")
            .whenScenarioStateIs("after-condition")
            .willSetStateTo("after-if-zero")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"if-zero-1\",\"type\":\"if\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("cond10nested")
            .whenScenarioStateIs("after-if-zero")
            .willSetStateTo("after-script-greetings")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"scr-g\",\"type\":\"script\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("cond10nested")
            .whenScenarioStateIs("after-script-greetings")
            .willSetStateTo("after-script-congrats")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"scr-c\",\"type\":\"script\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("cond10nested")
            .whenScenarioStateIs("after-script-congrats")
            .willSetStateTo("done")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"scr-h\",\"type\":\"script\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    stubFor(
        get(urlEqualTo("/v1/chains/" + chainId + "/elements/cond-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{},\"children\":["
                            + "{\"id\":\"if-even-1\",\"type\":\"if\",\"properties\":{},\"children\":[]},"
                            + "{\"id\":\"else-1\",\"type\":\"else\",\"properties\":{},\"children\":[]}"
                            + "]}")));

    String out = catalogElementTools.createElementsByJson(chainId, plan);

    assertTrue(t(out).contains("\"http-trigger-1\":\"http-tr-1\""), out);
    assertTrue(t(out).contains("\"condition-1\":\"cond-1\""), out);
    assertTrue(t(out).contains("\"if-even\":\"if-even-1\""), out);
    assertTrue(t(out).contains("\"if-zero\":\"if-zero-1\""), out);
    assertTrue(t(out).contains("\"else-default\":\"else-1\""), out);
    assertTrue(t(out).contains("\"script-greetings\":\"scr-g\""), out);
    assertTrue(t(out).contains("\"script-congratulations\":\"scr-c\""), out);
    assertTrue(t(out).contains("\"script-hello\":\"scr-h\""), out);
    verify(6, postRequestedFor(urlEqualTo(postPath)));
    JsonNode report = CatalogToolResultTestSupport.materializeReportData(objectMapper, out);
    assertTrue(report.path("stages").path("skeleton").path("ok").booleanValue(), out);
    assertTrue(report.path("stages").path("connections").path("ok").booleanValue(), out);
    assertTrue(report.path("stages").path("properties").path("ok").booleanValue(), out);
    verify(getRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/elements/cond-1")));

    verify(
        1,
        postRequestedFor(urlEqualTo(postPath))
            .withRequestBody(matchingJsonPath("$.type", equalTo("if")))
            .withRequestBody(matchingJsonPath("$.parentElementId", equalTo("cond-1"))));
    verify(
        0,
        postRequestedFor(urlEqualTo(postPath))
            .withRequestBody(matchingJsonPath("$.type", equalTo("else"))));

    verify(
        1,
        postRequestedFor(urlEqualTo(postPath))
            .withRequestBody(matchingJsonPath("$.type", equalTo("script")))
            .withRequestBody(matchingJsonPath("$.parentElementId", equalTo("if-even-1"))));
    verify(
        1,
        postRequestedFor(urlEqualTo(postPath))
            .withRequestBody(matchingJsonPath("$.type", equalTo("script")))
            .withRequestBody(matchingJsonPath("$.parentElementId", equalTo("if-zero-1"))));
    verify(
        1,
        postRequestedFor(urlEqualTo(postPath))
            .withRequestBody(matchingJsonPath("$.type", equalTo("script")))
            .withRequestBody(matchingJsonPath("$.parentElementId", equalTo("else-1"))));
  }

  @Test
  void createElementsByJsonPostsConnectionsAndPatchesProperties() throws Exception {
    String chainId = "c-conn-props";
    String plan =
        """
        {
          "elements": [
            {
              "clientId": "t1",
              "type": "http-trigger",
              "expectedProperties": { "contextPath": "/c", "httpMethodRestrict": "GET" }
            },
            { "clientId": "c1", "type": "condition", "expectedProperties": {} }
          ],
          "connections": [ { "fromClientId": "t1", "toClientId": "c1" } ]
        }
        """;

    stubListDependencies(chainId, "[]");
    stubListElements(
        chainId,
        "[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"parentElementId\":null,\"properties\":{}},"
            + "{\"id\":\"cond-1\",\"type\":\"condition\",\"parentElementId\":null,\"properties\":{}}]");
    stubPostDependencyOk(chainId);
    stubPatchElementsOk(chainId);
    stubGetElement(chainId, "t-1", "http-trigger", "null", "{}");

    String postPath = "/v1/chains/" + chainId + "/elements";
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("conn-props")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("after-t1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("conn-props")
            .whenScenarioStateIs("after-t1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    String out = catalogElementTools.createElementsByJson(chainId, plan);

    JsonNode report = CatalogToolResultTestSupport.materializeReportData(objectMapper, out);
    assertTrue(report.path("stages").path("skeleton").path("ok").booleanValue(), out);
    assertTrue(report.path("stages").path("connections").path("ok").booleanValue(), out);
    assertTrue(report.path("stages").path("properties").path("ok").booleanValue(), out);
    verify(1, postRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/dependencies")));
    verify(2, postRequestedFor(urlEqualTo(postPath)));
  }

  @Test
  void createElementsByJsonSkipsExistingDependenciesOnRetry() {
    String chainId = "c-skip-dep";
    String plan =
        """
        {
          "elements": [
            { "clientId": "t1", "type": "http-trigger", "expectedProperties": {} },
            { "clientId": "c1", "type": "condition", "expectedProperties": {} }
          ],
          "connections": [ { "fromClientId": "t1", "toClientId": "c1" } ]
        }
        """;

    stubListDependencies(chainId, "[{\"id\":\"d0\",\"from\":\"t-1\",\"to\":\"cond-1\"}]");
    stubListElements(
        chainId,
        "[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"parentElementId\":null,\"properties\":{}},"
            + "{\"id\":\"cond-1\",\"type\":\"condition\",\"parentElementId\":null,\"properties\":{}}]");

    String postPath = "/v1/chains/" + chainId + "/elements";
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("skip-dep")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("after-t1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("skip-dep")
            .whenScenarioStateIs("after-t1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    catalogElementTools.createElementsByJson(chainId, plan);

    verify(0, postRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/dependencies")));
  }

  @Test
  void createElementsByJsonExplicitBatchRestoresConnectionsFromApprovedSnapshot() {
    String conv = UUID.randomUUID().toString();
    MDC.put(ChatMdc.CONVERSATION_ID, conv);
    String approvedPlan =
        """
        {
          "chain": { "name": "Restore dep", "description": "" },
          "elements": [
            { "clientId": "t1", "type": "http-trigger", "expectedProperties": {} },
            { "clientId": "c1", "type": "condition", "expectedProperties": {} }
          ],
          "connections": [ { "fromClientId": "t1", "toClientId": "c1" } ]
        }
        """;
    activeChainPlanService.captureFromAssistantText(conv, "```json\n" + approvedPlan + "\n```");
    activeChainPlanService.applyHitlAgreeOptionChosen(conv);

    String chainId = "c-restore-dep";
    String explicitBatchWithoutConnections =
        """
        {
          "elements": [
            { "clientId": "t1", "type": "http-trigger", "expectedProperties": {} },
            { "clientId": "c1", "type": "condition", "expectedProperties": {} }
          ]
        }
        """;

    stubListDependencies(chainId, "[]");
    stubListElements(
        chainId,
        "[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"parentElementId\":null,\"properties\":{}},"
            + "{\"id\":\"cond-1\",\"type\":\"condition\",\"parentElementId\":null,\"properties\":{}}]");
    stubPostDependencyOk(chainId);

    String postPath = "/v1/chains/" + chainId + "/elements";
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("restore-dep")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("after-t1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"t-1\",\"type\":\"http-trigger\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));
    stubFor(
        post(urlEqualTo(postPath))
            .inScenario("restore-dep")
            .whenScenarioStateIs("after-t1")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    String out = catalogElementTools.createElementsByJson(chainId, explicitBatchWithoutConnections);

    assertTrue(t(out).contains("\"connections\":{\"ok\":true"), out);
    verify(1, postRequestedFor(urlEqualTo("/v1/chains/" + chainId + "/dependencies")));
  }

  @Test
  void createElementsByJsonPropertiesStageRecordsCatalogHttpFailure() throws Exception {
    String chainId = "c-prop-fail";
    String plan =
        """
        {
          "elements": [
            {
              "clientId": "c1",
              "type": "condition",
              "children": [
                {
                  "clientId": "if1",
                  "type": "if",
                  "expectedProperties": { "condition": "true", "priority": 0 }
                }
              ]
            }
          ],
          "connections": []
        }
        """;

    stubListDependencies(chainId, "[]");
    stubListElements(
        chainId,
        "[{\"id\":\"cond-1\",\"type\":\"condition\",\"parentElementId\":null,\"properties\":{}},"
            + "{\"id\":\"if-seed-1\",\"type\":\"if\",\"parentElementId\":\"cond-1\",\"properties\":{}}]");

    stubFor(
        post(urlEqualTo("/v1/chains/" + chainId + "/elements"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":[{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{}}],"
                            + "\"updatedElements\":null,\"createdDependencies\":null}")));

    stubFor(
        get(urlEqualTo("/v1/chains/" + chainId + "/elements/cond-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"cond-1\",\"type\":\"condition\",\"properties\":{},\"children\":["
                            + "{\"id\":\"if-seed-1\",\"type\":\"if\",\"properties\":{},\"children\":[]}"
                            + "]}")));

    stubGetElement(chainId, "if-seed-1", "if", "\"cond-1\"", "{\"priority\":0}");

    stubFor(
        patch(urlEqualTo("/v1/chains/" + chainId + "/elements/if-seed-1"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorMessage\":\"Schema validation failed for element type if:"
                            + " missingRequired:[condition]\"}")));

    String out = catalogElementTools.createElementsByJson(chainId, plan);
    JsonNode report = CatalogToolResultTestSupport.materializeReportData(objectMapper, out);
    assertTrue(report.path("stages").path("skeleton").path("ok").booleanValue(), out);
    assertFalse(report.path("stages").path("properties").path("ok").booleanValue(), out);
    assertEquals(1, report.path("stages").path("properties").path("failures").size());
    assertEquals(
        "if1",
        report.path("stages").path("properties").path("failures").path(0).path("clientId").asText());
    assertEquals(
        "if-seed-1",
        report.path("stages").path("properties").path("failures").path(0).path("elementId").asText());
    assertTrue(
        report.path("stages")
            .path("properties")
            .path("failures")
            .path(0)
            .path("reason")
            .asText()
            .contains("400"),
        out);
  }

  private static final String SCRIPT_BASE_GET_PROPS =
      "{\"propertiesToExportInSeparateFile\":\"script\",\"exportFileExtension\":\"groovy\",\"script\":\"\"}";

  private void stubListDependencies(String chainId, String jsonArrayBody) {
    stubFor(
        get(urlEqualTo("/v1/chains/" + chainId + "/dependencies"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jsonArrayBody)));
  }

  private void stubListElements(String chainId, String jsonArrayBody) {
    stubFor(
        get(urlEqualTo("/v1/chains/" + chainId + "/elements"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jsonArrayBody)));
  }

  private void stubPostDependencyOk(String chainId) {
    stubFor(
        post(urlEqualTo("/v1/chains/" + chainId + "/dependencies"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":null,\"updatedElements\":null,\"createdDependencies\":[{\"id\":\"dep-1\",\"from\":\"http-tr-1\",\"to\":\"cond-1\"}]}")));
  }

  private void stubGetElement(
      String chainId,
      String elementId,
      String type,
      String parentElementIdJson,
      String propertiesJson) {
    String body =
        "{\"id\":\""
            + elementId
            + "\",\"type\":\""
            + type
            + "\",\"parentElementId\":"
            + parentElementIdJson
            + ",\"properties\":"
            + propertiesJson
            + "}";
    stubFor(
        get(urlEqualTo("/v1/chains/" + chainId + "/elements/" + elementId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void stubPatchElementsOk(String chainId) {
    stubFor(
        patch(urlPathMatching("/v1/chains/" + chainId + "/elements/.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"createdElements\":null,\"updatedElements\":[{\"id\":\"patched\",\"type\":\"http-trigger\",\"properties\":{}}],\"createdDependencies\":null}")));
  }

  private void stubCond10ElementGets(String chainId) {
    stubGetElement(chainId, "http-tr-1", "http-trigger", "null", "{}");
    stubGetElement(chainId, "cond-1", "condition", "null", "{}");
    stubGetElement(chainId, "if-even-1", "if", "\"cond-1\"", "{\"priority\":0}");
    stubGetElement(chainId, "if-zero-1", "if", "\"cond-1\"", "{\"priority\":1}");
    stubGetElement(chainId, "else-1", "else", "\"cond-1\"", "{}");
    stubGetElement(chainId, "scr-g", "script", "\"if-even-1\"", SCRIPT_BASE_GET_PROPS);
    stubGetElement(chainId, "scr-c", "script", "\"if-zero-1\"", SCRIPT_BASE_GET_PROPS);
    stubGetElement(chainId, "scr-h", "script", "\"else-1\"", SCRIPT_BASE_GET_PROPS);
  }
}

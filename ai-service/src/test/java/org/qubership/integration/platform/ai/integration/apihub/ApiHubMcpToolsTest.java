package org.qubership.integration.platform.ai.integration.apihub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;

class ApiHubMcpToolsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {
      "Use only the runtime catalog.",
      "Do not use the API Hub.",
      "Continue without the API Hub."
  })
  void directOperationReadCannotBypassTheCatalogOnlyPolicy(String restriction) {
    var conversations = new ConversationService();
    conversations.archive().beginInput("catalog-only", restriction, null);
    var client = mock(ApiHubMcpClient.class);
    var tools = new ApiHubMcpTools(client, objectMapper, null, null, null,
        new ApiHubSearchAuthorizations(conversations));

    try (var ignored = ToolSession.open("catalog-only")) {
      String result = tools.getApiOperationSpecification("operation", "package", "1.0", "rest");
      assertTrue(result.contains(ApiHubSearchAuthorizations.DISABLED_MESSAGE),
          () -> "Restriction: " + restriction + ", result: " + result);
    }
    verifyNoInteractions(client);
  }

  @Test
  void packagesListResourceUsesMcpUriScheme() {
    assertEquals("mcp://api-packages-list", ApiHubMcpTools.MCP_RESOURCE_PACKAGES_LIST);
  }

  @Test
  void extractTextReadsPackagesListResourceContents() throws Exception {
    String body =
        objectMapper
            .readTree(
                """
                {
                  "result": {
                    "contents": [
                      {
                        "uri": "mcp://api-packages-list",
                        "text": "{\\"packages\\":[{\\"packageId\\":\\"S.ActProv.SvcCat\\"}]}"
                      }
                    ]
                  }
                }
                """)
            .toString();

    String text = ApiHubMcpTools.extractText(objectMapper.readTree(body), objectMapper);

    assertTrue(text.contains("S.ActProv.SvcCat"));
  }
}

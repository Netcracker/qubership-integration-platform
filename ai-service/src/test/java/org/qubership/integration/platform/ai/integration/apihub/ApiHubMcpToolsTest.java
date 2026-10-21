package org.qubership.integration.platform.ai.integration.apihub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ApiHubMcpToolsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

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

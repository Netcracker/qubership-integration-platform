package org.qubership.integration.platform.ai.chat.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogDiaryOutcomeParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConversationPlanningDiaryService diary = new ConversationPlanningDiaryService();

  @Test
  void recordSuccessSearchCatalogSystemsPopulatesDiary() throws Exception {
    String arrayJson =
        """
[{"id":"364ea2f4-8918-4e47-9fc3-17652f1706d3","name":"Pet store","type":"IMPLEMENTED","protocol":"http"}]
""";
    String envelope =
        CatalogToolResult.success(objectMapper, "searchCatalogSystems", objectMapper.readTree(arrayJson));
    CatalogDiaryOutcomeParser.recordSuccess(
        diary,
        objectMapper,
        "conv-parser",
        "searchCatalogSystems",
        "searchCondition=Pet store",
        envelope);

    String appendix = diary.formatAppendix("conv-parser");
    assertTrue(appendix.contains("Catalog services resolved"));
    assertTrue(appendix.contains("Pet store"));
    assertTrue(appendix.contains("364ea2f4-8918-4e47-9fc3-17652f1706d3"));
    assertTrue(appendix.contains("searchCondition=Pet store"));
  }

  @Test
  void recordSuccessGetApiSpecificationsAddsModelId() throws Exception {
    diary.recordCatalogSystemsFound(
        "conv-spec",
        "Pet store",
        java.util.List.of(
            new org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient
                .SystemDto(
                "364ea2f4-8918-4e47-9fc3-17652f1706d3", "Pet store", "IMPLEMENTED", "http")));
    String arrayJson =
        """
        [{"id":"364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7","name":"1.0.7"}]
        """;
    String envelope =
        CatalogToolResult.success(objectMapper, "getApiSpecifications", objectMapper.readTree(arrayJson));
    CatalogDiaryOutcomeParser.recordSuccess(
        diary,
        objectMapper,
        "conv-spec",
        "getApiSpecifications",
        "systemId=364ea2f4-8918-4e47-9fc3-17652f1706d3",
        envelope);

    String appendix = diary.formatAppendix("conv-spec");
    assertTrue(appendix.contains("364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7"));
  }
}

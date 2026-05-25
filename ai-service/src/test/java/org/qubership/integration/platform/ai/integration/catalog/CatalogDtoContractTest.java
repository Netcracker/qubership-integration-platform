package org.qubership.integration.platform.ai.integration.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract snapshots: request serialization and response deserialization must match fixtures under
 * {@code catalog-contract/}. Update fixtures manually when runtime-catalog JSON changes.
 */
class CatalogDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode tree(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  @Test
  void requestCreateChainWithDescriptionMatchesFixture() throws Exception {
    CatalogCreateChainRequest dto = CatalogCreateChainRequest.of("CN", "CD");
    JsonNode serialized = MAPPER.valueToTree(dto);
    assertEquals(
        tree(CatalogContractFixtures.resourceUtf8("catalog-contract/requests/create-chain.json")),
        serialized);
    assertTrue(serialized.get("labels").isArray());
    assertEquals(0, serialized.get("labels").size());
  }

  @Test
  void requestCreateChainWithoutDescriptionOmitsDescriptionField() throws Exception {
    CatalogCreateChainRequest dto = CatalogCreateChainRequest.of("CN", "");
    assertEquals(
        tree(
            CatalogContractFixtures.resourceUtf8(
                "catalog-contract/requests/create-chain-no-description.json")),
        MAPPER.valueToTree(dto));
  }

  @Test
  void requestCreateElementMatchesFixture() throws Exception {
    CatalogCreateElementRequest dto = new CatalogCreateElementRequest("http-trigger", null, null);
    assertEquals(
        tree(CatalogContractFixtures.resourceUtf8("catalog-contract/requests/create-element.json")),
        MAPPER.valueToTree(dto));
  }

  @Test
  void requestPatchElementPropertiesMapMatchesFixture() throws Exception {
    Map<String, Object> body = Map.of("properties", Map.of("k", "v"));
    assertEquals(
        tree(CatalogContractFixtures.resourceUtf8("catalog-contract/requests/patch-element.json")),
        MAPPER.valueToTree(body));
  }

  @Test
  void requestCreateDependencyMatchesFixture() throws Exception {
    CatalogCreateDependencyRequest dto = new CatalogCreateDependencyRequest("a", "b");
    assertEquals(
        tree(
            CatalogContractFixtures.resourceUtf8(
                "catalog-contract/requests/create-dependency.json")),
        MAPPER.valueToTree(dto));
  }

  @Test
  void requestCreateSystemMatchesFixture() throws Exception {
    CatalogCreateSystemRequest dto = new CatalogCreateSystemRequest("Sys", "EXTERNAL");
    assertEquals(
        tree(CatalogContractFixtures.resourceUtf8("catalog-contract/requests/create-system.json")),
        MAPPER.valueToTree(dto));
  }

  @Test
  void responseChainDeserializesFromFixture() throws Exception {
    String json = CatalogContractFixtures.resourceUtf8("catalog-contract/responses/chain.json");
    CatalogRestClient.ChainDto dto = MAPPER.readValue(json, CatalogRestClient.ChainDto.class);
    assertEquals("chain-1", dto.id());
    assertEquals("CN", dto.name());
    assertEquals("CD", dto.description());
  }

  @Test
  void responseChainDiffCreatedElementDeserializesFromFixture() throws Exception {
    String json =
        CatalogContractFixtures.resourceUtf8(
            "catalog-contract/responses/chain-diff-created-element.json");
    CatalogRestClient.ChainDiffDto dto =
        MAPPER.readValue(json, CatalogRestClient.ChainDiffDto.class);
    assertEquals(1, dto.createdElements().size());
    assertEquals("e1", dto.createdElements().get(0).id());
  }

  @Test
  void responseChainDiffUpdatedElementDeserializesFromFixture() throws Exception {
    String json =
        CatalogContractFixtures.resourceUtf8(
            "catalog-contract/responses/chain-diff-updated-element.json");
    CatalogRestClient.ChainDiffDto dto =
        MAPPER.readValue(json, CatalogRestClient.ChainDiffDto.class);
    assertEquals(1, dto.updatedElements().size());
    assertEquals("e1", dto.updatedElements().get(0).id());
  }

  @Test
  void responseChainDiffDependencyDeserializesFromFixture() throws Exception {
    String json =
        CatalogContractFixtures.resourceUtf8(
            "catalog-contract/responses/chain-diff-created-dependency.json");
    CatalogRestClient.ChainDiffDto dto =
        MAPPER.readValue(json, CatalogRestClient.ChainDiffDto.class);
    assertEquals(1, dto.createdDependencies().size());
    assertEquals("d1", dto.createdDependencies().get(0).id());
  }

  @Test
  void responseSystemDeserializesFromFixture() throws Exception {
    String json = CatalogContractFixtures.resourceUtf8("catalog-contract/responses/system.json");
    CatalogRestClient.SystemDto dto = MAPPER.readValue(json, CatalogRestClient.SystemDto.class);
    assertEquals("s1", dto.id());
    assertEquals("Sys", dto.name());
  }

  @Test
  void responseModelsDeserializesFromFixture() throws Exception {
    String json = CatalogContractFixtures.resourceUtf8("catalog-contract/responses/models.json");
    List<CatalogRestClient.SpecificationDto> list =
        MAPPER.readValue(
            json,
            MAPPER
                .getTypeFactory()
                .constructCollectionType(List.class, CatalogRestClient.SpecificationDto.class));
    assertEquals(1, list.size());
    assertEquals("m1", list.get(0).id());
  }

  @Test
  void responseElementsListDeserializesFromFixture() throws Exception {
    String json =
        CatalogContractFixtures.resourceUtf8("catalog-contract/responses/elements-list.json");
    List<CatalogElementResponseDto> list =
        MAPPER.readValue(
            json,
            MAPPER
                .getTypeFactory()
                .constructCollectionType(List.class, CatalogElementResponseDto.class));
    assertEquals(1, list.size());
    assertEquals("e1", list.get(0).id);
    assertEquals("POST", list.get(0).properties.get("httpMethod"));
  }

  @Test
  void responseDependenciesListDeserializesFromFixture() throws Exception {
    String json =
        CatalogContractFixtures.resourceUtf8("catalog-contract/responses/dependencies-list.json");
    List<CatalogDependencyDto> list =
        MAPPER.readValue(
            json,
            MAPPER
                .getTypeFactory()
                .constructCollectionType(List.class, CatalogDependencyDto.class));
    assertEquals(1, list.size());
    assertEquals("e1", list.get(0).from);
    assertEquals("e2", list.get(0).to);
  }

  @Test
  void responseOperationsDeserializesFromFixture() throws Exception {
    String json =
        CatalogContractFixtures.resourceUtf8("catalog-contract/responses/operations.json");
    List<CatalogRestClient.OperationDto> list =
        MAPPER.readValue(
            json,
            MAPPER
                .getTypeFactory()
                .constructCollectionType(List.class, CatalogRestClient.OperationDto.class));
    assertEquals(1, list.size());
    assertEquals("op1", list.get(0).id());
  }
}

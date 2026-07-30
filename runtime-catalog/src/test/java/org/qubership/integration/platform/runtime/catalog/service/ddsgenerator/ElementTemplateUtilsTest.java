package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateSchema;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.ElementTemplateUtils;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.JsonSchemaParser;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.findInput;

/**
 * Confirms DDS generation reads operation schemas through the on-demand extractor now that
 * {@code requestSchema} / {@code responseSchemas} are {@code @Transient}. The oracle is the shared
 * conformance corpus, so the emitted {@link TemplateSchema} tree must reflect the schemas import
 * once materialized, with the non-content-type {@code parameters} key excluded.
 */
class ElementTemplateUtilsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperationSchemaExtractor extractor = ExtractorTestParsers.extractor();

    @Test
    void producesRequestAndResponseTemplateSchemas() throws Exception {
        String operationId = "op-createAsset";
        ElementTemplateUtils utils = utilsFor("openapi31-aperture-dam", "createAsset.expected.json",
                OperationProtocol.HTTP, operationId);

        Map<String, Object> templateProps = new HashMap<>();
        utils.addJSONSchemas(Map.of(CamelOptions.OPERATION_ID, operationId), templateProps);

        Map<String, TemplateSchema> requestSchema = requestSchema(templateProps);
        assertEquals(Set.of("application/json"), requestSchema.keySet());
        TemplateSchema requestBody = requestSchema.get("application/json");
        assertFalse(requestBody.getProperties().isEmpty(), "createAsset request body must yield properties");
        assertFalse(requestBody.getDefinitions().isEmpty(), "createAsset request body must yield definitions");

        Map<String, Map<String, TemplateSchema>> responseSchema = responseSchema(templateProps);
        assertEquals(Set.of("201", "422", "default"), responseSchema.keySet());
        assertEquals(Set.of("application/json"), responseSchema.get("201").keySet());
        TemplateSchema created = responseSchema.get("201").get("application/json");
        assertFalse(created.getProperties().isEmpty(), "createAsset 201 response must yield properties");
        assertFalse(created.getDefinitions().isEmpty(), "createAsset 201 response must yield definitions");
        assertTrue(responseSchema.get("422").isEmpty(), "response 422 carries no content-type schema");
        assertTrue(responseSchema.get("default").isEmpty(), "response default carries no content-type schema");
    }

    @Test
    void excludesParametersKeyFromRequestSchema() throws Exception {
        String operationId = "op-getAssetById";
        ElementTemplateUtils utils = utilsFor("openapi31-aperture-dam", "getAssetById.expected.json",
                OperationProtocol.HTTP, operationId);

        Map<String, Object> templateProps = new HashMap<>();
        utils.addJSONSchemas(Map.of(CamelOptions.OPERATION_ID, operationId), templateProps);

        Map<String, TemplateSchema> requestSchema = requestSchema(templateProps);
        assertFalse(requestSchema.containsKey("parameters"), "the parameters key is not a content type and must be excluded");
        assertTrue(requestSchema.isEmpty(), "getAssetById carries only parameters, so no content-type schema remains");

        Map<String, Map<String, TemplateSchema>> responseSchema = responseSchema(templateProps);
        assertEquals(Set.of("200", "304", "404"), responseSchema.keySet());
        assertEquals(Set.of("application/json"), responseSchema.get("200").keySet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TemplateSchema> requestSchema(Map<String, Object> templateProps) {
        Object value = templateProps.get("requestSchema");
        assertNotNull(value, "requestSchema must be present in template props");
        return (Map<String, TemplateSchema>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, TemplateSchema>> responseSchema(Map<String, Object> templateProps) {
        Object value = templateProps.get("responseSchema");
        assertNotNull(value, "responseSchema must be present in template props");
        return (Map<String, Map<String, TemplateSchema>>) value;
    }

    private ElementTemplateUtils utilsFor(String caseDir, String expectedFile, OperationProtocol protocol,
                                          String operationId) throws IOException {
        JsonNode expected = loadExpected(caseDir, expectedFile);
        Operation operation = buildOperation(caseDir, expected, protocol, operationId);
        OperationRepository repository = mock(OperationRepository.class);
        when(repository.findById(operationId)).thenReturn(Optional.of(operation));
        OperationService service = new OperationService(
                repository,
                JSON,
                mock(ElementHelperService.class),
                extractor);
        return new ElementTemplateUtils(new JsonSchemaParser(JSON), service);
    }

    private Operation buildOperation(String caseDir, JsonNode expected, OperationProtocol protocol,
                                     String operationId) throws IOException {
        String rawSource = Files.readString(findInput(corpusRoot().resolve(caseDir)));
        IntegrationSystem system = IntegrationSystem.builder().protocol(protocol).build();
        ApiGroup group = ApiGroup.builder().system(system).build();
        SystemModel model = SystemModel.builder().apiGroup(group).build();
        SpecificationSource source = SpecificationSource.builder().isMainSource(true).source(rawSource).build();
        model.addProvidedSpecificationSource(source);
        return Operation.builder()
                .id(operationId)
                .path(expected.get("path").asText())
                .method(expected.get("method").asText())
                .systemModel(model)
                .build();
    }

    private static JsonNode loadExpected(String caseDir, String expectedFile) throws IOException {
        return JSON.readTree(corpusRoot().resolve(caseDir).resolve(expectedFile).toFile());
    }

}

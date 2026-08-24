package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.io.model.exportimport.system.ApiGroupContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationSourceDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.KubeSecretSerializer;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export filter is registered by id and applies only to classes annotated {@code @JsonFilter("baseEntityFilter")}.
 * With {@code setFailOnUnknownId(false)} on every other mapper, a missing annotation costs nothing at runtime and
 * shows up nowhere in the logs, so the only way to tell a working filter from a dead one is to serialize through it.
 */
class MapperAutoConfigurationTest {

    private static final List<String> EXCLUDED_FIELDS =
            List.of("createdWhen", "createdBy", "modifiedBy", "sourceHash");

    private final MapperAutoConfiguration configuration = new MapperAutoConfiguration();
    private final YAMLMapper exportMapper = configuration.yamlExportImportMapper();

    static Stream<Arguments> annotatedExportDtos() {
        Timestamp when = Timestamp.valueOf("2026-01-01 00:00:00");
        User user = User.builder().id("u-1").username("tester").build();
        return Stream.of(
                Arguments.of("SpecificationSourceDto", SpecificationSourceDto.builder()
                        .id("src-1")
                        .name("api.yaml")
                        .fileName("source-sm-1/api.yaml")
                        .mainSource(true)
                        .createdWhen(when)
                        .createdBy(user)
                        .modifiedBy(user)
                        .sourceHash("0ff1ce")
                        .build(), "filePath"),
                Arguments.of("IntegrationSystemContentDto", IntegrationSystemContentDto.builder()
                        .description("service")
                        .internalServiceName("orders")
                        .createdWhen(when)
                        .createdBy(user)
                        .modifiedBy(user)
                        .build(), "internalServiceName"),
                Arguments.of("ApiGroupContentDto", ApiGroupContentDto.builder()
                        .description("group")
                        .url("/orders")
                        .createdWhen(when)
                        .createdBy(user)
                        .modifiedBy(user)
                        .build(), "url"),
                Arguments.of("SystemModelContentDto", SystemModelContentDto.builder()
                        .description("api")
                        .version("1.0.0")
                        .createdWhen(when)
                        .createdBy(user)
                        .modifiedBy(user)
                        .build(), "version"),
                Arguments.of("MCPServiceContentDto", MCPServiceContentDto.builder()
                        .description("mcp")
                        .identifier("mcp-1")
                        .createdWhen(when)
                        .createdBy(user)
                        .modifiedBy(user)
                        .build(), "identifier"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("annotatedExportDtos")
    void exportMapperDropsTheExcludedFields(String name, Object dto, String retainedField) {
        JsonNode node = exportMapper.valueToTree(dto);

        EXCLUDED_FIELDS.forEach(field ->
                assertFalse(node.has(field), () -> name + " must not export " + field));
        assertTrue(node.has(retainedField),
                () -> name + " must still export " + retainedField + ": the filter excludes, it does not whitelist");
    }

    /** Every other mapper leaves the annotated class alone; only the export mapper registers the filter. */
    @Test
    void theOtherMappersKeepTheFieldsTheExportMapperDrops() {
        Object dto = annotatedExportDtos().findFirst().orElseThrow().get()[1];

        JsonNode node = configuration.defaultYamlMapper().valueToTree(dto);

        assertTrue(node.has("sourceHash"), "only the export mapper filters");
        assertTrue(node.has("createdWhen"), "only the export mapper filters");
    }

    /**
     * A mapper with no {@code FilterProvider} at all fails the whole serialization with "Cannot resolve PropertyFilter
     * with id 'baseEntityFilter'", so every mapper that could ever meet an annotated class needs the permissive one.
     */
    @Test
    void everyMapperCanSerializeAnAnnotatedClass() {
        Object dto = annotatedExportDtos().findFirst().orElseThrow().get()[1];
        ObjectMapper primaryObjectMapper = configuration.qipPrimaryObjectMapper();

        assertDoesNotThrow(() -> primaryObjectMapper.writeValueAsString(dto));
        assertDoesNotThrow(() -> configuration.objectMapperWithSorting().writeValueAsString(dto));
        assertDoesNotThrow(() -> configuration.defaultYamlMapper().writeValueAsString(dto));
        assertDoesNotThrow(() -> configuration.codeViewYamlMapper(primaryObjectMapper).writeValueAsString(dto));
        assertDoesNotThrow(() -> configuration.yamlMapper(new KubeSecretSerializer()).writeValueAsString(dto));
        assertDoesNotThrow(() -> configuration.variablesYamlMapper(new KubeSecretSerializer()).writeValueAsString(dto));
    }

    /** Jackson writes `---` by default; every document exported here is a single-document file. */
    @Test
    void exportedDocumentsCarryNoDocumentStartMarker() throws Exception {
        Object dto = annotatedExportDtos().findFirst().orElseThrow().get()[1];

        assertFalse(exportMapper.writeValueAsString(dto).startsWith("---"),
                "the export must not open with a document-start marker");
    }

    /**
     * An entity with no {@code @JsonInclude} of its own inherits the mapper default. Under NON_NULL, Hibernate's
     * empty collection reached the archive as {@code labels: []} while the same absent data was a missing key on an
     * annotated DTO next to it.
     */
    @Test
    void exportMapperDropsEmptyCollectionsOnAnUnannotatedEntity() {
        Environment environment = Environment.builder()
                .address("https://example.test")
                .labels(List.of())
                .build();
        environment.setId("env-1");
        environment.setName("Production");

        JsonNode node = exportMapper.valueToTree(environment);

        assertFalse(node.has("labels"), "an empty collection carries no information");
        assertTrue(node.has("address"), "a populated field must survive");
    }

    /**
     * Content inclusion stays NON_NULL: inside a free-form map such as a chain element's properties, an empty string
     * is a value the element chose, and dropping it would change the element on re-import.
     */
    @Test
    void exportMapperKeepsAnEmptyStringInsideAMapButStillDropsNull() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contextPath", "");
        properties.put("unset", null);

        JsonNode node = exportMapper.valueToTree(properties);

        assertTrue(node.has("contextPath"), "an explicitly empty property value must survive");
        assertFalse(node.has("unset"), "a null property value stays dropped");
    }
}

package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainConfigurationsToFilesConverterTest {

    private static final String APP_PREFIX = "qip";
    private static final String CHAIN_ID = "chain-abc";

    private ObjectMapper objectMapper;
    private ChainConfigurationsToFilesConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new ChainConfigurationsToFilesConverter(objectMapper, APP_PREFIX, Collections.emptyList());
    }

    @Test
    @DisplayName("Empty chainConfigs returns empty map")
    void emptyChainConfigsReturnsEmptyMap() throws JsonProcessingException {
        Map<Path, byte[]> result = converter.convert(Collections.emptyMap(), Collections.emptyMap());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Single chain config creates file at {chainId}/{chainId}.chain.{appPrefix}.yaml")
    void singleChainConfigCreatesCorrectFilePath() throws JsonProcessingException {
        RolloutImportConfigurationItem item = chainItem(CHAIN_ID, objectMapper.createObjectNode());
        Path expectedPath = Path.of(CHAIN_ID).resolve(CHAIN_ID + ".chain." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(Map.of(CHAIN_ID, item), Collections.emptyMap());

        assertThat(result).containsKey(expectedPath);
    }

    @Test
    @DisplayName("Chain content ObjectNode gains 'migrations' field when absent")
    void chainContentGainsMigrationsFieldWhenAbsent() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode();
        RolloutImportConfigurationItem item = chainItem(CHAIN_ID, content);

        converter.convert(Map.of(CHAIN_ID, item), Collections.emptyMap());

        assertThat(content.has("migrations")).isTrue();
        assertThat(content.get("migrations").asText()).isEqualTo("[]");
    }

    @Test
    @DisplayName("Existing 'migrations' field in content is not overwritten")
    void existingMigrationsFieldIsNotOverwritten() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("migrations", "[v1, v2]");
        RolloutImportConfigurationItem item = chainItem(CHAIN_ID, content);

        converter.convert(Map.of(CHAIN_ID, item), Collections.emptyMap());

        assertThat(content.get("migrations").asText()).isEqualTo("[v1, v2]");
    }

    @Test
    @DisplayName("Chain referencing an existing resource includes resource bytes in result")
    void chainWithExistingResourceIncludesResourceInResult() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode();
        String resourceFileName = "script.groovy";
        content.put("propertiesFilename", resourceFileName);

        RolloutImportConfigurationItem item = chainItem(CHAIN_ID, content);
        String resourceContent = "println 'hello'";
        Map<String, String> resources = Map.of(resourceFileName, resourceContent);

        Map<Path, byte[]> result = converter.convert(Map.of(CHAIN_ID, item), resources);

        Path expectedResourcePath = Path.of(CHAIN_ID).resolve(resourceFileName);
        assertThat(result).containsKey(expectedResourcePath);
        assertThat(result.get(expectedResourcePath)).isEqualTo(resourceContent.getBytes());
    }

    @Test
    @DisplayName("Chain referencing a missing resource is not added to result for that resource")
    void chainWithMissingResourceDoesNotIncludeResourceEntry() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("propertiesFilename", "missing-file.groovy");
        RolloutImportConfigurationItem item = chainItem(CHAIN_ID, content);

        Map<Path, byte[]> result = converter.convert(Map.of(CHAIN_ID, item), Collections.emptyMap());

        Path missingPath = Path.of(CHAIN_ID).resolve("missing-file.groovy");
        assertThat(result).doesNotContainKey(missingPath);
        // chain yaml file is still present
        Path chainFilePath = Path.of(CHAIN_ID).resolve(CHAIN_ID + ".chain." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(chainFilePath);
    }

    @Test
    @DisplayName("Multiple chain configs each produce their own file entry")
    void multipleChainConfigsProduceMultipleEntries() throws JsonProcessingException {
        List<String> chainIds = List.of("chain-1", "chain-2", "chain-3");
        Map<String, RolloutImportConfigurationItem> configs = new java.util.HashMap<>();
        for (String id : chainIds) {
            configs.put(id, chainItem(id, objectMapper.createObjectNode()));
        }

        Map<Path, byte[]> result = converter.convert(configs, Collections.emptyMap());

        for (String id : chainIds) {
            Path expected = Path.of(id).resolve(id + ".chain." + APP_PREFIX + ".yaml");
            assertThat(result).containsKey(expected);
        }
    }

    private RolloutImportConfigurationItem chainItem(String id, ObjectNode content) {
        RolloutImportConfigurationItem item = new RolloutImportConfigurationItem();
        item.setId(id);
        item.setContent(content);
        return item;
    }
}

package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


@Slf4j
@Component
public class VariableConfigurationsToFilesConverter {

    private static final String VARIABLE_YAML_NAME = "common-variables";

    private final ObjectMapper objectMapper;

    public VariableConfigurationsToFilesConverter(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<Path, byte[]> convert(Map<String, JsonNode> variableConfigs) throws JacksonException {
        if (variableConfigs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Path, byte[]> files = new HashMap<>();
        for (Map.Entry<String, JsonNode> variableConfig : variableConfigs.entrySet()) {
            String variableId = variableConfig.getKey();
            Path variableDirectory = Path.of(variableId);
            files.put(variableDirectory.resolve(VARIABLE_YAML_NAME), objectMapper.writeValueAsBytes(variableConfig.getValue()));
        }

        return files;
    }
}

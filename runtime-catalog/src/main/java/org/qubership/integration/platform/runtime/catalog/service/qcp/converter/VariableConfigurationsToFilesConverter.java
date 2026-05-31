package org.qubership.integration.platform.runtime.catalog.service.qcp.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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

    public Map<Path, byte[]> convert(Map<String, JsonNode> variableConfigs) throws JsonProcessingException {
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

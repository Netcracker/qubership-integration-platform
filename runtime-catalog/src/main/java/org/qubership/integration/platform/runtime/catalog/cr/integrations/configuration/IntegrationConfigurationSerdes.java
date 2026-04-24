package org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.builders.IntegrationsConfigurationConfigMapBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;

@Component
public class IntegrationConfigurationSerdes {
    private final YAMLMapper yamlMapper;

    @Autowired
    public IntegrationConfigurationSerdes(
            @Qualifier("integrationsConfigurationMapper") YAMLMapper yamlMapper
    ) {
        this.yamlMapper = yamlMapper;
    }

    public IntegrationsConfiguration getFromConfigMap(V1ConfigMap configMap) {
        String content = Optional.ofNullable(configMap.getData())
                .orElse(Collections.emptyMap())
                .get(IntegrationsConfigurationConfigMapBuilder.CONTENT_KEY);
        return StringUtils.isBlank(content)
                ? new IntegrationsConfiguration()
                : parseYaml(content);
    }

    public IntegrationsConfiguration parseYaml(String data) {
        try {
            return yamlMapper.readValue(data, IntegrationsConfiguration.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse integration configuration", e);
        }
    }

    public String toYaml(IntegrationsConfiguration integrationsConfiguration) {
        try {
            return yamlMapper.writeValueAsString(integrationsConfiguration);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to write integration configuration", e);
        }
    }
}

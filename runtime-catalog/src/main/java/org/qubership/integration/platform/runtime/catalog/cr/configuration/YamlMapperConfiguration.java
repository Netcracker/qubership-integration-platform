package org.qubership.integration.platform.runtime.catalog.cr.configuration;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class YamlMapperConfiguration {
    @Bean("customResourceYamlMapper")
    public YAMLMapper customResourceYamlMapper() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.SPLIT_LINES)
                .build();
        return new YAMLMapper(yamlFactory);
    }

    @Bean("integrationsConfigurationMapper")
    public YAMLMapper integrationsConfigurationMapper() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.SPLIT_LINES)
                .build();
        return new YAMLMapper(yamlFactory);
    }
}

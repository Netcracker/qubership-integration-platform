package org.qubership.integration.platform.camelk.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;


@AutoConfiguration
public class YamlMapperConfiguration {
    @Bean("customResourceYamlMapper")
    public YAMLMapper customResourceYamlMapper() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .enable(YAMLWriteFeature.SPLIT_LINES)
                .build();
        return new YAMLMapper(yamlFactory);
    }

    @Bean("integrationsConfigurationMapper")
    public YAMLMapper integrationsConfigurationMapper() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.SPLIT_LINES)
                .build();
        return new YAMLMapper(yamlFactory);
    }
}

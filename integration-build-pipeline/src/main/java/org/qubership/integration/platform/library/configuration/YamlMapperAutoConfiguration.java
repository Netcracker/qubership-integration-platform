package org.qubership.integration.platform.library.configuration;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;


@AutoConfiguration
public class YamlMapperAutoConfiguration {
    private static final int CODE_POINT_LIMIT_MB = 256;

    @Bean("defaultYamlMapper")
    @ConditionalOnMissingBean(name = "defaultYamlMapper")
    public YAMLMapper defaultYamlMapper(@Qualifier("defaultYamlFactory") YAMLFactory yamlFactory) {
        return new YAMLMapper(yamlFactory);
    }

    @Bean("defaultYamlFactory")
    @ConditionalOnMissingBean(name = "defaultYamlFactory")
    public YAMLFactory createCustomYamlFactory() {
        LoadSettings loadSettings = LoadSettings.builder()
                .setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024)
                .build();
        return YAMLFactory.builder().loadSettings(loadSettings).build();
    }
}

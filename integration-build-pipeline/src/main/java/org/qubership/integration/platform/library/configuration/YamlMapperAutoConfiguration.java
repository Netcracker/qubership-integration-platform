package org.qubership.integration.platform.library.configuration;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.LoaderOptions;

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
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(CODE_POINT_LIMIT_MB * 1024 * 1024);
        return YAMLFactory.builder().loaderOptions(loaderOptions).build();
    }
}

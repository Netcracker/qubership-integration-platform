package org.qubership.integration.platform.maven.plugin.domain.configuration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.dataformat.yaml.YAMLMapper;


@Configuration
public class MavenPluginYamlMapperConfiguration {
    @Bean
    YAMLMapper yamlMapper(@Qualifier("defaultYamlMapper") YAMLMapper yamlMapper) {
        return yamlMapper;
    }
}

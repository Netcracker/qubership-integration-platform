package org.qubership.integration.platform.maven.plugin.domain.configuration;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MavenPluginYamlMapperConfiguration {
    @Bean
    YAMLMapper yamlMapper(@Qualifier("defaultYamlMapper") YAMLMapper yamlMapper) {
        return yamlMapper;
    }
}

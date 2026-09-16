package org.qubership.integration.platform.maven.plugin.domain.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@ComponentScan(basePackages = "org.qubership.integration.platform")
@PropertySource(
    value = "classpath:application.yml",
    factory = YamlPropertySourceFactory.class
)
public class ApplicationConfiguration {
}

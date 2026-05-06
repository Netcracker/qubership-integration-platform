package org.qubership.integration.platform.runtime.catalog.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(DomainProperties.class)
public class DomainAutoConfiguration {
}

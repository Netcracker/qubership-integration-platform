package org.qubership.integration.platform.runtime.catalog.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationJsonSchemaPropertiesBindingTest {

    private static final String PREFIX = "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/";

    @Test
    void applicationYmlDefaultsAreTheSchemaIds() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        ApplicationJsonSchemaProperties properties = binder
                .bind("cip.json.schemas", ApplicationJsonSchemaProperties.class)
                .get();

        assertEquals(PREFIX + "chain", properties.getChain());
        assertEquals(PREFIX + "service", properties.getService());
        assertEquals(PREFIX + "context-service", properties.getContextService());
        assertEquals(PREFIX + "mcp-service", properties.getMcpService());
        assertEquals(PREFIX + "specification-group", properties.getSpecificationGroup());
        assertEquals(PREFIX + "specification", properties.getSpecification());
    }
}

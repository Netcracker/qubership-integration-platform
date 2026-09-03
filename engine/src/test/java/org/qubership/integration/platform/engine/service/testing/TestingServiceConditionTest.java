package org.qubership.integration.platform.engine.service.testing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestingServiceConditionTest {

    private static final String ADDRESS = "qip.testing.address=http://testing-service:8080";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EndpointMockTestingService.class);

    @Test
    void mockingIsOffWhenTheFlagIsNotSet() {
        runner.withPropertyValues(ADDRESS)
                .run(context -> assertEquals(0, context.getBeanNamesForType(TestingService.class).length));
    }

    @Test
    void mockingIsOffWhenTheFlagIsFalse() {
        runner.withPropertyValues(ADDRESS, "qip.testing.enabled=false")
                .run(context -> assertEquals(0, context.getBeanNamesForType(TestingService.class).length));
    }

    @Test
    void mockingIsOnWhenTheFlagIsTrue() {
        runner.withPropertyValues(ADDRESS, "qip.testing.enabled=true")
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(TestingService.class).length);
                    assertInstanceOf(EndpointMockTestingService.class, context.getBean(TestingService.class));
                });
    }

    // The keys the condition and the @Value read have to be the keys the shipped application.yml writes.
    @Test
    void theShippedConfigurationKeepsMockingOff() throws IOException {
        assertEquals("${TESTING_SERVICE_ENABLED:false}", shippedProperty("qip.testing.enabled"));
        assertEquals("${TESTING_SERVICE_ADDRESS:http://testing-service:8080}", shippedProperty("qip.testing.address"));
    }

    private static Object shippedProperty(String name) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        Object value = sources.stream()
                .map(source -> source.getProperty(name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        assertNotNull(value, "application.yml does not declare " + name);
        return value;
    }
}

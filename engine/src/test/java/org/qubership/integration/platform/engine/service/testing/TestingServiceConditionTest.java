package org.qubership.integration.platform.engine.service.testing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}

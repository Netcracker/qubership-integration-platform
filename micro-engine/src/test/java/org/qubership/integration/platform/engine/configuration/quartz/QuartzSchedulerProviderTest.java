package org.qubership.integration.platform.engine.configuration.quartz;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QuartzSchedulerProviderTest {

    @Test
    void shouldNameSchedulerAfterDomain() {
        assertEquals("engine-orders",
                QuartzSchedulerProvider.getSchedulerProperties("orders").getProperty("org.quartz.scheduler.instanceName"));
    }
}

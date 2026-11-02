package org.qubership.integration.platform.engine.configuration.quartz;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QuartzSchedulerProviderTest {

    @Test
    void shouldNameTheSchedulerClusterAfterTheConfiguredName() {
        Properties properties = QuartzSchedulerProvider.getSchedulerProperties("cip-engine-orders");

        assertEquals("cip-engine-orders", properties.getProperty("org.quartz.scheduler.instanceName"));
        assertEquals("true", properties.getProperty("org.quartz.jobStore.isClustered"));
    }
}

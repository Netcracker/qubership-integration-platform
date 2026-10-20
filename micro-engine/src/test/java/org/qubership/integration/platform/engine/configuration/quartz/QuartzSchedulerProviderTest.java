package org.qubership.integration.platform.engine.configuration.quartz;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QuartzSchedulerProviderTest {

    @Test
    void shouldKeepJobsInMemoryOutsideAnyCluster() {
        Properties properties = QuartzSchedulerProvider.getSchedulerProperties();

        assertEquals("org.quartz.simpl.RAMJobStore", properties.getProperty("org.quartz.jobStore.class"));
        assertNull(properties.getProperty("org.quartz.jobStore.isClustered"));
    }
}

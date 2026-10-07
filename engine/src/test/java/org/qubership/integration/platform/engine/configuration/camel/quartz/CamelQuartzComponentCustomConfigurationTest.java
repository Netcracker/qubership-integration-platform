package org.qubership.integration.platform.engine.configuration.camel.quartz;

import org.apache.camel.component.quartz.QuartzComponent;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerProxy;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CamelQuartzComponentCustomConfigurationTest {

    // The customizer used to call the factory directly, outside the lock that serializes scheduler creation.
    @Test
    void givesTheComponentTheSchedulerFromTheService() throws Exception {
        QuartzSchedulerService service = mock(QuartzSchedulerService.class);
        StdSchedulerProxy scheduler = mock(StdSchedulerProxy.class);
        when(service.getSchedulerProxy()).thenReturn(scheduler);
        CamelQuartzComponentCustomConfiguration configuration = new CamelQuartzComponentCustomConfiguration(service);
        ReflectionTestUtils.setField(configuration, "threadPoolCount", "10");
        QuartzComponent component = new QuartzComponent();

        configuration.quartzComponentCustomizer().configure("quartz", component);

        assertSame(scheduler, component.getScheduler());
    }
}

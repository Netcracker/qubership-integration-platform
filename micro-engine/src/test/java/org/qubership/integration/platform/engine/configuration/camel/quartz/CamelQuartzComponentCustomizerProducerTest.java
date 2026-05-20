package org.qubership.integration.platform.engine.configuration.camel.quartz;

import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.apache.camel.component.quartz.QuartzComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.scheduler.SchedulerProxy;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "qip.camel.component.quartz.thread-pool-count", value = "10")
class CamelQuartzComponentCustomizerProducerTest {

    @Inject
    CamelQuartzComponentCustomizerProducer producer;

    @InjectMock
    QuartzSchedulerService quartzSchedulerService;

    @Test
    void shouldCreateCustomizerAndApplyQuartzConfiguration() {
        SchedulerProxy scheduler = mock(SchedulerProxy.class);
        when(quartzSchedulerService.getSchedulerProxy()).thenReturn(scheduler);

        ComponentCustomizer customizer = producer.quartzComponentCustomizer();
        QuartzComponent component = new QuartzComponent();

        assertNotNull(customizer);

        customizer.configure("quartz", component);

        assertSame(scheduler, component.getScheduler());
        assertFalse(component.isPrefixInstanceName());
        assertFalse(component.isEnableJmx());
        assertEquals(
                Map.of(CamelQuartzComponentCustomizerProducer.THREAD_POOL_COUNT_PROP, "10"),
                component.getProperties()
        );
    }
}

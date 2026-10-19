package org.qubership.integration.platform.engine.camel.listeners;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.impl.event.CamelContextStartedEvent;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RemoveStaleSchedulerJobsListenerTest {

    @Mock
    private QuartzSchedulerService quartzSchedulerService;

    @Mock
    private CamelContext camelContext;

    @Mock
    private Route route;

    @Mock
    private CamelEvent camelEvent;

    private RemoveStaleSchedulerJobsListener listener;

    @BeforeEach
    void setUp() {
        listener = new RemoveStaleSchedulerJobsListener();
        listener.quartzSchedulerService = quartzSchedulerService;
    }

    @Test
    void shouldRemoveJobsNotOwnedByContextRoutesWhenCamelContextStarted() {
        when(camelContext.getRoutes()).thenReturn(List.of(route));

        listener.notify(new CamelContextStartedEvent(camelContext));

        verify(quartzSchedulerService).removeJobsNotOwnedBy(List.of(route));
    }

    @Test
    void shouldIgnoreOtherCamelEvents() {
        listener.notify(camelEvent);

        verifyNoInteractions(quartzSchedulerService);
    }
}

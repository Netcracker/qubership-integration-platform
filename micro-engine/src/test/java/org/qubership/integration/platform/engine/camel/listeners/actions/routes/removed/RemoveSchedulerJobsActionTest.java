package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RemoveSchedulerJobsActionTest {

    @Mock
    private QuartzSchedulerService quartzSchedulerService;

    @Mock
    private CamelEvent.RouteRemovedEvent event;

    @Mock
    private Route route;

    private RemoveSchedulerJobsAction action;

    @BeforeEach
    void setUp() {
        action = new RemoveSchedulerJobsAction();
        action.quartzSchedulerService = quartzSchedulerService;
    }

    @Test
    void shouldRemoveSchedulerJobsForRemovedRoute() throws Exception {
        when(event.getRoute()).thenReturn(route);

        action.process(event);

        verify(quartzSchedulerService).removeSchedulerJobs(route);
    }

    @Test
    void shouldPropagateExceptionWhenSchedulerJobsRemovalFails() {
        RuntimeException exception = new RuntimeException("Failed to remove scheduler jobs");

        when(event.getRoute()).thenReturn(route);
        doThrow(exception).when(quartzSchedulerService).removeSchedulerJobs(route);

        RuntimeException result = assertThrows(RuntimeException.class, () -> action.process(event));

        assertSame(exception, result);
    }
}

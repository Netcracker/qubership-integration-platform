package org.qubership.integration.platform.engine.camel.listeners.actions.routes.started;

import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CommitSchedulerJobsActionTest {

    @Mock
    private QuartzSchedulerService quartzSchedulerService;

    @Mock
    private CamelEvent.RouteStartedEvent event;

    private CommitSchedulerJobsAction action;

    @BeforeEach
    void setUp() {
        action = new CommitSchedulerJobsAction();
        action.quartzSchedulerService = quartzSchedulerService;
    }

    @Test
    void shouldCommitScheduledJobsWhenRouteStarted() throws Exception {
        action.process(event);

        verify(quartzSchedulerService).commitScheduledJobs();
    }
}

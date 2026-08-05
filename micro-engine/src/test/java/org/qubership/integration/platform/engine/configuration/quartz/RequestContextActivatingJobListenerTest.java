package org.qubership.integration.platform.engine.configuration.quartz;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RequestContextActivatingJobListenerTest {

    private RequestContextActivatingJobListener listener;

    @Mock
    ArcContainer arcContainer;

    @Mock
    ManagedContext requestContext;

    @Mock
    JobExecutionContext jobExecutionContext;

    private MockedStatic<Arc> arcMockedStatic;

    @BeforeEach
    void setUp() {
        listener = new RequestContextActivatingJobListener();
        arcMockedStatic = mockStatic(Arc.class);
        arcMockedStatic.when(Arc::container).thenReturn(arcContainer);
        when(arcContainer.requestContext()).thenReturn(requestContext);
    }

    @AfterEach
    void tearDown() {
        arcMockedStatic.close();
    }

    @Test
    void shouldActivateRequestContextWhenJobIsAboutToBeExecutedAndContextIsNotActive() {
        when(requestContext.isActive()).thenReturn(false);

        listener.jobToBeExecuted(jobExecutionContext);

        verify(requestContext).activate();
    }

    @Test
    void shouldNotActivateRequestContextWhenJobIsAboutToBeExecutedAndContextIsAlreadyActive() {
        when(requestContext.isActive()).thenReturn(true);

        listener.jobToBeExecuted(jobExecutionContext);

        verify(requestContext, never()).activate();
    }

    @Test
    void shouldTerminateRequestContextWhenJobWasExecutedAndContextIsActive() {
        when(requestContext.isActive()).thenReturn(true);

        listener.jobWasExecuted(jobExecutionContext, null);

        verify(requestContext).terminate();
    }

    @Test
    void shouldNotTerminateRequestContextWhenJobWasExecutedAndContextIsNotActive() {
        when(requestContext.isActive()).thenReturn(false);

        listener.jobWasExecuted(jobExecutionContext, null);

        verify(requestContext, never()).terminate();
    }

    @Test
    void shouldTerminateRequestContextWhenJobExecutionIsVetoedAndContextIsActive() {
        when(requestContext.isActive()).thenReturn(true);

        listener.jobExecutionVetoed(jobExecutionContext);

        verify(requestContext).terminate();
    }

    @Test
    void shouldSwallowExceptionThrownWhileTerminatingRequestContext() {
        when(requestContext.isActive()).thenReturn(true);
        doThrowOnTerminate();

        listener.jobWasExecuted(jobExecutionContext, null);

        verify(requestContext).terminate();
    }

    private void doThrowOnTerminate() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(requestContext).terminate();
    }
}

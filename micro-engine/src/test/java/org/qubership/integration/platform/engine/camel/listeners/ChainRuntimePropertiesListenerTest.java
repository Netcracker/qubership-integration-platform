package org.qubership.integration.platform.engine.camel.listeners;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.k.Runtime;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.scheduler.TasksScheduler;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainRuntimePropertiesListenerTest {

    @Mock
    private Runtime runtime;

    @Mock
    private CDI<Object> cdi;

    @Mock
    private Instance<TasksScheduler> schedulerInstance;

    @Mock
    private TasksScheduler scheduler;

    private final ChainRuntimePropertiesListener listener = new ChainRuntimePropertiesListener();

    @Test
    void shouldReadRuntimePropertiesInStartingPhase() {
        when(cdi.select(TasksScheduler.class)).thenReturn(schedulerInstance);
        when(schedulerInstance.get()).thenReturn(scheduler);
        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            cdiStatic.when(CDI::current).thenReturn(cdi);

            assertTrue(listener.accept(Runtime.Phase.Starting, runtime));
        }

        verify(scheduler).checkRuntimeDeploymentProperties();
    }

    @Test
    void shouldBeRegisteredWithCamelK() {
        assertTrue(ServiceLoader.load(Runtime.Listener.class).stream()
                .anyMatch(provider -> provider.type() == ChainRuntimePropertiesListener.class));
    }
}

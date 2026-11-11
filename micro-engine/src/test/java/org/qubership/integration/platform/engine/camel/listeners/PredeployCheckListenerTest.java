package org.qubership.integration.platform.engine.camel.listeners;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.k.Runtime;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.predeploy.PredeployCheckService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PredeployCheckListenerTest {

    @Mock
    private Runtime runtime;

    @Mock
    private CamelContext camelContext;

    @Mock
    private CDI<Object> cdi;

    @Mock
    private Instance<PredeployCheckService> serviceInstance;

    @Mock
    private PredeployCheckService service;

    private final PredeployCheckListener listener = new PredeployCheckListener();

    @Test
    void shouldCheckRoutesInStartingPhase() throws Exception {
        givenService();
        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            cdiStatic.when(CDI::current).thenReturn(cdi);

            assertTrue(listener.accept(Runtime.Phase.Starting, runtime));
        }

        verify(service).checkRoutes(camelContext);
    }

    @Test
    void shouldFailStartupWhenCheckFails() throws Exception {
        givenService();
        IllegalStateException failure = new IllegalStateException("Kafka topics (orders) not found");
        doThrow(failure).when(service).checkRoutes(camelContext);

        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            cdiStatic.when(CDI::current).thenReturn(cdi);

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> listener.accept(Runtime.Phase.Starting, runtime));

            assertSame(failure, thrown.getCause());
        }
    }

    private void givenService() {
        when(runtime.getCamelContext()).thenReturn(camelContext);
        when(cdi.select(PredeployCheckService.class)).thenReturn(serviceInstance);
        when(serviceInstance.get()).thenReturn(service);
    }
}

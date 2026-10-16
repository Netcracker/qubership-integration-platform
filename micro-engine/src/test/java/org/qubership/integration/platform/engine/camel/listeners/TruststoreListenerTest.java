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
import org.qubership.integration.platform.engine.configuration.TruststoreConfiguration;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TruststoreListenerTest {

    @Mock
    private Runtime runtime;

    @Mock
    private CDI<Object> cdi;

    @Mock
    private Instance<TruststoreConfiguration> truststoreInstance;

    @Mock
    private TruststoreConfiguration truststore;

    private final TruststoreListener listener = new TruststoreListener();

    @Test
    void shouldBuildTruststoreInConfigureContextPhase() {
        when(cdi.select(TruststoreConfiguration.class)).thenReturn(truststoreInstance);
        when(truststoreInstance.get()).thenReturn(truststore);
        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            cdiStatic.when(CDI::current).thenReturn(cdi);

            assertTrue(listener.accept(Runtime.Phase.ConfigureContext, runtime));
        }

        verify(truststore).buildTruststore();
    }

    @Test
    void shouldBeRegisteredWithCamelK() {
        assertTrue(ServiceLoader.load(Runtime.Listener.class).stream()
                .anyMatch(provider -> provider.type() == TruststoreListener.class));
    }
}

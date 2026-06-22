package org.qubership.integration.platform.engine.registry;

import org.apache.camel.http.common.DefaultHttpRegistry;
import org.apache.camel.http.common.HttpConsumer;
import org.apache.camel.http.common.HttpRegistry;
import org.apache.camel.http.common.HttpRegistryProvider;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GatewayHttpRegistryTest {

    private static final String SERVLET_NAME = "gateway-servlet";
    private static final String ANOTHER_SERVLET_NAME = "another-servlet";

    @Mock
    private HttpRegistry httpRegistry;

    @Mock
    private HttpConsumer httpConsumer;

    @Mock
    private HttpRegistryProvider httpRegistryProvider;

    @Mock
    private HttpRegistryProvider resolvedHttpRegistryProvider;

    @Test
    void shouldCreateDelegateRegistryUsingConfiguredServletName() {
        try (MockedStatic<DefaultHttpRegistry> defaultHttpRegistry = mockStatic(DefaultHttpRegistry.class)) {
            defaultHttpRegistry.when(() -> DefaultHttpRegistry.getHttpRegistry(SERVLET_NAME))
                .thenReturn(httpRegistry);

            new GatewayHttpRegistry(SERVLET_NAME);

            defaultHttpRegistry.verify(() -> DefaultHttpRegistry.getHttpRegistry(SERVLET_NAME));
        }
    }

    @Test
    void shouldRegisterHttpConsumerUsingDelegateRegistry() {
        GatewayHttpRegistry gatewayHttpRegistry = gatewayHttpRegistry();

        gatewayHttpRegistry.register(httpConsumer);

        verify(httpRegistry).register(httpConsumer);
    }

    @Test
    void shouldRegisterHttpRegistryProviderUsingDelegateRegistry() {
        GatewayHttpRegistry gatewayHttpRegistry = gatewayHttpRegistry();

        gatewayHttpRegistry.register(httpRegistryProvider);

        verify(httpRegistry).register(httpRegistryProvider);
    }

    @Test
    void shouldUnregisterHttpConsumerUsingDelegateRegistry() {
        GatewayHttpRegistry gatewayHttpRegistry = gatewayHttpRegistry();

        gatewayHttpRegistry.unregister(httpConsumer);

        verify(httpRegistry).unregister(httpConsumer);
    }

    @Test
    void shouldUnregisterHttpRegistryProviderUsingDelegateRegistry() {
        GatewayHttpRegistry gatewayHttpRegistry = gatewayHttpRegistry();

        gatewayHttpRegistry.unregister(httpRegistryProvider);

        verify(httpRegistry).unregister(httpRegistryProvider);
    }

    @Test
    void shouldGetCamelServletUsingDelegateRegistry() {
        GatewayHttpRegistry gatewayHttpRegistry = gatewayHttpRegistry();

        when(httpRegistry.getCamelServlet(ANOTHER_SERVLET_NAME)).thenReturn(resolvedHttpRegistryProvider);

        HttpRegistryProvider result = gatewayHttpRegistry.getCamelServlet(ANOTHER_SERVLET_NAME);

        assertSame(resolvedHttpRegistryProvider, result);
        verify(httpRegistry).getCamelServlet(ANOTHER_SERVLET_NAME);
    }

    private GatewayHttpRegistry gatewayHttpRegistry() {
        try (MockedStatic<DefaultHttpRegistry> defaultHttpRegistry = mockStatic(DefaultHttpRegistry.class)) {
            defaultHttpRegistry.when(() -> DefaultHttpRegistry.getHttpRegistry(SERVLET_NAME))
                .thenReturn(httpRegistry);

            return new GatewayHttpRegistry(SERVLET_NAME);
        }
    }
}

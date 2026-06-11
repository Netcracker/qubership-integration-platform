package org.qubership.integration.platform.engine.camel.dsl.errorhandling;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.camel.StartupErrorHandlingConfiguration;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ErrorHandlerFactoryTest {

    @Mock
    private StartupErrorHandlingConfiguration configuration;

    @Mock
    private Resource resource;

    @Mock
    private CamelContext camelContext;

    private ErrorHandlerFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ErrorHandlerFactory();
        factory.configuration = configuration;
    }

    @Test
    void shouldThrowOriginalExceptionWhenRouteLoadingErrorsAreNotIgnored() throws Exception {
        Exception routeLoadingException = new Exception("Failed to load route");

        when(configuration.ignoreRouteLoadingErrors()).thenReturn(false);

        ErrorHandler errorHandler = factory.getErrorHandler(resource);

        Exception thrownException = assertThrows(
            Exception.class,
            () -> errorHandler.handleError(camelContext, routeLoadingException)
        );

        assertSame(routeLoadingException, thrownException);
        verify(resource, never()).getURL();
    }

    @Test
    void shouldNotThrowExceptionWhenRouteLoadingErrorsAreIgnored() throws Exception {
        Exception routeLoadingException = new Exception("Failed to load route");

        when(configuration.ignoreRouteLoadingErrors()).thenReturn(true);
        when(resource.getURL()).thenReturn(new URL("file:/tmp/routes.xml"));

        ErrorHandler errorHandler = factory.getErrorHandler(resource);

        assertDoesNotThrow(() -> errorHandler.handleError(camelContext, routeLoadingException));

        verify(resource).getURL();
    }

    @Test
    void shouldPropagateResourceUrlExceptionWhenRouteLoadingErrorsAreIgnored() throws Exception {
        Exception routeLoadingException = new Exception("Failed to load route");
        Exception urlException = new RuntimeException("Failed to read resource URL");

        when(configuration.ignoreRouteLoadingErrors()).thenReturn(true);
        when(resource.getURL()).thenThrow(urlException);

        ErrorHandler errorHandler = factory.getErrorHandler(resource);

        RuntimeException thrownException = assertThrows(
            RuntimeException.class,
            () -> errorHandler.handleError(camelContext, routeLoadingException)
        );

        assertSame(urlException, thrownException);
    }
}

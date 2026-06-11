package org.qubership.integration.platform.engine.camel.dsl;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.RouteBuilderLifecycleStrategy;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.Resource;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.dsl.errorhandling.ErrorHandler;
import org.qubership.integration.platform.engine.camel.dsl.errorhandling.ErrorHandlerFactory;
import org.qubership.integration.platform.engine.camel.dsl.notification.SourceProcessingNotifier;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.ResourceContentPreprocessingService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomXmlRoutesBuilderLoaderTest {

    private static final String VALID_XML_ROUTES = """
        <routes xmlns="http://camel.apache.org/schema/spring">
            <route id="test-route">
                <from uri="direct:start"/>
                <to uri="mock:result"/>
            </route>
        </routes>
        """;

    @Mock
    private ResourceContentPreprocessingService preprocessingService;

    @Mock
    private ErrorHandlerFactory errorHandlerFactory;

    @Mock
    private ErrorHandler errorHandler;

    @Mock
    private SourceProcessingNotifier sourceProcessingNotifier;

    @Mock
    private Resource resource;

    @Mock
    private RouteBuilder delegateRouteBuilder;

    @Mock
    private CamelContext camelContext;

    @Mock
    private RouteBuilderLifecycleStrategy lifecycleStrategy;

    private CustomXmlRoutesBuilderLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        loader = new CustomXmlRoutesBuilderLoader();
        FieldUtils.writeField(loader, "preprocessingService", preprocessingService, true);
        FieldUtils.writeField(loader, "errorHandlerFactory", errorHandlerFactory, true);
        FieldUtils.writeField(loader, "sourceProcessingNotifier", sourceProcessingNotifier, true);
    }

    @Test
    void shouldPreprocessInputAndReturnSimpleResourceWithOriginalSchemeAndLocation() throws Exception {
        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream("original content".getBytes()));
        when(resource.getScheme()).thenReturn("classpath");
        when(resource.getLocation()).thenReturn("routes/test.xml");
        when(preprocessingService.preprocess("original content")).thenReturn("processed content");

        Resource result = preprocessInput(resource);

        assertEquals("classpath", result.getScheme());
        assertEquals("routes/test.xml", result.getLocation());
        assertEquals("processed content", new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldDelegateConfigureToWrappedRouteBuilder() throws Exception {
        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        wrappedRouteBuilder.configure();

        verify(delegateRouteBuilder).configure();
    }

    @Test
    void shouldDelegateLifecycleInterceptorToWrappedRouteBuilder() throws Exception {
        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        wrappedRouteBuilder.addLifecycleInterceptor(lifecycleStrategy);

        verify(delegateRouteBuilder).addLifecycleInterceptor(lifecycleStrategy);
    }

    @Test
    void shouldAddRoutesToCamelContextAndNotifySourceLoaded() throws Exception {
        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        wrappedRouteBuilder.addRoutesToCamelContext(camelContext);

        verify(delegateRouteBuilder).addRoutesToCamelContext(camelContext);
        verify(sourceProcessingNotifier).notifySourceLoaded(resource);
    }

    @Test
    void shouldHandleErrorWhenAddRoutesToCamelContextFails() throws Exception {
        Exception exception = new Exception("Failed to add routes");

        doThrow(exception).when(delegateRouteBuilder).addRoutesToCamelContext(camelContext);
        when(errorHandlerFactory.getErrorHandler(resource)).thenReturn(errorHandler);

        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        wrappedRouteBuilder.addRoutesToCamelContext(camelContext);

        verify(delegateRouteBuilder).addRoutesToCamelContext(camelContext);
        verify(sourceProcessingNotifier).notifySourceLoadFailed(resource, exception);
        verify(errorHandler).handleError(camelContext, exception);
    }

    @Test
    void shouldAddTemplatedRoutesToCamelContextAndNotifySourceLoaded() throws Exception {
        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        wrappedRouteBuilder.addTemplatedRoutesToCamelContext(camelContext);

        verify(delegateRouteBuilder).addTemplatedRoutesToCamelContext(camelContext);
        verify(sourceProcessingNotifier).notifySourceLoaded(resource);
    }

    @Test
    void shouldHandleErrorWhenAddTemplatedRoutesToCamelContextFails() throws Exception {
        Exception exception = new Exception("Failed to add templated routes");

        doThrow(exception).when(delegateRouteBuilder).addTemplatedRoutesToCamelContext(camelContext);
        when(errorHandlerFactory.getErrorHandler(resource)).thenReturn(errorHandler);

        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        wrappedRouteBuilder.addTemplatedRoutesToCamelContext(camelContext);

        verify(delegateRouteBuilder).addTemplatedRoutesToCamelContext(camelContext);
        verify(sourceProcessingNotifier).notifySourceLoadFailed(resource, exception);
        verify(errorHandler).handleError(camelContext, exception);
    }

    @Test
    void shouldUpdateRoutesToCamelContextNotifySourceLoadedAndReturnIdentifiers() throws Exception {
        Set<String> routeIds = Set.of("first-route", "second-route");

        when(delegateRouteBuilder.updateRoutesToCamelContext(camelContext)).thenReturn(routeIds);

        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        Set<String> result = wrappedRouteBuilder.updateRoutesToCamelContext(camelContext);

        assertEquals(routeIds, result);
        verify(delegateRouteBuilder).updateRoutesToCamelContext(camelContext);
        verify(sourceProcessingNotifier).notifySourceLoaded(resource);
    }

    @Test
    void shouldHandleErrorAndReturnEmptySetWhenUpdateRoutesToCamelContextFails() throws Exception {
        Exception exception = new Exception("Failed to update routes");

        when(delegateRouteBuilder.updateRoutesToCamelContext(camelContext)).thenThrow(exception);
        when(errorHandlerFactory.getErrorHandler(resource)).thenReturn(errorHandler);

        RouteBuilder wrappedRouteBuilder = wrapRouteBuilder(delegateRouteBuilder);

        Set<String> result = wrappedRouteBuilder.updateRoutesToCamelContext(camelContext);

        assertTrue(result.isEmpty());
        verify(delegateRouteBuilder).updateRoutesToCamelContext(camelContext);
        verify(sourceProcessingNotifier).notifySourceLoadFailed(resource, exception);
        verify(errorHandler).handleError(camelContext, exception);
    }

    @Test
    void shouldHandleErrorWhenPreParseRouteFails() throws Exception {
        RuntimeException exception = new RuntimeException("Failed to pre-parse route");

        doThrow(exception).when(sourceProcessingNotifier).notifySourceProcessingStarted(resource);
        when(errorHandlerFactory.getErrorHandler(resource)).thenReturn(errorHandler);

        loader.preParseRoute(resource);

        verify(sourceProcessingNotifier).notifySourceProcessingStarted(resource);
        verify(sourceProcessingNotifier).notifySourceLoadFailed(resource, exception);
        verify(errorHandler).handleError(isNull(), eq(exception));
    }

    @Test
    void shouldHandleErrorAndReturnNullWhenDoLoadRouteBuilderFails() throws Exception {
        Exception exception = new Exception("Failed to preprocess content");

        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream("original content".getBytes()));
        when(preprocessingService.preprocess("original content")).thenThrow(exception);
        when(errorHandlerFactory.getErrorHandler(resource)).thenReturn(errorHandler);

        RouteBuilder result = loader.doLoadRouteBuilder(resource);

        assertNull(result);
        verify(sourceProcessingNotifier).notifySourceLoadFailed(resource, exception);
        verify(errorHandler).handleError(isNull(), eq(exception));
    }

    @Test
    void shouldGetPreprocessingServiceFromCdiWhenItIsNotInitialized() throws Exception {
        CustomXmlRoutesBuilderLoader loaderWithoutDependencies = new CustomXmlRoutesBuilderLoader();

        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            CDI<Object> cdi = mock(CDI.class);
            Instance<ResourceContentPreprocessingService> instance = mock(Instance.class);

            cdiStatic.when(CDI::current).thenReturn(cdi);
            when(cdi.select(ResourceContentPreprocessingService.class)).thenReturn(instance);
            when(instance.get()).thenReturn(preprocessingService);

            ResourceContentPreprocessingService result = getPreprocessingService(loaderWithoutDependencies);

            assertSame(preprocessingService, result);
        }
    }

    @Test
    void shouldGetErrorHandlerFactoryFromCdiWhenItIsNotInitialized() throws Exception {
        CustomXmlRoutesBuilderLoader loaderWithoutDependencies = new CustomXmlRoutesBuilderLoader();

        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            CDI<Object> cdi = mock(CDI.class);
            Instance<ErrorHandlerFactory> instance = mock(Instance.class);

            cdiStatic.when(CDI::current).thenReturn(cdi);
            when(cdi.select(ErrorHandlerFactory.class)).thenReturn(instance);
            when(instance.get()).thenReturn(errorHandlerFactory);

            ErrorHandlerFactory result = getErrorHandlerFactory(loaderWithoutDependencies);

            assertSame(errorHandlerFactory, result);
        }
    }

    @Test
    void shouldGetSourceProcessingNotifierFromCdiWhenItIsNotInitialized() throws Exception {
        CustomXmlRoutesBuilderLoader loaderWithoutDependencies = new CustomXmlRoutesBuilderLoader();

        try (MockedStatic<CDI> cdiStatic = mockStatic(CDI.class)) {
            CDI<Object> cdi = mock(CDI.class);
            Instance<SourceProcessingNotifier> instance = mock(Instance.class);

            cdiStatic.when(CDI::current).thenReturn(cdi);
            when(cdi.select(SourceProcessingNotifier.class)).thenReturn(instance);
            when(instance.get()).thenReturn(sourceProcessingNotifier);

            SourceProcessingNotifier result = getSourceProcessingNotifier(loaderWithoutDependencies);

            assertSame(sourceProcessingNotifier, result);
        }
    }

    @Test
    void shouldNotifySourceProcessingStartedWhenPreParseRouteSucceeds() throws Exception {
        try (DefaultCamelContext defaultCamelContext = new DefaultCamelContext()) {
            loader.setCamelContext(defaultCamelContext);
            Resource xmlResource = xmlResource();

            loader.preParseRoute(xmlResource);

            verify(sourceProcessingNotifier).notifySourceProcessingStarted(xmlResource);
        }
    }

    @Test
    void shouldLoadPreprocessedRouteBuilderWhenInputIsValid() throws Exception {
        try (DefaultCamelContext defaultCamelContext = new DefaultCamelContext()) {
            loader.setCamelContext(defaultCamelContext);
            Resource xmlResource = xmlResource();

            when(preprocessingService.preprocess(VALID_XML_ROUTES)).thenReturn(VALID_XML_ROUTES);

            RouteBuilder result = loader.doLoadRouteBuilder(xmlResource);

            assertNotNull(result);
        }
    }

    @Test
    void shouldLoadPreprocessedRouteBuilderAndNotifySourceLoadedWhenRoutesAreAdded() throws Exception {
        try (DefaultCamelContext defaultCamelContext = new DefaultCamelContext()) {
            loader.setCamelContext(defaultCamelContext);
            Resource xmlResource = xmlResource();

            when(preprocessingService.preprocess(VALID_XML_ROUTES)).thenReturn(VALID_XML_ROUTES);

            RouteBuilder result = loader.doLoadRouteBuilder(xmlResource);
            result.addRoutesToCamelContext(defaultCamelContext);

            assertNotNull(result);
            verify(sourceProcessingNotifier).notifySourceLoaded(xmlResource);
        }
    }

    private Resource preprocessInput(Resource input) throws Exception {
        Method method = CustomXmlRoutesBuilderLoader.class.getDeclaredMethod("preprocessInput", Resource.class);
        method.setAccessible(true);
        return (Resource) method.invoke(loader, input);
    }

    private RouteBuilder wrapRouteBuilder(RouteBuilder builder) throws Exception {
        Method method = CustomXmlRoutesBuilderLoader.class.getDeclaredMethod(
            "wrapRouteBuilder",
            Resource.class,
            RouteBuilder.class
        );
        method.setAccessible(true);
        return (RouteBuilder) method.invoke(loader, resource, builder);
    }

    private static ResourceContentPreprocessingService getPreprocessingService(
        CustomXmlRoutesBuilderLoader loader
    ) throws Exception {
        Method method = CustomXmlRoutesBuilderLoader.class.getDeclaredMethod("getPreprocessingService");
        method.setAccessible(true);
        return (ResourceContentPreprocessingService) method.invoke(loader);
    }

    private static ErrorHandlerFactory getErrorHandlerFactory(CustomXmlRoutesBuilderLoader loader) throws Exception {
        Method method = CustomXmlRoutesBuilderLoader.class.getDeclaredMethod("getErrorHandlerFactory");
        method.setAccessible(true);
        return (ErrorHandlerFactory) method.invoke(loader);
    }

    private static SourceProcessingNotifier getSourceProcessingNotifier(CustomXmlRoutesBuilderLoader loader)
        throws Exception {
        Method method = CustomXmlRoutesBuilderLoader.class.getDeclaredMethod("getSourceProcessingNotifier");
        method.setAccessible(true);
        return (SourceProcessingNotifier) method.invoke(loader);
    }

    private static Resource xmlResource() {
        return new SimpleResource(
            "classpath",
            "routes/test-route.xml",
            CustomXmlRoutesBuilderLoaderTest.VALID_XML_ROUTES.getBytes(StandardCharsets.UTF_8)
        );
    }
}

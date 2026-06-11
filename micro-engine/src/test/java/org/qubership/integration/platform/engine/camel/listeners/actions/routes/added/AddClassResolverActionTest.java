package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.ClassResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.QipCustomClassResolver;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.ServiceCallInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.service.ExternalLibraryService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.SERVICE_CALL_ELEMENT;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class AddClassResolverActionTest {

    private static final String CHAIN_ID = "05861946-9f04-458e-a859-8ed3588d5a25";
    private static final String SERVICE_CALL_ELEMENT_ID = "e3d6f107-5344-4a5e-af15-9d695ac96ed0";
    private static final String SPECIFICATION_ID = "7f969279-ca8f-4c1d-8fc6-2aafbd1dec42";

    @Mock
    private ExternalLibraryService externalLibraryService;

    @Mock
    private CamelEvent.RouteAddedEvent event;

    @Mock
    private Route route;

    @Mock
    private CamelContext camelContext;

    @Mock
    private DeploymentInfo deploymentInfo;

    @Mock
    private ChainInfo chainInfo;

    private AddClassResolverAction action;

    @BeforeEach
    void setUp() {
        action = new AddClassResolverAction();
        action.externalLibraryService = externalLibraryService;

        when(event.getRoute()).thenReturn(route);
    }

    @Test
    void shouldDoNothingWhenRouteAlreadyHasClassResolver() throws Exception {
        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.hasBean(route, ClassResolver.class)).thenReturn(true);

            action.process(event);

            verifyNoInteractions(externalLibraryService);
            metadataUtil.verify(() -> MetadataUtil.hasBean(route, ClassResolver.class));
            metadataUtil.verifyNoMoreInteractions();
        }
    }

    @Test
    void shouldAddClassResolverWhenRouteDoesNotHaveClassResolver() throws Exception {
        ElementInfo serviceCallElement = serviceCallElement();
        ServiceCallInfo serviceCallInfo = serviceCallInfo();
        ClassLoader applicationClassLoader = mock(ClassLoader.class);
        ClassLoader externalLibraryClassLoader = mock(ClassLoader.class);

        stubDeploymentInfo();
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getApplicationContextClassLoader()).thenReturn(applicationClassLoader);
        when(externalLibraryService.getClassLoaderForSpecifications(
            List.of(SPECIFICATION_ID),
            applicationClassLoader
        )).thenReturn(externalLibraryClassLoader);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.hasBean(route, ClassResolver.class)).thenReturn(false);
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(serviceCallElement));
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(
                route,
                SERVICE_CALL_ELEMENT_ID,
                ServiceCallInfo.class
            )).thenReturn(serviceCallInfo);

            action.process(event);

            ArgumentCaptor<ClassResolver> classResolverCaptor = ArgumentCaptor.forClass(ClassResolver.class);
            metadataUtil.verify(() -> MetadataUtil.addBean(
                eq(route),
                eq(ClassResolver.class),
                classResolverCaptor.capture()
            ));

            ClassResolver classResolver = classResolverCaptor.getValue();
            assertInstanceOf(QipCustomClassResolver.class, classResolver);
        }
    }

    @Test
    void shouldAddClassResolverWithEmptySpecificationIdsWhenServiceCallElementsAreMissing() throws Exception {
        ElementInfo nonServiceCallElement = elementWithType("script");
        ClassLoader applicationClassLoader = mock(ClassLoader.class);
        ClassLoader externalLibraryClassLoader = mock(ClassLoader.class);

        stubDeploymentInfo();
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getApplicationContextClassLoader()).thenReturn(applicationClassLoader);
        when(externalLibraryService.getClassLoaderForSpecifications(
            List.of(),
            applicationClassLoader
        )).thenReturn(externalLibraryClassLoader);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.hasBean(route, ClassResolver.class)).thenReturn(false);
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(nonServiceCallElement));

            action.process(event);

            metadataUtil.verify(() -> MetadataUtil.addBean(
                eq(route),
                eq(ClassResolver.class),
                any(ClassResolver.class)
            ));
        }
    }

    @Test
    void shouldCollectSpecificationIdsOnlyFromServiceCallElements() throws Exception {
        ElementInfo serviceCallElement = serviceCallElement();
        ElementInfo nonServiceCallElement = elementWithType("mapper");
        ServiceCallInfo serviceCallInfo = serviceCallInfo();
        ClassLoader applicationClassLoader = mock(ClassLoader.class);
        ClassLoader externalLibraryClassLoader = mock(ClassLoader.class);

        stubDeploymentInfo();
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getApplicationContextClassLoader()).thenReturn(applicationClassLoader);
        when(externalLibraryService.getClassLoaderForSpecifications(
            List.of(SPECIFICATION_ID),
            applicationClassLoader
        )).thenReturn(externalLibraryClassLoader);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.hasBean(route, ClassResolver.class)).thenReturn(false);
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route))
                .thenReturn(Stream.of(serviceCallElement, nonServiceCallElement));
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(
                route,
                SERVICE_CALL_ELEMENT_ID,
                ServiceCallInfo.class
            )).thenReturn(serviceCallInfo);

            action.process(event);

            metadataUtil.verify(() -> MetadataUtil.addBean(
                eq(route),
                eq(ClassResolver.class),
                any(ClassResolver.class)
            ));
        }
    }

    @Test
    void shouldPropagateExceptionWhenExternalLibraryClassLoaderCannotBeCreated() throws Exception {
        ElementInfo serviceCallElement = serviceCallElement();
        ServiceCallInfo serviceCallInfo = serviceCallInfo();
        ClassLoader applicationClassLoader = mock(ClassLoader.class);
        RuntimeException exception = new RuntimeException("Failed to create class loader");

        stubDeploymentInfo();
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getApplicationContextClassLoader()).thenReturn(applicationClassLoader);
        when(externalLibraryService.getClassLoaderForSpecifications(
            List.of(SPECIFICATION_ID),
            applicationClassLoader
        )).thenThrow(exception);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.hasBean(route, ClassResolver.class)).thenReturn(false);
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(serviceCallElement));
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(
                route,
                SERVICE_CALL_ELEMENT_ID,
                ServiceCallInfo.class
            )).thenReturn(serviceCallInfo);

            RuntimeException result = assertThrows(RuntimeException.class, () -> action.process(event));

            assertSame(exception, result);
        }
    }

    private void stubDeploymentInfo() {
        when(deploymentInfo.getChain()).thenReturn(chainInfo);
        when(chainInfo.getId()).thenReturn(CHAIN_ID);
    }

    private static ElementInfo serviceCallElement() {
        ElementInfo elementInfo = mock(ElementInfo.class);
        when(elementInfo.getType()).thenReturn(SERVICE_CALL_ELEMENT);
        when(elementInfo.getSnapshotElementId()).thenReturn(AddClassResolverActionTest.SERVICE_CALL_ELEMENT_ID);
        return elementInfo;
    }

    private static ElementInfo elementWithType(String type) {
        ElementInfo elementInfo = mock(ElementInfo.class);
        when(elementInfo.getType()).thenReturn(type);
        return elementInfo;
    }

    private static ServiceCallInfo serviceCallInfo() {
        ServiceCallInfo serviceCallInfo = mock(ServiceCallInfo.class);
        when(serviceCallInfo.getSpecificationId()).thenReturn(AddClassResolverActionTest.SPECIFICATION_ID);
        return serviceCallInfo;
    }
}

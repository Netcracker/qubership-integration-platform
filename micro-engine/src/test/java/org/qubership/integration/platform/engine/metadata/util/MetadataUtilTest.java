package org.qubership.integration.platform.engine.metadata.util;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetadataUtilTest {

    private static final String GROUP_ID = "3b5b839e-74e6-42ee-ad64-25b91bd23c9a";
    private static final String ROUTE_ID = "21fe21c9-074a-43ce-92ad-c054feac4c6b";
    private static final String SNAPSHOT_ID = "f7b835b6-b31e-4e18-a872-356d955f7f19";
    private static final String ANOTHER_SNAPSHOT_ID = "89a72ef6-15e9-4c9e-a5d3-0495f62a3674";
    private static final String ELEMENT_ID = "ea229c20-1cf8-4aaf-9d87-f3721fb50635";

    @Mock
    private Route route;

    @Mock
    private Exchange exchange;

    @Mock
    private CamelContext camelContext;

    @Mock
    private Registry registry;

    @Mock
    private DeploymentInfo deploymentInfo;

    @Mock
    private DeploymentInfo anotherDeploymentInfo;

    @Mock
    private SnapshotInfo snapshotInfo;

    @Mock
    private ElementInfo elementInfo;

    @Mock
    private ElementInfo anotherElementInfo;

    @Mock
    private RouteRegistrationInfo routeRegistrationInfo;

    @Mock
    private RouteRegistrationInfo anotherRouteRegistrationInfo;

    @Test
    void shouldLookupBeanByRouteGroup() {
        stubRouteRegistry();
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(deploymentInfo);

        Optional<DeploymentInfo> result = MetadataUtil.lookupBean(route, DeploymentInfo.class);

        assertTrue(result.isPresent());
        assertSame(deploymentInfo, result.get());
    }

    @Test
    void shouldReturnEmptyWhenBeanIsMissing() {
        stubRouteRegistry();
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(null);

        Optional<DeploymentInfo> result = MetadataUtil.lookupBean(route, DeploymentInfo.class);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldGetBeanByRouteGroup() {
        stubRouteRegistry();
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(deploymentInfo);

        DeploymentInfo result = MetadataUtil.getBean(route, DeploymentInfo.class);

        assertSame(deploymentInfo, result);
    }

    @Test
    void shouldThrowBeanNotFoundExceptionWhenBeanIsMissing() {
        stubRouteRegistry();
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(null);

        BeanNotFoundException exception = assertThrows(
            BeanNotFoundException.class,
            () -> MetadataUtil.getBean(route, DeploymentInfo.class)
        );

        assertInstanceOf(BeanNotFoundException.class, exception);
    }

    @Test
    void shouldGetBeanFromExchangeRoute() {
        stubExchangeRouteResolution();
        when(route.getGroup()).thenReturn(GROUP_ID);
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(deploymentInfo);

        DeploymentInfo result = MetadataUtil.getBean(exchange, DeploymentInfo.class);

        assertSame(deploymentInfo, result);
    }

    @Test
    void shouldReturnTrueWhenRouteHasBean() {
        stubRouteRegistry();
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(deploymentInfo);

        boolean result = MetadataUtil.hasBean(route, DeploymentInfo.class);

        assertTrue(result);
    }

    @Test
    void shouldReturnFalseWhenRouteDoesNotHaveBean() {
        stubRouteRegistry();
        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(null);

        boolean result = MetadataUtil.hasBean(route, DeploymentInfo.class);

        assertFalse(result);
    }

    @Test
    void shouldAddBeanUsingRouteGroup() {
        stubRouteRegistry();

        MetadataUtil.addBean(route, DeploymentInfo.class, deploymentInfo);

        verify(registry).bind("DeploymentInfo-" + GROUP_ID, deploymentInfo);
    }

    @Test
    void shouldLookupBeanForElementFromCamelContext() {
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-" + ELEMENT_ID, ElementInfo.class))
            .thenReturn(elementInfo);

        Optional<ElementInfo> result = MetadataUtil.lookupBeanForElement(
            camelContext,
            ELEMENT_ID,
            ElementInfo.class
        );

        assertTrue(result.isPresent());
        assertSame(elementInfo, result.get());
    }

    @Test
    void shouldLookupBeanForElementFromRoute() {
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-" + ELEMENT_ID, ElementInfo.class))
            .thenReturn(elementInfo);

        Optional<ElementInfo> result = MetadataUtil.lookupBeanForElement(
            route,
            ELEMENT_ID,
            ElementInfo.class
        );

        assertTrue(result.isPresent());
        assertSame(elementInfo, result.get());
    }

    @Test
    void shouldLookupBeanForElementFromExchange() {
        stubExchangeRouteResolution();
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-" + ELEMENT_ID, ElementInfo.class))
            .thenReturn(elementInfo);

        Optional<ElementInfo> result = MetadataUtil.lookupBeanForElement(
            exchange,
            ELEMENT_ID,
            ElementInfo.class
        );

        assertTrue(result.isPresent());
        assertSame(elementInfo, result.get());
    }

    @Test
    void shouldGetBeanForElementFromRoute() {
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-" + ELEMENT_ID, ElementInfo.class))
            .thenReturn(elementInfo);

        ElementInfo result = MetadataUtil.getBeanForElement(route, ELEMENT_ID, ElementInfo.class);

        assertSame(elementInfo, result);
    }

    @Test
    void shouldGetBeanForElementFromExchange() {
        stubExchangeRouteResolution();
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-" + ELEMENT_ID, ElementInfo.class))
            .thenReturn(elementInfo);

        ElementInfo result = MetadataUtil.getBeanForElement(exchange, ELEMENT_ID, ElementInfo.class);

        assertSame(elementInfo, result);
    }

    @Test
    void shouldThrowBeanNotFoundExceptionWhenElementBeanIsMissing() {
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-" + ELEMENT_ID, ElementInfo.class))
            .thenReturn(null);

        BeanNotFoundException exception = assertThrows(
            BeanNotFoundException.class,
            () -> MetadataUtil.getBeanForElement(route, ELEMENT_ID, ElementInfo.class)
        );

        assertInstanceOf(BeanNotFoundException.class, exception);
    }

    @Test
    void shouldGetElementsInfoForDeploymentSnapshot() {
        stubRouteRegistry();
        stubDeploymentSnapshot();

        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(deploymentInfo);
        when(registry.findByType(ElementInfo.class)).thenReturn(Set.of(elementInfo, anotherElementInfo));
        when(elementInfo.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(anotherElementInfo.getSnapshotId()).thenReturn(ANOTHER_SNAPSHOT_ID);

        List<ElementInfo> result = MetadataUtil.getElementsInfo(route).toList();

        assertEquals(List.of(elementInfo), result);
    }

    @Test
    void shouldGetElementsInfoFromExchangeRoute() {
        stubExchangeRouteResolution();
        when(route.getGroup()).thenReturn(GROUP_ID);
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        stubDeploymentSnapshot();

        when(registry.lookupByNameAndType("DeploymentInfo-" + GROUP_ID, DeploymentInfo.class))
            .thenReturn(deploymentInfo);
        when(registry.findByType(ElementInfo.class)).thenReturn(Set.of(elementInfo));
        when(elementInfo.getSnapshotId()).thenReturn(SNAPSHOT_ID);

        List<ElementInfo> result = MetadataUtil.getElementsInfo(exchange).toList();

        assertEquals(List.of(elementInfo), result);
    }

    @Test
    void shouldGetRouteRegistrationInfoForSnapshot() {
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.findByType(RouteRegistrationInfo.class))
            .thenReturn(Set.of(routeRegistrationInfo, anotherRouteRegistrationInfo));
        when(routeRegistrationInfo.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(anotherRouteRegistrationInfo.getSnapshotId()).thenReturn(ANOTHER_SNAPSHOT_ID);

        Collection<RouteRegistrationInfo> result = MetadataUtil.getRouteRegistrationInfo(camelContext, SNAPSHOT_ID);

        assertEquals(List.of(routeRegistrationInfo), result);
    }

    private void stubRouteRegistry() {
        when(route.getGroup()).thenReturn(GROUP_ID);
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
    }

    private void stubExchangeRouteResolution() {
        when(exchange.getFromRouteId()).thenReturn(ROUTE_ID);
        when(exchange.getContext()).thenReturn(camelContext);
        when(camelContext.getRoute(ROUTE_ID)).thenReturn(route);
    }

    private void stubDeploymentSnapshot() {
        when(deploymentInfo.getSnapshot()).thenReturn(snapshotInfo);
        when(snapshotInfo.getId()).thenReturn(SNAPSHOT_ID);
    }
}

package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import jakarta.enterprise.inject.Instance;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.service.RouteRegistrationService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RouteRegisterActionTest {

    private static final String DEPLOYMENT_ID = "deployment-id";
    private static final String ANOTHER_DEPLOYMENT_ID = "another-deployment-id";
    private static final String DEPLOYMENT_NAME = "deployment-name";
    private static final String SNAPSHOT_ID = "f7b835b6-b31e-4e18-a872-356d955f7f19";
    private static final String ANOTHER_SNAPSHOT_ID = "89a72ef6-15e9-4c9e-a5d3-0495f62a3674";

    @Mock
    private Instance<RouteRegistrationService> routeRegistrationServiceInstance;

    @Mock
    private RouteRegistrationService routeRegistrationService;

    @Mock
    private CamelEvent.RouteAddedEvent event;

    @Mock
    private CamelEvent.RouteAddedEvent anotherEvent;

    @Mock
    private Route route;

    @Mock
    private Route anotherRoute;

    @Mock
    private CamelContext camelContext;

    @Mock
    private DeploymentInfo deploymentInfo;

    @Mock
    private DeploymentInfo anotherDeploymentInfo;

    @Mock
    private SnapshotInfo snapshotInfo;

    @Mock
    private SnapshotInfo anotherSnapshotInfo;

    @Mock
    private RouteRegistrationInfo routeRegistrationInfo;

    @Mock
    private RouteRegistrationInfo anotherRouteRegistrationInfo;

    @BeforeEach
    void setUp() {
        when(event.getRoute()).thenReturn(route);
    }

    @Test
    void shouldRegisterRoutesWhenDeploymentIsNotRegisteredAndServiceIsAvailable() throws Exception {
        RouteRegisterAction action = actionWithRouteRegistrationService();
        Collection<RouteRegistrationInfo> routeRegistrationInfos = List.of(routeRegistrationInfo);

        stubDeploymentWithSnapshot(deploymentInfo, DEPLOYMENT_ID, snapshotInfo, SNAPSHOT_ID);
        when(route.getCamelContext()).thenReturn(camelContext);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getRouteRegistrationInfo(camelContext, SNAPSHOT_ID))
                .thenReturn(routeRegistrationInfos);

            action.process(event);

            verify(routeRegistrationService).registerRoutes(routeRegistrationInfos);
        }
    }

    @Test
    void shouldSkipRouteRegistrationWhenServiceIsNotAvailable() throws Exception {
        RouteRegisterAction action = actionWithoutRouteRegistrationService();

        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_ID);
        when(deploymentInfo.getName()).thenReturn(DEPLOYMENT_NAME);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            verifyNoInteractions(routeRegistrationService);
            metadataUtil.verify(() -> MetadataUtil.getBean(route, DeploymentInfo.class));
            metadataUtil.verifyNoMoreInteractions();
        }
    }

    @Test
    void shouldRegisterRoutesOnlyOnceWhenSameDeploymentIsProcessedTwice() throws Exception {
        RouteRegisterAction action = actionWithRouteRegistrationService();
        Collection<RouteRegistrationInfo> routeRegistrationInfos = List.of(routeRegistrationInfo);

        stubDeploymentWithSnapshot(deploymentInfo, DEPLOYMENT_ID, snapshotInfo, SNAPSHOT_ID);
        when(route.getCamelContext()).thenReturn(camelContext);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getRouteRegistrationInfo(camelContext, SNAPSHOT_ID))
                .thenReturn(routeRegistrationInfos);

            action.process(event);
            action.process(event);

            verify(routeRegistrationService, times(1)).registerRoutes(routeRegistrationInfos);
            metadataUtil.verify(() -> MetadataUtil.getBean(route, DeploymentInfo.class), times(2));
            metadataUtil.verify(() -> MetadataUtil.getRouteRegistrationInfo(camelContext, SNAPSHOT_ID), times(1));
        }
    }

    @Test
    void shouldRegisterRoutesForDifferentDeploymentsIndependently() throws Exception {
        RouteRegisterAction action = actionWithRouteRegistrationService();
        Collection<RouteRegistrationInfo> routeRegistrationInfos = List.of(routeRegistrationInfo);
        Collection<RouteRegistrationInfo> anotherRouteRegistrationInfos = List.of(anotherRouteRegistrationInfo);

        when(anotherEvent.getRoute()).thenReturn(anotherRoute);

        stubDeploymentWithSnapshot(deploymentInfo, DEPLOYMENT_ID, snapshotInfo, SNAPSHOT_ID);
        stubDeploymentWithSnapshot(
            anotherDeploymentInfo,
            ANOTHER_DEPLOYMENT_ID,
            anotherSnapshotInfo,
            ANOTHER_SNAPSHOT_ID
        );

        when(route.getCamelContext()).thenReturn(camelContext);
        when(anotherRoute.getCamelContext()).thenReturn(camelContext);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getBean(anotherRoute, DeploymentInfo.class))
                .thenReturn(anotherDeploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getRouteRegistrationInfo(camelContext, SNAPSHOT_ID))
                .thenReturn(routeRegistrationInfos);
            metadataUtil.when(() -> MetadataUtil.getRouteRegistrationInfo(camelContext, ANOTHER_SNAPSHOT_ID))
                .thenReturn(anotherRouteRegistrationInfos);

            action.process(event);
            action.process(anotherEvent);

            verify(routeRegistrationService).registerRoutes(routeRegistrationInfos);
            verify(routeRegistrationService).registerRoutes(anotherRouteRegistrationInfos);
        }
    }

    @Test
    void shouldNotRegisterRoutesAgainWhenServiceIsUnavailableAndSameDeploymentIsProcessedTwice() throws Exception {
        RouteRegisterAction action = actionWithoutRouteRegistrationService();

        when(deploymentInfo.getId()).thenReturn(DEPLOYMENT_ID);
        when(deploymentInfo.getName()).thenReturn(DEPLOYMENT_NAME);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);
            action.process(event);

            verifyNoInteractions(routeRegistrationService);
            metadataUtil.verify(() -> MetadataUtil.getBean(route, DeploymentInfo.class), times(2));
            metadataUtil.verifyNoMoreInteractions();
        }
    }

    @Test
    void shouldPropagateExceptionWhenRouteRegistrationFails() {
        RouteRegisterAction action = actionWithRouteRegistrationService();
        Collection<RouteRegistrationInfo> routeRegistrationInfos = List.of(routeRegistrationInfo);
        RuntimeException exception = new RuntimeException("Failed to register routes");

        stubDeploymentWithSnapshot(deploymentInfo, DEPLOYMENT_ID, snapshotInfo, SNAPSHOT_ID);
        when(route.getCamelContext()).thenReturn(camelContext);
        org.mockito.Mockito.doThrow(exception)
            .when(routeRegistrationService)
            .registerRoutes(routeRegistrationInfos);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getRouteRegistrationInfo(camelContext, SNAPSHOT_ID))
                .thenReturn(routeRegistrationInfos);

            RuntimeException result = assertThrows(RuntimeException.class, () -> action.process(event));

            assertSame(exception, result);
        }
    }

    private RouteRegisterAction actionWithRouteRegistrationService() {
        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(routeRegistrationServiceInstance))
                .thenReturn(Optional.of(routeRegistrationService));

            return new RouteRegisterAction(routeRegistrationServiceInstance);
        }
    }

    private RouteRegisterAction actionWithoutRouteRegistrationService() {
        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(routeRegistrationServiceInstance))
                .thenReturn(Optional.empty());

            return new RouteRegisterAction(routeRegistrationServiceInstance);
        }
    }

    private static void stubDeploymentWithSnapshot(
        DeploymentInfo deploymentInfo,
        String deploymentId,
        SnapshotInfo snapshotInfo,
        String snapshotId
    ) {
        when(deploymentInfo.getId()).thenReturn(deploymentId);
        when(deploymentInfo.getSnapshot()).thenReturn(snapshotInfo);
        when(snapshotInfo.getId()).thenReturn(snapshotId);
    }
}

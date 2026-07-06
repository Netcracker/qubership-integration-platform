package org.qubership.integration.platform.engine.controlplane;

import com.netcracker.cloud.routesregistration.common.gateway.route.RouteEntry;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.gateway.route.RoutesRestRegistrationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.rest.RestApiConstants;
import org.qubership.integration.platform.engine.rest.v1.controller.CheckpointSessionController;
import org.qubership.integration.platform.engine.rest.v1.controller.LiveExchangesController;
import org.qubership.integration.platform.engine.rest.v1.controller.SessionController;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RoutesRegistratorTest {

    private static final String DOMAIN = "engine-domain";

    @Mock
    private RoutesRestRegistrationProcessor routesRestRegistrationProcessor;

    private EngineInfo engineInfo;

    private RoutesRegistrator routesRegistrator;

    @BeforeEach
    void setUp() {
        engineInfo = EngineInfo.builder().domain(DOMAIN).build();
        routesRegistrator = new RoutesRegistrator(routesRestRegistrationProcessor, engineInfo);
    }

    @Test
    void shouldRegisterPublicRoutesForSessionsCheckpointsAndLiveExchanges() {
        routesRegistrator.registerRoutes();

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<RouteEntry>> routesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(routesRestRegistrationProcessor).postRoutes(routesCaptor.capture());
        verifyNoMoreInteractions(routesRestRegistrationProcessor);

        List<RouteEntry> expectedRoutes = List.of(
                new RouteEntry(
                        RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/" + DOMAIN + SessionController.SESSIONS_PATH,
                        RouteType.PUBLIC),
                new RouteEntry(
                        RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/" + DOMAIN
                                + CheckpointSessionController.CHECKPOINT_SESSION_PATH,
                        RouteType.PUBLIC),
                new RouteEntry(
                        RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/" + DOMAIN
                                + LiveExchangesController.LIVE_EXCHANGES_PATH,
                        RouteType.PUBLIC)
        );
        assertEquals(expectedRoutes, routesCaptor.getValue());
    }

    @Test
    void shouldBuildRoutePathsUsingEngineDomainFromEngineInfo() {
        engineInfo.setDomain("other-domain");

        routesRegistrator.registerRoutes();

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<RouteEntry>> routesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(routesRestRegistrationProcessor).postRoutes(routesCaptor.capture());

        assertEquals(
                List.of(
                        RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/other-domain" + SessionController.SESSIONS_PATH,
                        RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/other-domain"
                                + CheckpointSessionController.CHECKPOINT_SESSION_PATH,
                        RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/other-domain"
                                + LiveExchangesController.LIVE_EXCHANGES_PATH),
                routesCaptor.getValue().stream().map(RouteEntry::getFrom).toList());
    }
}

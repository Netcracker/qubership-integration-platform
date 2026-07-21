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
import org.qubership.integration.platform.engine.rest.v1.controller.CheckpointSessionController;
import org.qubership.integration.platform.engine.rest.v1.controller.LiveExchangesController;
import org.qubership.integration.platform.engine.rest.v1.controller.SessionController;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.qubership.integration.platform.engine.rest.RestApiConstants.V1_ROUTE_PREFIX;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RoutesRegistratorTest {

    private static final String DOMAIN = "engine-domain";
    private static final String PUBLIC_V1_PREFIX = "/api/v1/test/engine";

    @Mock
    private RoutesRestRegistrationProcessor routesRestRegistrationProcessor;

    private EngineInfo engineInfo;

    private RoutesRegistrator routesRegistrator;

    @BeforeEach
    void setUp() {
        engineInfo = EngineInfo.builder().domain(DOMAIN).build();
        routesRegistrator = new RoutesRegistrator(routesRestRegistrationProcessor, engineInfo);
        routesRegistrator.publicRoutePrefixV1 = PUBLIC_V1_PREFIX;
    }

    @Test
    void shouldRegisterRoutesForSessionsCheckpointsAndLiveExchanges() {
        routesRegistrator.registerRoutes();

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<RouteEntry>> routesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(routesRestRegistrationProcessor).postRoutes(routesCaptor.capture());
        verifyNoMoreInteractions(routesRestRegistrationProcessor);

        String sessionFrom = PUBLIC_V1_PREFIX + "/" + DOMAIN + SessionController.SESSIONS_PATH;
        String sessionTo = V1_ROUTE_PREFIX + SessionController.SESSIONS_PATH;
        String checkpointFrom = PUBLIC_V1_PREFIX + "/" + DOMAIN + CheckpointSessionController.CHECKPOINT_SESSION_PATH;
        String checkpointTo = V1_ROUTE_PREFIX + CheckpointSessionController.CHECKPOINT_SESSION_PATH;
        String liveExchangesFrom = PUBLIC_V1_PREFIX + "/" + DOMAIN + LiveExchangesController.LIVE_EXCHANGES_PATH;

        List<RouteEntry> expectedRoutes = List.of(
                new RouteEntry(sessionFrom, sessionTo, RouteType.PUBLIC),
                new RouteEntry(sessionFrom, sessionTo, RouteType.PRIVATE),
                new RouteEntry(sessionFrom, sessionTo, RouteType.INTERNAL),
                new RouteEntry(checkpointFrom, checkpointTo, RouteType.PUBLIC),
                new RouteEntry(checkpointFrom, checkpointTo, RouteType.PRIVATE),
                new RouteEntry(checkpointFrom, checkpointTo, RouteType.INTERNAL),
                new RouteEntry(liveExchangesFrom, RouteType.PUBLIC)
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

        String sessionsFrom = PUBLIC_V1_PREFIX + "/other-domain" + SessionController.SESSIONS_PATH;
        String checkpointFrom = PUBLIC_V1_PREFIX + "/other-domain" + CheckpointSessionController.CHECKPOINT_SESSION_PATH;
        String liveExchangesFrom = PUBLIC_V1_PREFIX + "/other-domain" + LiveExchangesController.LIVE_EXCHANGES_PATH;

        assertEquals(
                List.of(
                        sessionsFrom, sessionsFrom, sessionsFrom,
                        checkpointFrom, checkpointFrom, checkpointFrom,
                        liveExchangesFrom),
                routesCaptor.getValue().stream().map(RouteEntry::getFrom).toList());
    }
}

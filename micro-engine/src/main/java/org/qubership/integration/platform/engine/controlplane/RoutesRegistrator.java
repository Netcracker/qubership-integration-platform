package org.qubership.integration.platform.engine.controlplane;

import com.netcracker.cloud.routesregistration.common.gateway.route.RouteEntry;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.gateway.route.RoutesRestRegistrationProcessor;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.rest.RestApiConstants;
import org.qubership.integration.platform.engine.rest.v1.controller.CheckpointSessionController;
import org.qubership.integration.platform.engine.rest.v1.controller.LiveExchangesController;
import org.qubership.integration.platform.engine.rest.v1.controller.SessionController;

import java.util.List;

@Unremovable
@ApplicationScoped
@IfBuildProperty(name = "qip.control-plane.routes.registration.enabled", stringValue = "true", enableIfMissing = true)
public class RoutesRegistrator {
    @Inject
    RoutesRestRegistrationProcessor routesRestRegistrationProcessor;

    @Inject
    EngineInfo engineInfo;

    @PostConstruct
    public void registerRoutes() {
        List<RouteEntry> routes = List.of(
            new RouteEntry(
                RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/" + engineInfo.getDomain()
                    + SessionController.SESSIONS_PATH,
                RouteType.PUBLIC
            ),
            new RouteEntry(
                RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/" + engineInfo.getDomain()
                    + CheckpointSessionController.CHECKPOINT_SESSION_PATH,
                RouteType.PUBLIC
            ),
            new RouteEntry(
                RestApiConstants.V1_PUBLIC_ROUTE_PREFIX + "/" + engineInfo.getDomain()
                    + LiveExchangesController.LIVE_EXCHANGES_PATH,
                RouteType.PUBLIC
            )
        );
        routesRestRegistrationProcessor.postRoutes(routes);
    }
}

package org.qubership.integration.platform.engine.controlplane;

import com.netcracker.cloud.routesregistration.common.gateway.route.RouteEntry;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.gateway.route.RoutesRestRegistrationProcessor;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.rest.v1.controller.CheckpointSessionController;
import org.qubership.integration.platform.engine.rest.v1.controller.LiveExchangesController;
import org.qubership.integration.platform.engine.rest.v1.controller.SessionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.integration.platform.engine.rest.RestApiConstants.V1_ROUTE_PREFIX;

@Startup
@Unremovable
@ApplicationScoped
@IfBuildProperty(name = "qip.control-plane.routes.registration.enabled", stringValue = "true", enableIfMissing = true)
public class RoutesRegistrator {
    private static final Logger LOG = LoggerFactory.getLogger(RoutesRegistrator.class);

    private final RoutesRestRegistrationProcessor routesRestRegistrationProcessor;
    private final EngineInfo engineInfo;

    @ConfigProperty(name = "qip.control-plane.routes.public.v1-prefix")
    String publicRoutePrefixV1;

    @Inject
    public RoutesRegistrator(
        RoutesRestRegistrationProcessor routesRestRegistrationProcessor,
        EngineInfo engineInfo
    ) {
        this.routesRestRegistrationProcessor = routesRestRegistrationProcessor;
        this.engineInfo = engineInfo;
    }

    @PostConstruct
    public void registerRoutes() {
        LOG.info("registering routes in control-plane for domain={}", engineInfo.getDomain());
        List<RouteEntry> routes = new ArrayList<>();
        routes.addAll(createRouteEntriesForAllGateways(SessionController.SESSIONS_PATH));
        routes.addAll(createRouteEntriesForAllGateways(CheckpointSessionController.CHECKPOINT_SESSION_PATH));
        routes.add(
            new RouteEntry(publicRoutePrefixV1 + "/" + engineInfo.getDomain() + LiveExchangesController.LIVE_EXCHANGES_PATH,
                RouteType.PUBLIC
            )
        );
        try {
            routesRestRegistrationProcessor.postRoutes(routes);
        } catch (Exception e) {
            LOG.error("failed to post routes", e);
            throw e;
        }
    }

    private List<RouteEntry> createRouteEntriesForAllGateways(String apiPath) {
        String from = publicRoutePrefixV1 + "/" + engineInfo.getDomain() + apiPath;
        String to = V1_ROUTE_PREFIX + apiPath;
        return List.of(
            new RouteEntry(from, to, RouteType.PUBLIC),
            new RouteEntry(from, to, RouteType.PRIVATE),
            new RouteEntry(from, to, RouteType.INTERNAL)
        );
    }
}

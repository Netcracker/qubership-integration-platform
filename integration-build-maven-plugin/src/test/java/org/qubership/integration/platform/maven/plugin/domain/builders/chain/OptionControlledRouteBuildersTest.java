package org.qubership.integration.platform.maven.plugin.domain.builders.chain;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService.BUILD_CRS_TASK_PARAMETERS;

/**
 * The three builders gate their base class on the {@code controlPlaneType} mojo parameter. Only the
 * route collection is reachable from {@code enabled}, so the remaining collaborators are passed as
 * null: a future {@code enabled} that reads one of them fails here rather than passing silently.
 */
class OptionControlledRouteBuildersTest {

    private final RoutesGetterService routesGetterService = mock(RoutesGetterService.class);

    private final OptionControlledHttpRouteResourceBuilder httpRouteBuilder =
        new OptionControlledHttpRouteResourceBuilder(null, routesGetterService, null, null, null, null);

    private final OptionControlledEgressRouteResourceBuilder egressRouteBuilder =
        new OptionControlledEgressRouteResourceBuilder(null, routesGetterService, null, null);

    private final OptionControlledEngineRoutesResourceBuilder engineRoutesBuilder =
        new OptionControlledEngineRoutesResourceBuilder(null, null, null, null);

    @Test
    void istioBuildsTheChainTriggerRoutes() {
        givenRoutes(RouteType.EXTERNAL_TRIGGER);

        assertTrue(httpRouteBuilder.enabled(contextFor(ControlPlaneType.ISTIO)));
    }

    @Test
    void istioBuildsTheEgressRoutes() {
        givenRoutes(RouteType.EXTERNAL_SENDER);

        assertTrue(egressRouteBuilder.enabled(contextFor(ControlPlaneType.ISTIO)));
    }

    @Test
    void istioBuildsTheEngineRoutes() {
        assertTrue(engineRoutesBuilder.enabled(contextFor(ControlPlaneType.ISTIO)));
    }

    /**
     * The gate comes first, so a CORE build never walks the chains looking for routes it would then
     * throw away.
     */
    @Test
    void coreSkipsTheChainTriggerRoutesWithoutCollectingThem() {
        assertFalse(httpRouteBuilder.enabled(contextFor(ControlPlaneType.CORE)));
        verifyNoInteractions(routesGetterService);
    }

    @Test
    void coreSkipsTheEgressRoutesWithoutCollectingThem() {
        assertFalse(egressRouteBuilder.enabled(contextFor(ControlPlaneType.CORE)));
        verifyNoInteractions(routesGetterService);
    }

    @Test
    void coreSkipsTheEngineRoutes() {
        assertFalse(engineRoutesBuilder.enabled(contextFor(ControlPlaneType.CORE)));
    }

    private void givenRoutes(RouteType routeType) {
        when(routesGetterService.getRoutes(any(), any()))
            .thenReturn(List.of(Route.builder().path("/test").type(routeType).build()));
    }

    private static ResourceBuildContext<List<Snapshot>> contextFor(ControlPlaneType controlPlaneType) {
        ResourceBuildContext<Void> context =
            ResourceBuildContext.create(BuildInfo.builder().build(), IntegrationServiceCatalog.EMPTY);
        context.getBuildCache().put(BUILD_CRS_TASK_PARAMETERS,
            BuildCRsTaskParameters.builder().controlPlaneType(controlPlaneType).build());
        return context.updateTo(List.of(mock(Snapshot.class)));
    }
}

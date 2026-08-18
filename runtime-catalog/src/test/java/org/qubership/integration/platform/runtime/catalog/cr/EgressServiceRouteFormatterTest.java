package org.qubership.integration.platform.runtime.catalog.cr;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentRouteUpdate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link EgressServiceRouteFormatter#formatServiceRoute} reproduces engine's
 * {@code RegisterRoutesInControlPlaneAction.formatServiceRoutes} transformation exactly, since both
 * write to the same shared HTTPRoute by name and must produce identical values for the same
 * underlying route.
 */
class EgressServiceRouteFormatterTest {

    private DeploymentRouteUpdate route(String path, String gatewayPrefix, RouteType type, Long connectTimeout) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .gatewayPrefix(gatewayPrefix)
                .type(type)
                .connectTimeout(connectTimeout)
                .build();
    }

    @Test
    void leavesNonExternalServiceRoutesUntouched() {
        DeploymentRouteUpdate senderRoute = route("https://backend:8080", "/http-sender/elem-a/abc",
                RouteType.EXTERNAL_SENDER, 5000L);

        DeploymentRouteUpdate result = EgressServiceRouteFormatter.formatServiceRoute(senderRoute);

        assertSame(senderRoute, result);
    }

    @Test
    void appendsSchemeAndHashSuffixForExternalServiceRoute() {
        DeploymentRouteUpdate serviceRoute = route("example.com", "/system/elem-a",
                RouteType.EXTERNAL_SERVICE, 5000L);

        DeploymentRouteUpdate result = EgressServiceRouteFormatter.formatServiceRoute(serviceRoute);

        assertEquals("https://example.com", result.getPath());
        assertTrue(result.getGatewayPrefix().matches("^/system/elem-a/[0-9a-f]{40}$"),
                () -> "unexpected gatewayPrefix: " + result.getGatewayPrefix());
    }

    @Test
    void hashMatchesEngineAlgorithmExactly() {
        DeploymentRouteUpdate serviceRoute = route("backend.internal:8443/base", "/system/elem-a",
                RouteType.EXTERNAL_SERVICE, 7000L);

        DeploymentRouteUpdate result = EgressServiceRouteFormatter.formatServiceRoute(serviceRoute);

        String formattedPath = "https://backend.internal:8443/base";
        String expectedHash = DigestUtils.sha1Hex(StringUtils.joinWith(",", formattedPath, 7000L));
        assertEquals(formattedPath, result.getPath());
        assertEquals("/system/elem-a/" + expectedHash, result.getGatewayPrefix());
    }

    @Test
    void twoRoutesOnTheSameTargetShareTheSameHashSuffixButDistinctPrefixes() {
        DeploymentRouteUpdate routeA = route("https://api.example.com", "/system/elem-a",
                RouteType.EXTERNAL_SERVICE, 3000L);
        DeploymentRouteUpdate routeB = route("https://api.example.com", "/system/elem-b",
                RouteType.EXTERNAL_SERVICE, 3000L);

        DeploymentRouteUpdate resultA = EgressServiceRouteFormatter.formatServiceRoute(routeA);
        DeploymentRouteUpdate resultB = EgressServiceRouteFormatter.formatServiceRoute(routeB);

        String hashA = resultA.getGatewayPrefix().substring("/system/elem-a/".length());
        String hashB = resultB.getGatewayPrefix().substring("/system/elem-b/".length());
        assertEquals(hashA, hashB);
        assertNotEquals(resultA.getGatewayPrefix(), resultB.getGatewayPrefix());
    }

    @Test
    void preservesVariableNameAndConnectTimeout() {
        DeploymentRouteUpdate serviceRoute = DeploymentRouteUpdate.builder()
                .path("https://example.com")
                .gatewayPrefix("/system/elem-a")
                .type(RouteType.EXTERNAL_SERVICE)
                .variableName("route-elem-a")
                .connectTimeout(5000L)
                .build();

        DeploymentRouteUpdate result = EgressServiceRouteFormatter.formatServiceRoute(serviceRoute);

        assertEquals("route-elem-a", result.getVariableName());
        assertEquals(5000L, result.getConnectTimeout());
        assertEquals(RouteType.EXTERNAL_SERVICE, result.getType());
    }
}

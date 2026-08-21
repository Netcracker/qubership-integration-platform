package org.qubership.integration.platform.camelk.services;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.camelk.model.ResourceBuildError;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.io.util.SimpleHttpUriUtils;

import java.net.MalformedURLException;

/**
 * Reproduces, for build-time HTTPRoute/ServiceEntry/DestinationRule generation,
 * the transformation {@code engine}'s {@code RegisterRoutesInControlPlaneAction.formatServiceRoutes}
 * (and {@code micro-engine}'s equivalent) applies to {@code EXTERNAL_SERVICE} routes at live
 * registration time: normalize the target address to always carry a scheme, then append a SHA-1
 * hash of {@code (path, connectTimeout)} to {@code gatewayPrefix}.
 *
 * <p>This is deliberately NOT applied inside {@link RoutesGetterService} itself, because that
 * method's output also feeds the deployment payload engine/micro-engine receive -- their own
 * formatting step already appends this same hash downstream of that payload, so hashing at the
 * shared source would double it. Applying it here, once, only within the CR-generation call sites,
 * keeps the two hashing steps (this one and engine's/micro-engine's) each applied exactly once,
 * producing the same final value engine/micro-engine will register live.
 */
public final class EgressServiceRouteFormatter {
    private EgressServiceRouteFormatter() {
    }

    public static Route formatServiceRoute(Route route) {
        if (route.getType() != RouteType.EXTERNAL_SERVICE) {
            return route;
        }

        String formattedPath;
        try {
            formattedPath = SimpleHttpUriUtils.formatUri(route.getPath());
        } catch (MalformedURLException e) {
            throw new ResourceBuildError(
                    "Failed to build egress routes. Invalid environment address for gateway prefix "
                            + route.getGatewayPrefix(), e);
        }
        String pathHash = DigestUtils.sha1Hex(StringUtils.joinWith(",", formattedPath, route.getConnectTimeout()));

        return route.toBuilder()
                .path(formattedPath)
                .gatewayPrefix(route.getGatewayPrefix() + "/" + pathHash)
                .build();
    }
}

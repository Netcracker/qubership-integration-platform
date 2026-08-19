package org.qubership.integration.platform.runtime.catalog.util;

import org.qubership.integration.platform.camelk.model.routes.ElementRoute;
import org.qubership.integration.platform.runtime.catalog.util.paths.PathIntersectionChecker;

public final class ElementRouteUtils {
    private ElementRouteUtils() {}

    public static boolean intersects(ElementRoute route, ElementRoute other) {
        PathIntersectionChecker intersectionChecker = new PathIntersectionChecker();

        return intersectionChecker.intersects(route.getPath(), other.getPath())
            && route.getMethods().stream().anyMatch(other.getMethods()::contains);
    }
}

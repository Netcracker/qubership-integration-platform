package org.qubership.integration.platform.engine.model.controlplane;

import lombok.*;
import org.qubership.integration.platform.engine.util.PathIntersectionChecker;
import org.springframework.http.HttpMethod;

import java.util.Set;

@Getter
@Setter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ElementRoute {
    private String path;
    private Set<HttpMethod> methods;
    private boolean isExternal;
    private long connectionTimeout;

    public boolean intersectsWith(ElementRoute route) {
        PathIntersectionChecker intersectionChecker = new PathIntersectionChecker();
        return (isExternal == route.isExternal())
                && intersectionChecker.intersects(path, route.getPath())
                && methods.stream().anyMatch(route.getMethods()::contains);
    }
}

package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class ResourceBuildContext<T> {
    private final BuildInfo buildInfo;
    private final Map<String, Object> buildCache;
    private final T data;

    public static ResourceBuildContext<Void> create(BuildInfo buildInfo) {
        return new ResourceBuildContext<>(buildInfo, new HashMap<>(), null);
    }

    public <U> ResourceBuildContext<U> updateTo(U data) {
        return new ResourceBuildContext<>(buildInfo, buildCache, data);
    }

    private ResourceBuildContext(
            BuildInfo buildInfo,
            Map<String, Object> buildCache,
            T data
    ) {
        this.buildInfo = buildInfo;
        this.buildCache = buildCache;
        this.data = data;
    }
}

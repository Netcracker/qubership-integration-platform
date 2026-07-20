package org.qubership.integration.platform.camelk.model;

import lombok.Getter;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;

import java.util.*;

@Getter
public class ResourceBuildContext<T> {
    private static final IntegrationServiceCatalog DEFAULT_SERVICE_CATALOG = new IntegrationServiceCatalog() {
        @Override
        public Optional<IntegrationService> findById(String id) {
            return Optional.empty();
        }

        @Override
        public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
            return List.of();
        }
    };

    private final BuildInfo buildInfo;
    private final Map<String, Object> buildCache;
    private final IntegrationServiceCatalog serviceCatalog;
    private final T data;


    public static ResourceBuildContext<Void> create(BuildInfo buildInfo) {
        return new ResourceBuildContext<>(buildInfo, new HashMap<>(), DEFAULT_SERVICE_CATALOG, null);
    }

    public static ResourceBuildContext<Void> create(BuildInfo buildInfo, IntegrationServiceCatalog serviceCatalog) {
        return new ResourceBuildContext<>(buildInfo, new HashMap<>(), serviceCatalog, null);
    }

    public <U> ResourceBuildContext<U> updateTo(U data) {
        return new ResourceBuildContext<>(buildInfo, buildCache, serviceCatalog, data);
    }

    private ResourceBuildContext(
            BuildInfo buildInfo,
            Map<String, Object> buildCache,
            IntegrationServiceCatalog serviceCatalog,
            T data
    ) {
        this.buildInfo = buildInfo;
        this.buildCache = buildCache;
        this.serviceCatalog = serviceCatalog;
        this.data = data;
    }
}

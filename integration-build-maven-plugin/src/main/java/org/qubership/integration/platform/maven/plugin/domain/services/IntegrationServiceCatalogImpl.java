package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IntegrationServiceCatalogImpl implements IntegrationServiceCatalog {
    private final Map<String, IntegrationService> integrationServiceMap = new ConcurrentHashMap<>();

    @Override
    public Optional<IntegrationService> findById(String id) {
        return Optional.ofNullable(integrationServiceMap.get(id));
    }

    @Override
    public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
        return ids.stream().map(integrationServiceMap::get).filter(Objects::nonNull).toList();
    }

    public void addService(IntegrationService integrationService) {
        IntegrationService prev = integrationServiceMap.putIfAbsent(integrationService.getId(), integrationService);
        if (prev != null) {
            throw new IllegalStateException("Duplicate integration service with id " + integrationService.getId());
        }
    }
}

package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
public class IntegrationServiceCatalogImpl implements IntegrationServiceCatalog {
    @Override
    public Optional<IntegrationService> findById(String id) {
        return Optional.empty();
    }

    @Override
    public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
        return List.of();
    }
}

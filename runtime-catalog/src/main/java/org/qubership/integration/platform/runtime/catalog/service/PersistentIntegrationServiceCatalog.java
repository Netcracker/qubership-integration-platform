package org.qubership.integration.platform.runtime.catalog.service;

import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.runtime.catalog.adapters.IntegrationServiceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

@Component
public class PersistentIntegrationServiceCatalog implements IntegrationServiceCatalog {
    private final SystemService systemService;

    @Autowired
    public PersistentIntegrationServiceCatalog(SystemService systemService) {
        this.systemService = systemService;
    }

    @Override
    public Optional<IntegrationService> findById(String id) {
        return Optional.ofNullable(systemService.findById(id)).map(IntegrationServiceAdapter::new);
    }

    @Override
    public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
        return systemService.findAllByIds(ids).stream()
            .<IntegrationService>map(IntegrationServiceAdapter::new)
            .toList();
    }
}

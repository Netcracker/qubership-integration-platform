package org.qubership.integration.platform.camelk.sources;

import org.qubership.integration.platform.chain.model.IntegrationService;

import java.util.Collection;
import java.util.Optional;

public interface IntegrationServiceCatalog {
    Optional<IntegrationService> findById(String id);

    Collection<IntegrationService> findAllByIds(Collection<String> ids);
}

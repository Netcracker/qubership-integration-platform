package org.qubership.integration.platform.camelk.sources;

import org.qubership.integration.platform.chain.model.IntegrationService;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public interface IntegrationServiceCatalog {
    Collection<IntegrationService> findAll();

    Optional<IntegrationService> findById(String id);

    Collection<IntegrationService> findAllByIds(Collection<String> ids);

    /**
     * A stateless catalog that resolves nothing, shared as a singleton since it carries no state.
     * Use it only where the caller does not, in fact, look up a service through the resulting
     * context — e.g. a naming context built just to call a {@code NamingStrategy}.
     */
    IntegrationServiceCatalog EMPTY = new IntegrationServiceCatalog() {
        @Override
        public Optional<IntegrationService> findById(String id) {
            return Optional.empty();
        }

        @Override
        public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
            return Collections.emptyList();
        }
    };
}

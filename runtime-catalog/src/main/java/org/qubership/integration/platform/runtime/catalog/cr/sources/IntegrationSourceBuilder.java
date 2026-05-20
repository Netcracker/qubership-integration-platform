package org.qubership.integration.platform.runtime.catalog.cr.sources;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;

public interface IntegrationSourceBuilder {
    String getLanguageName();

    String build(Snapshot snapshot, SourceBuilderContext context) throws Exception;
}

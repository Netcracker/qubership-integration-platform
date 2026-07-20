package org.qubership.integration.platform.camelk.sources;

import org.qubership.integration.platform.chain.model.Snapshot;

public interface IntegrationSourceBuilder {
    String getLanguageName();

    String build(Snapshot snapshot, SourceBuilderContext context) throws Exception;
}

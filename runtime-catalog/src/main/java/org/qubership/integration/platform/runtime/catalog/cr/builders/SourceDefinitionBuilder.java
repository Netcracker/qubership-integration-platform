package org.qubership.integration.platform.runtime.catalog.cr.builders;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.SourceDefinition;
import org.qubership.integration.platform.runtime.catalog.cr.locations.SourceLocationGetterProvider;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SourceDefinitionBuilder {
    private final SourceLocationGetterProvider sourceLocationGetterProvider;

    @Autowired
    public SourceDefinitionBuilder(SourceLocationGetterProvider sourceLocationGetterProvider) {
        this.sourceLocationGetterProvider = sourceLocationGetterProvider;
    }

    public SourceDefinition build(ResourceBuildContext<Snapshot> context) {
        Snapshot snapshot = context.getData();
        return SourceDefinition.builder()
                .id(snapshot.getId())
                .chainId(snapshot.getChain().getId())
                .name(String.format("%s (%s)", snapshot.getChain().getName(), snapshot.getName()))
                .location(getSourceDslLocation(context))
                .language(context.getBuildInfo().getOptions().getLanguage())
                .build();
    }

    private String getSourceDslLocation(ResourceBuildContext<Snapshot> context) {
        return sourceLocationGetterProvider.get(context).apply(context);
    }
}

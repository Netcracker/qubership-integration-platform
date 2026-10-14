package org.qubership.integration.platform.camelk.builders;

import org.qubership.integration.platform.camelk.integrations.configuration.SourceDefinition;
import org.qubership.integration.platform.camelk.locations.SourceLocationGetterProvider;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.chain.model.Snapshot;
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

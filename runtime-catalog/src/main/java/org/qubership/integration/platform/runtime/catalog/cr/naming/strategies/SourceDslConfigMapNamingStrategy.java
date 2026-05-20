package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component("sourceDslConfigMapNamingStrategy")
public class SourceDslConfigMapNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<Snapshot>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;

    @Autowired
    public SourceDslConfigMapNamingStrategy(
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy
    ) {
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
    }

    @Override
    protected String proposeName(ResourceBuildContext<Snapshot> context) {
        Snapshot snapshot = context.getData();
        return context.getBuildCache()
                .computeIfAbsent(getKey(snapshot), k -> createUniqueName(context))
                .toString();
    }

    private String getKey(Snapshot snapshot) {
        return this.getClass().getSimpleName() + "-" + snapshot.getId();
    }

    private String createUniqueName(ResourceBuildContext<Snapshot> context) {
        String prefix = integrationResourceNamingStrategy.getName(context.updateTo(Collections.emptyList()));
        return String.format("%s-%s", prefix, UUID.randomUUID());
    }

    public void useName(ResourceBuildContext<Snapshot> context, String name) {
        Snapshot snapshot = context.getData();
        context.getBuildCache().put(getKey(snapshot), name);
    }
}

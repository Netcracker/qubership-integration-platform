package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.model.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

@Component("sourceDslConfigMapNamingStrategy")
public class SourceDslConfigMapNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<Snapshot>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final Supplier<String> suffixGenerator;

    @Autowired
    public SourceDslConfigMapNamingStrategy(
        K8sNameVerifier nameVerifier,
        K8sNameValidator nameValidator,

        @Qualifier("integrationResourceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

        @Qualifier("suffixGenerator")
        Supplier<String> suffixGenerator
    ) {
        super(nameVerifier);
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.suffixGenerator = suffixGenerator;
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
        return String.format("%s-%s", prefix, suffixGenerator.get());
    }

    public void useName(ResourceBuildContext<Snapshot> context, String name) {
        Snapshot snapshot = context.getData();
        context.getBuildCache().put(getKey(snapshot), name);
    }
}

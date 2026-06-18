package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("integrationResourceNamingStrategy")
public class IntegrationResourceNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> cloudServiceNamingStrategy;
    private final String bgVersion;

    @Autowired
    public IntegrationResourceNamingStrategy(
        K8sNameVerifier nameVerifier,

        @Qualifier("cloudServiceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> cloudServiceNamingStrategy,

        @Value("${spring.application.deployment_version:v1}")
        String bgVersion
    ) {
        super(nameVerifier);
        this.cloudServiceNamingStrategy = cloudServiceNamingStrategy;
        this.bgVersion = bgVersion;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        return cloudServiceNamingStrategy.getName(context) + "-" + bgVersion;
    }
}

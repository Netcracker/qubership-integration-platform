package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("serviceNamingStrategy")
public class ServiceNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;

    @Value("${qip.cr.naming.service.suffix:-svc}")
    private String suffix;

    @Autowired
    public ServiceNamingStrategy(
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy
    ) {
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        String name = integrationResourceNamingStrategy.getName(context) + suffix;
        return name.substring(0, Math.min(name.length(), K8S_RESOURCE_NAME_LENGTH_LIMIT));
    }
}

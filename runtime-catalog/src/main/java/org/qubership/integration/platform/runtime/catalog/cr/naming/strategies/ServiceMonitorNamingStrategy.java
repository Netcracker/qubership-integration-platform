package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.model.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("serviceMonitorNamingStrategy")
public class ServiceMonitorNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> cloudServiceNamingStrategy;
    private final K8sNameValidator nameValidator;
    private final String suffix;

    @Autowired
    public ServiceMonitorNamingStrategy(
        K8sNameVerifier nameVerifier,
        K8sNameValidator nameValidator,

        @Qualifier("cloudServiceNamingStrategy")
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> cloudServiceNamingStrategy,

        @Value("${qip.cr.naming.service-monitor.suffix:}")
        String suffix
    ) {
        super(nameVerifier);
        this.cloudServiceNamingStrategy = cloudServiceNamingStrategy;
        this.nameValidator = nameValidator;
        this.suffix = suffix;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        String name = cloudServiceNamingStrategy.getName(context) + suffix;
        return nameValidator.validate(name);
    }
}

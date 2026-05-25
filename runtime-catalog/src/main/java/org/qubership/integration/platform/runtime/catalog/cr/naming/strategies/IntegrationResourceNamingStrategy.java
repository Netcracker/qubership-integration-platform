package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("integrationResourceNamingStrategy")
public class IntegrationResourceNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final String prefix;

    @Autowired
    public IntegrationResourceNamingStrategy(
        K8sNameVerifier nameVerifier,

        @Value("${qip.cr.naming.prefix:}")
        String prefix
    ) {
        super(nameVerifier);
        this.prefix = prefix;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        BuildInfo buildInfo = context.getBuildInfo();
        String name = buildInfo.getOptions().getName();
        return prefix + (StringUtils.isNotBlank(name) ? name : buildInfo.getId());
    }
}

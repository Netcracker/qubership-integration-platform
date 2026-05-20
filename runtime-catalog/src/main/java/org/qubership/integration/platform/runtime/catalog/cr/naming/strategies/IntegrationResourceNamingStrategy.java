package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("integrationResourceNamingStrategy")
public class IntegrationResourceNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        BuildInfo buildInfo = context.getBuildInfo();
        String name = buildInfo.getOptions().getName();
        return StringUtils.isNotBlank(name)
                ? name
                : "integration-" + buildInfo.getId();
    }
}

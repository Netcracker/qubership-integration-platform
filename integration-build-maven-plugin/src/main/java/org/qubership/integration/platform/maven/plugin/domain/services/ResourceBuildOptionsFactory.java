package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.springframework.stereotype.Component;

@Component
public class ResourceBuildOptionsFactory {
    public ResourceBuildOptions createResourceBuildOptions(String domain, BuildCRsTaskParameters parameters) {
        // FIXME
        return ResourceBuildOptions.builder()
            .name(domain)
            // TODO
            .build();
    }
}

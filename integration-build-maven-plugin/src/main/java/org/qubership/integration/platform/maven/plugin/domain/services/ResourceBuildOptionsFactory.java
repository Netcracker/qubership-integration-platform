package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;
import org.springframework.stereotype.Component;

@Component
public class ResourceBuildOptionsFactory {
    public ResourceBuildOptions createResourceBuildOptions(String domain, BuildCRsTaskParameters parameters) {
        BuildCRsOptions options = parameters.getOptions();
        return ResourceBuildOptions.builder()
            .name(domain)
            .replicas(options.getReplicas())
            .container(options.getContainer())
            .health(options.getHealth())
            .jvm(options.getJvm())
            .monitoring(options.getMonitoring())
            .service(options.getService())
            .mount(options.getMount())
            .environment(options.getEnvironment())
            .integrations(options.getIntegrations())
            .serviceAccount(options.getServiceAccount())
            .build();
    }
}

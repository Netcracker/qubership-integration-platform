package org.qubership.integration.platform.maven.plugin.domain.services;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.camelk.model.options.ContainerOptions;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ResourceBuildOptionsFactory {
    private static final String DEFAULT_SECRET_ENABLED_ENV = "DEFAULT_SECRET_ENABLED";

    private final String defaultContainerName;

    @Autowired
    public ResourceBuildOptionsFactory(
        @Value("${qip.cr.build.container.image}")
        String defaultContainerName
    ) {
        this.defaultContainerName = defaultContainerName;
    }

    public ResourceBuildOptions createResourceBuildOptions(String domain, BuildCRsTaskParameters parameters) {
        BuildCRsOptions options = parameters.getOptions();
        ContainerOptions.ContainerOptionsBuilder containerOptionsBuilder = options.getContainer().toBuilder();
        if (StringUtils.isBlank(options.getContainer().getImage())) {
            containerOptionsBuilder.image(defaultContainerName);
        }
        return ResourceBuildOptions.builder()
            .name(domain)
            .replicas(options.getReplicas())
            .container(containerOptionsBuilder.build())
            .health(options.getHealth())
            .jvm(options.getJvm())
            .monitoring(options.getMonitoring())
            .service(options.getService())
            .mount(options.getMount())
            .environment(buildEnvironment(parameters))
            .integrations(options.getIntegrations())
            .serviceAccount(options.getServiceAccount())
            .build();
    }

    private Map<String, String> buildEnvironment(BuildCRsTaskParameters parameters) {
        Map<String, String> env = new HashMap<>(parameters.getOptions().getEnvironment());
        env.put("MONITORING_ENABLED", Boolean.toString(parameters.getOptions().getMonitoring().isEnabled()));
        env.put(DEFAULT_SECRET_ENABLED_ENV, Boolean.toString(parameters.isDefaultSecretEnabled()));
        return env;
    }
}

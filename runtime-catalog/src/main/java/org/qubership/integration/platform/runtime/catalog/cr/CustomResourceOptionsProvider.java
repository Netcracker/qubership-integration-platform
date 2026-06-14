package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class CustomResourceOptionsProvider {
    private static final String DEFAULT_SECRET_ENABLED_ENV = "DEFAULT_SECRET_ENABLED";

    @Value("${qip.cr.build.container.image}")
    private String containerImage;

    @Value("${qip.cr.build.container.image-pool-policy:IfNotPresent}")
    private ImagePoolPolicy imagePoolPolicy;

    @Value("${qip.cr.build.monitoring.enabled:false}")
    private boolean monitoringEnabled;

    @Value("${qip.cr.build.monitoring.interval:30s}")
    private String interval;

    @Value("${qip.cr.build.service.enabled:true}")
    private boolean serviceEnabled;

    @Value("${qip.cr.build.service-account:default}")
    private String serviceAccount;

    @Value("${qip.cr.build.namespace:default")
    private String namespace;

    @Value("#{${qip.cr.build.environment:{}}}")
    private Map<String, String> environment;

    @Value("${qip.variables.default-secret.enabled:false}")
    private boolean defaultSecretEnabled;

    public ResourceBuildOptions getOptions(ResourceDeployRequest request) {
        return ResourceBuildOptions.builder()
                .name(request.getName())
                .namespace(namespace)
                .container(ContainerOptions.builder()
                        .image(containerImage)
                        .imagePoolPolicy(imagePoolPolicy)
                        .build())
                .monitoring(MonitoringOptions.builder()
                        .enabled(monitoringEnabled)
                        .interval(interval)
                        .build())
                .integrations(IntegrationsConfigurationOptions.builder()
                        .camelKSourcesUtilized(false)
                        .build())
                .environment(getEnvironment())
                .service(ServiceOptions.builder()
                        .enabled(serviceEnabled)
                        .build())
                .serviceAccount(serviceAccount)
                .build();
    }

    private Map<String, String> getEnvironment() {
        Map<String, String> result = new HashMap<>(environment);
        result.put("MONITORING_ENABLED", Boolean.valueOf(monitoringEnabled).toString());
        result.put(DEFAULT_SECRET_ENABLED_ENV, Boolean.toString(defaultSecretEnabled));
        return result;
    }
}

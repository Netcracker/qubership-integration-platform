package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class CustomResourceOptionsProvider {
    private static final String DEFAULT_SECRET_ENABLED_ENV = "DEFAULT_SECRET_ENABLED";

    @Value("${qip.cr.build.replicas:1}")
    private int replicas;

    @Value("${qip.cr.build.monitoring.enabled:false}")
    private boolean monitoringEnabled;

    @Value("${qip.cr.build.service-account:default}")
    private String serviceAccount;

    @Value("${qip.cr.build.namespace:default}")
    private String namespace;

    @Value("#{${qip.cr.build.environment:{T(java.util.Collections).emptyMap()}}}")
    private Map<String, String> environment;

    @Value("${qip.variables.default-secret.enabled:false}")
    private boolean defaultSecretEnabled;

    private final Environment propertyResolver;

    @Autowired
    public CustomResourceOptionsProvider(Environment propertyResolver) {
        this.propertyResolver = propertyResolver;
    }

    public ResourceBuildOptions getOptions(ResourceDeployRequest request) {
        return ResourceBuildOptions.builder()
                .name(request.getName())
                .namespace(namespace)
                .replicas(replicas)
                .container(Binder.get(propertyResolver)
                    .bind("qip.cr.build.container", ContainerOptions.class)
                    .orElseGet(ContainerOptions::new))
                .jvm(Binder.get(propertyResolver)
                    .bind("qip.cr.build.jvm", JvmOptions.class)
                    .orElseGet(JvmOptions::new))
                .monitoring(Binder.get(propertyResolver)
                    .bind("qip.cr.build.monitoring", MonitoringOptions.class)
                    .orElseGet(MonitoringOptions::new))
                .integrations(IntegrationsConfigurationOptions.builder()
                        .camelKSourcesUtilized(false)
                        .build())
                .environment(getEnvironment())
                .mount(Binder.get(propertyResolver)
                    .bind("qip.cr.build.mount", MountOptions.class)
                    .orElseGet(MountOptions::new))
                .service(Binder.get(propertyResolver)
                    .bind("qip.cr.build.service", ServiceOptions.class)
                    .orElseGet(ServiceOptions::new))
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

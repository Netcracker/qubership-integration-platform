package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto;

import lombok.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ResourceBuildOptions {
    @Builder.Default
    private String language = "xml";

    private String name;

    private String namespace;

    @Builder.Default
    private ContainerOptions container = new ContainerOptions();

    @Builder.Default
    private MonitoringOptions monitoring = new MonitoringOptions();

    @Builder.Default
    private ServiceOptions service = new ServiceOptions();

    @Builder.Default
    private Map<String, String> environment = new HashMap<>();

    @Builder.Default
    private Set<String> resources = new HashSet<>();

    @Builder.Default
    private IntegrationsConfigurationOptions integrations = new IntegrationsConfigurationOptions();

    private String serviceAccount;
}

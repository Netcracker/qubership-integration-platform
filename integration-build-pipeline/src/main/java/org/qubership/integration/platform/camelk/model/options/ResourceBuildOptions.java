package org.qubership.integration.platform.camelk.model.options;

import lombok.*;

import java.util.*;

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
    private int replicas = 1;

    @Builder.Default
    private ContainerOptions container = new ContainerOptions();

    @Builder.Default
    private HealthOptions health = new HealthOptions();

    @Builder.Default
    private JvmOptions jvm = new JvmOptions();

    @Builder.Default
    private MonitoringOptions monitoring = new MonitoringOptions();

    @Builder.Default
    private ServiceOptions service = new ServiceOptions();

    @Builder.Default
    private MountOptions mount = new MountOptions();

    @Builder.Default
    private Map<String, String> environment = new HashMap<>();

    @Builder.Default
    private IntegrationsConfigurationOptions integrations = new IntegrationsConfigurationOptions();

    private String serviceAccount;
}

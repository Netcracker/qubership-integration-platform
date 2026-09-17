package org.qubership.integration.platform.maven.plugin.mojos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.integration.platform.camelk.model.options.*;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BuildCRsOptions {
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

    private String serviceAccount = "default";
}

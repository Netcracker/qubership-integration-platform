package org.qubership.integration.platform.runtime.catalog.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "qip.deploy")
public class DomainProperties {
    @Data
    public static class DeployMethodConfiguration {
        private boolean enabled = false;
    }

    private DeployMethodConfiguration classic;
    private DeployMethodConfiguration micro;
}

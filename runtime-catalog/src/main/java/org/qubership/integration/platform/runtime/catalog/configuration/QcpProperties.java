package org.qubership.integration.platform.runtime.catalog.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "qip.qcp")
public class QcpProperties {
    private boolean enabled = true;
    private String name = "qcp";
    private String clientId = "qip-runtime-catalog";
    private String namespace = "default";
}

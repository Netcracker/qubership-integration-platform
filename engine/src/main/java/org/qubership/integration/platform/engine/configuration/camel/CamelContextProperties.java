package org.qubership.integration.platform.engine.configuration.camel;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "qip.camel.context")
public class CamelContextProperties {

    private Map<String, String> globalOptions = new HashMap<>();
}

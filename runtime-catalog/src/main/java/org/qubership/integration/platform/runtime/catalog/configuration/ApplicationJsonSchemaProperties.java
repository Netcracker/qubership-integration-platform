package org.qubership.integration.platform.runtime.catalog.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "qip.json.schemas")
public class ApplicationJsonSchemaProperties {
    private String chain = "http://qubership.org/schemas/product/qip/chain";
    private String service = "http://qubership.org/schemas/product/qip/service";
    private String contextService = "http://qubership.org/schemas/product/qip/context-service";
    private String mcpService = "http://qubership.org/schemas/product/qip/mcp-service";
    private String specificationGroup = "http://qubership.org/schemas/product/qip/specification-group";
    private String specification = "http://qubership.org/schemas/product/qip/specification";
}

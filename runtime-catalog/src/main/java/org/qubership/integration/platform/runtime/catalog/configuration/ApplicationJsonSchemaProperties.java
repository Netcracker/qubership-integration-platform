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
    private String chain = "http://qubership.org/schemas/product/qip/chain.schema.yaml";
    private String service = "http://qubership.org/schemas/product/qip/service.schema.yaml";
    private String externalService = "http://qubership.org/schemas/product/qip/external-service.schema.yaml";
    private String internalService = "http://qubership.org/schemas/product/qip/internal-service.schema.yaml";
    private String implementedService = "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml";
    private String contextService = "http://qubership.org/schemas/product/qip/context-service.schema.yaml";
    private String mcpService = "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml";
    private String specificationGroup = "http://qubership.org/schemas/product/qip/specification-group.schema.yaml";
    private String apiGroup = "http://qubership.org/schemas/product/qip/api-group.schema.yaml";
    private String specification = "http://qubership.org/schemas/product/qip/specification.schema.yaml";
    private String api = "http://qubership.org/schemas/product/qip/api.schema.yaml";
}

package org.qubership.integration.platform.runtime.catalog.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "cip.json.schemas")
public class ApplicationJsonSchemaProperties {
    private static final String URI_PREFIX =
            "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/";

    // Import also accepts these URIs, which exports wrote before the move to the CIP namespace.
    private static final String LEGACY_URI_PREFIX = "http://qubership.org/schemas/product/qip/";
    private static final String LEGACY_CHAIN = LEGACY_URI_PREFIX + "chain";
    private static final String LEGACY_SERVICE = LEGACY_URI_PREFIX + "service";
    private static final String LEGACY_CONTEXT_SERVICE = LEGACY_URI_PREFIX + "context-service";
    private static final String LEGACY_MCP_SERVICE = LEGACY_URI_PREFIX + "mcp-service";
    private static final String LEGACY_SPECIFICATION_GROUP = LEGACY_URI_PREFIX + "specification-group";
    private static final String LEGACY_SPECIFICATION = LEGACY_URI_PREFIX + "specification";

    private String chain = URI_PREFIX + "chain";
    private String service = URI_PREFIX + "service";
    private String contextService = URI_PREFIX + "context-service";
    private String mcpService = URI_PREFIX + "mcp-service";
    private String specificationGroup = URI_PREFIX + "specification-group";
    private String specification = URI_PREFIX + "specification";

    public boolean isChain(String schema) {
        return chain.equals(schema) || LEGACY_CHAIN.equals(schema);
    }

    public boolean isService(String schema) {
        return service.equals(schema) || LEGACY_SERVICE.equals(schema);
    }

    public boolean isContextService(String schema) {
        return contextService.equals(schema) || LEGACY_CONTEXT_SERVICE.equals(schema);
    }

    public boolean isMcpService(String schema) {
        return mcpService.equals(schema) || LEGACY_MCP_SERVICE.equals(schema);
    }

    public boolean isSpecificationGroup(String schema) {
        return specificationGroup.equals(schema) || LEGACY_SPECIFICATION_GROUP.equals(schema);
    }

    public boolean isSpecification(String schema) {
        return specification.equals(schema) || LEGACY_SPECIFICATION.equals(schema);
    }
}

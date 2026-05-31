package org.qubership.integration.platform.runtime.catalog.service.qcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.integration.platform.runtime.catalog.configuration.QipJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpPackageContent;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpResourceItem;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ImportConfigFactory {

    private final QipJsonSchemaProperties schemas;

    public ImportConfigFactory(QipJsonSchemaProperties schemas) {
        this.schemas = schemas;
    }

    public ImportConfig fromPackageContent(QcpPackageContent packageContent) {
        if (packageContent == null) {
            return empty();
        }
        return fromConfigurationsAndResources(
                packageContent.getConfigurations(),
                packageContent.getResources()
        );
    }

    public ImportConfig fromConfigurationsAndResources(
            List<JsonNode> configurations,
            List<QcpResourceItem> resourceItems
    ) {
        Map<String, JsonNode> chains = new HashMap<>();
        Map<String, JsonNode> services = new HashMap<>();
        Map<String, JsonNode> specificationGroups = new HashMap<>();
        Map<String, JsonNode> specifications = new HashMap<>();
        Map<String, JsonNode> commonVariables = new HashMap<>();
        Map<String, JsonNode> contextServices = new HashMap<>();
        Map<String, byte[]> resources = new HashMap<>();

        if (configurations != null) {
            for (JsonNode configuration : configurations) {
                if (!configuration.has("$schema") || !configuration.has("id")) {
                    continue;
                }
                String schema = configuration.get("$schema").asText();
                String id = configuration.get("id").asText();
                if (schemas.getChain().equals(schema)) {
                    chains.put(id, configuration);
                } else if (schemas.getService().equals(schema)) {
                    services.put(id, configuration);
                } else if (schemas.getSpecificationGroup().equals(schema)) {
                    specificationGroups.put(id, configuration);
                } else if (schemas.getSpecification().equals(schema)) {
                    specifications.put(id, configuration);
                } else if (schemas.getContextService().equals(schema)) {
                    contextServices.put(id, configuration);
                }
            }
        }

        if (resourceItems != null) {
            for (QcpResourceItem resource : resourceItems) {
                String resourceKey = resolveResourceKey(resource);
                if (resourceKey == null || resource.getContent() == null) {
                    continue;
                }
                resources.put(resourceKey, decodeResourceContent(resource));
            }
        }

        return new ImportConfig(
                chains,
                services,
                specificationGroups,
                specifications,
                commonVariables,
                contextServices,
                resources
        );
    }

    public ImportConfig empty() {
        return new ImportConfig(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

    private static String resolveResourceKey(QcpResourceItem resource) {
        if (resource.getName() != null && !resource.getName().isBlank()) {
            return resource.getName();
        }
        if (resource.getLegacyResourceName() != null && !resource.getLegacyResourceName().isBlank()) {
            return resource.getLegacyResourceName();
        }
        return null;
    }

    private static byte[] decodeResourceContent(QcpResourceItem resource) {
        String content = resource.getContent();
        if (Boolean.TRUE.equals(resource.getEncoded())) {
            return Base64.getDecoder().decode(content);
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }
}

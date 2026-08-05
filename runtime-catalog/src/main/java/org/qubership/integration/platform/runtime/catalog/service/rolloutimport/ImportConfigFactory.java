package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportPackageContent;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportResourceItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ImportConfigFactory {

    private final ApplicationJsonSchemaProperties schemas;
    private final ServiceTypeFiles serviceTypeFiles;

    public ImportConfigFactory(ApplicationJsonSchemaProperties schemas, ServiceTypeFiles serviceTypeFiles) {
        this.schemas = schemas;
        this.serviceTypeFiles = serviceTypeFiles;
    }

    public ImportConfig fromPackageContent(RolloutImportPackageContent packageContent) {
        if (packageContent == null) {
            return empty();
        }
        return fromConfigurationsAndResources(
                packageContent.getConfigurations(),
                packageContent.getResources()
        );
    }

    public ImportConfig fromConfigurationsAndResources(
            List<RolloutImportConfigurationItem> configurations,
            List<RolloutImportResourceItem> resourceItems
    ) {
        Map<String, RolloutImportConfigurationItem> chains = new HashMap<>();
        Map<String, RolloutImportConfigurationItem> services = new HashMap<>();
        Map<String, RolloutImportConfigurationItem> specificationGroups = new HashMap<>();
        Map<String, RolloutImportConfigurationItem> specifications = new HashMap<>();
        Map<String, RolloutImportConfigurationItem> commonVariables = new HashMap<>();
        Map<String, RolloutImportConfigurationItem> contextServices = new HashMap<>();
        Map<String, String> resources = new HashMap<>();

        if (configurations != null) {
            for (RolloutImportConfigurationItem configuration : configurations) {
                String schema = configuration.getSchema();
                String id = configuration.getId();
                if (schemas.getChain().equals(schema)) {
                    chains.put(id, configuration);
                } else if (isService(schema)) {
                    services.put(id, configuration);
                } else if (schemas.getSpecificationGroup().equals(schema) || schemas.getApiGroup().equals(schema)) {
                    specificationGroups.put(id, configuration);
                } else if (schemas.getSpecification().equals(schema) || schemas.getApi().equals(schema)) {
                    specifications.put(id, configuration);
                } else if (schemas.getContextService().equals(schema)) {
                    contextServices.put(id, configuration);
                } else {
                    log.warn("Package item {} carries an unknown $schema {} and is not imported", id, schema);
                }
            }
        }

        if (resourceItems != null) {
            for (RolloutImportResourceItem resource : resourceItems) {
                resources.put(resource.getName(), this.decodeResourceContent(resource));
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

    // Since #553 a service states its type in the $schema too, so all four URIs land in the service bucket. Leave the
    // per-type ones out and such an item falls through every branch and is dropped without an error row.
    private boolean isService(String schema) {
        return schemas.getService().equals(schema) || serviceTypeFiles.typeFromSchemaUri(schema).isPresent();
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

    private String decodeResourceContent(RolloutImportResourceItem resource) {
        String content = resource.getResourceContent();
        if (Boolean.TRUE.equals(resource.getEncoded())) {
            byte[] decodedBytes = Base64.getDecoder().decode(content);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        }
        return content;
    }
}

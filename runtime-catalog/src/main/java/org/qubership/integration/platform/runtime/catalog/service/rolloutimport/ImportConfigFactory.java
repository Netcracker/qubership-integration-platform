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
                if (spells(schemas.getChain(), "chain", schema)) {
                    chains.put(id, configuration);
                } else if (isService(schema)) {
                    services.put(id, configuration);
                } else if (spells(schemas.getSpecificationGroup(), "specification-group", schema)
                           || spells(schemas.getApiGroup(), "api-group", schema)) {
                    specificationGroups.put(id, configuration);
                } else if (spells(schemas.getSpecification(), "specification", schema)
                           || spells(schemas.getApi(), "api", schema)) {
                    specifications.put(id, configuration);
                } else if (spells(schemas.getContextService(), "context-service", schema)) {
                    contextServices.put(id, configuration);
                } else if (spells(schemas.getMcpService(), "mcp-service", schema)) {
                    // Known kind, no bucket by design: rollout import does not handle MCP services (nor common
                    // variables, whose items carry no schema this service configures).
                    log.warn("Package item {} is an MCP service, which rollout import does not handle."
                            + " The item is skipped.", id, schema);
                } else {
                    // Nothing this service knows spells the schema — a renamed schema file, or a kind of item this
                    // service has never heard of. Louder than the by-design skip above: if the item was meant for
                    // one of the buckets, its content is dropped here.
                    log.error("Package item {} carries $schema {}, which rollout import does not recognize."
                            + " The item is skipped.", id, schema);
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

    // A service states its type in the $schema too, so all four URIs land in the service bucket. Leave the per-type
    // ones out and such an item falls through every branch and is dropped without an error row.
    private boolean isService(String schema) {
        return spells(schemas.getService(), "service", schema)
               || serviceTypeFiles.typeFromSchemaUri(schema).isPresent();
    }

    /**
     * Configured URI first, the schema file stem second — the same two layers
     * {@code ServiceTypeFiles.typeFromSchemaUri} matches, so a package produced by an installation with rehosted
     * schema URIs routes every kind of item, not only the plain services. Unlike the archive-side
     * {@code isContextOrMCPServiceFile} there is no file name to pair the URI with and no competing claim: the only
     * alternative to the bucket is dropping the item.
     */
    private static boolean spells(String configuredUri, String stem, String schema) {
        return schema != null
               && (configuredUri.equals(schema) || stem.equals(ServiceTypeFiles.schemaFileStem(schema)));
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

package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportPackageContent;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportResourceItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportConfigFactoryTest {

    private static final String CHAIN_SCHEMA = "http://qubership.org/schemas/product/qip/chain.schema.yaml";
    private static final String SERVICE_SCHEMA = "http://qubership.org/schemas/product/qip/service.schema.yaml";
    private static final String CONTEXT_SERVICE_SCHEMA = "http://qubership.org/schemas/product/qip/context-service.schema.yaml";
    private static final String SPECIFICATION_GROUP_SCHEMA = "http://qubership.org/schemas/product/qip/specification-group.schema.yaml";
    private static final String API_GROUP_SCHEMA = "http://qubership.org/schemas/product/qip/api-group.schema.yaml";
    private static final String SPECIFICATION_SCHEMA = "http://qubership.org/schemas/product/qip/specification.schema.yaml";
    private static final String API_SCHEMA = "http://qubership.org/schemas/product/qip/api.schema.yaml";

    private static final String CHAIN_CONFIG_ID = "chain-1";
    private static final String SERVICE_CONFIG_ID = "svc-1";
    private static final String CONTEXT_SERVICE_CONFIG_ID = "ctx-1";
    private static final String RESOURCE_FILE_NAME = "res.txt";

    private ImportConfigFactory factory;

    @BeforeEach
    void setUp() {
        ApplicationJsonSchemaProperties schemas = new ApplicationJsonSchemaProperties();
        factory = new ImportConfigFactory(schemas, new ServiceTypeFiles(schemas));
    }

    @Test
    @DisplayName("fromPackageContent with null returns empty ImportConfig")
    void fromPackageContentNullReturnsEmpty() {
        ImportConfig result = factory.fromPackageContent(null);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("fromPackageContent with null configurations list returns empty maps")
    void fromPackageContentNullConfigurationsReturnsEmptyMaps() {
        RolloutImportPackageContent packageContent = new RolloutImportPackageContent();
        packageContent.setConfigurations(null);

        ImportConfig result = factory.fromPackageContent(packageContent);

        assertThat(result.getChains()).isEmpty();
        assertThat(result.getServices()).isEmpty();
        assertThat(result.getSpecificationGroups()).isEmpty();
        assertThat(result.getSpecifications()).isEmpty();
        assertThat(result.getContextServices()).isEmpty();
    }

    @Test
    @DisplayName("Configuration with chain schema is routed to chains map")
    void chainSchemaRoutedToChains() {
        RolloutImportConfigurationItem item = configItem(CHAIN_CONFIG_ID, CHAIN_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getChains()).containsKey(CHAIN_CONFIG_ID);
        assertThat(result.getServices()).doesNotContainKey(CHAIN_CONFIG_ID);
        assertThat(result.getContextServices()).doesNotContainKey(CHAIN_CONFIG_ID);
    }

    @Test
    @DisplayName("Configuration with service schema is routed to services map")
    void serviceSchemaRoutedToServices() {
        RolloutImportConfigurationItem item = configItem(SERVICE_CONFIG_ID, SERVICE_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getServices()).containsKey(SERVICE_CONFIG_ID);
        assertThat(result.getChains()).doesNotContainKey(SERVICE_CONFIG_ID);
    }

    /**
     * Since #553 a service states its type in the $schema too. Miss a per-type URI here and the item falls through
     * every branch: no file is written for it and the rollout import reports success with the service missing.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("Configuration with a per-type service schema is routed to services map")
    void perTypeServiceSchemaRoutedToServices(IntegrationSystemType type) {
        RolloutImportConfigurationItem item = configItem(
                SERVICE_CONFIG_ID, new ServiceTypeFiles(new ApplicationJsonSchemaProperties()).schemaUri(type));

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getServices()).containsKey(SERVICE_CONFIG_ID);
        assertThat(result.getContextServices()).doesNotContainKey(SERVICE_CONFIG_ID);
    }

    @Test
    @DisplayName("Configuration with specificationGroup schema is routed to specificationGroups map")
    void specGroupSchemaRoutedToSpecGroups() {
        RolloutImportConfigurationItem item = configItem("sg-1", SPECIFICATION_GROUP_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getSpecificationGroups()).containsKey("sg-1");
    }

    @Test
    @DisplayName("Configuration with the renamed api-group schema is also routed to specificationGroups map")
    void apiGroupSchemaRoutedToSpecGroups() {
        RolloutImportConfigurationItem item = configItem("sg-1", API_GROUP_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getSpecificationGroups()).containsKey("sg-1");
    }

    @Test
    @DisplayName("Configuration with specification schema is routed to specifications map")
    void specificationSchemaRoutedToSpecifications() {
        RolloutImportConfigurationItem item = configItem("spec-1", SPECIFICATION_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getSpecifications()).containsKey("spec-1");
    }

    @Test
    @DisplayName("Configuration with api schema is routed to specifications map")
    void apiSchemaRoutedToSpecifications() {
        RolloutImportConfigurationItem item = configItem("api-1", API_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getSpecifications()).containsKey("api-1");
    }

    @Test
    @DisplayName("Configuration with contextService schema is routed to contextServices map")
    void contextServiceSchemaRoutedToContextServices() {
        RolloutImportConfigurationItem item = configItem(CONTEXT_SERVICE_CONFIG_ID, CONTEXT_SERVICE_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getContextServices()).containsKey(CONTEXT_SERVICE_CONFIG_ID);
        assertThat(result.getServices()).doesNotContainKey(CONTEXT_SERVICE_CONFIG_ID);
    }

    /**
     * A package produced by an installation with rehosted schema URIs: the configured layer misses and the schema
     * file stem routes — for every kind, not only the plain services. Before this, such a package imported its
     * plain services and dropped everything else into a skip log.
     */
    @Test
    @DisplayName("a rehosted package routes every kind of item through the schema file stem")
    void rehostedSchemasRouteThroughTheStem() {
        String host = "https://schemas.acme.internal/qip/";
        List<RolloutImportConfigurationItem> items = List.of(
                configItem("chain-1", host + "chain.schema.yaml"),
                configItem("svc-1", host + "service.schema.yaml"),
                configItem("sg-1", host + "api-group.schema.yaml"),
                configItem("api-1", host + "api.schema.yaml"),
                configItem("ctx-1", host + "context-service.schema.yaml"),
                // The truncated legacy form, which stops at the schema file stem itself.
                configItem("ctx-2", host + "context-service"));

        ImportConfig result = factory.fromConfigurationsAndResources(items, null);

        assertThat(result.getChains()).containsKey("chain-1");
        assertThat(result.getServices()).containsKey("svc-1");
        assertThat(result.getSpecificationGroups()).containsKey("sg-1");
        assertThat(result.getSpecifications()).containsKey("api-1");
        assertThat(result.getContextServices()).containsOnlyKeys("ctx-1", "ctx-2");
    }

    /** No bucket by design, so the item lands in no map — under the default and a rehosted URI alike. */
    @Test
    @DisplayName("an MCP item is skipped under either spelling of its schema")
    void mcpItemIsSkippedByDesign() {
        List<RolloutImportConfigurationItem> items = List.of(
                configItem("mcp-1", "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml"),
                configItem("mcp-2", "https://schemas.acme.internal/qip/mcp-service.schema.yaml"));

        ImportConfig result = factory.fromConfigurationsAndResources(items, null);

        assertThat(result.getChains()).isEmpty();
        assertThat(result.getServices()).isEmpty();
        assertThat(result.getSpecificationGroups()).isEmpty();
        assertThat(result.getSpecifications()).isEmpty();
        assertThat(result.getContextServices()).isEmpty();
    }

    @Test
    @DisplayName("Configuration with unknown schema is not added to any map")
    void unknownSchemaNotAddedToAnyMap() {
        RolloutImportConfigurationItem item = configItem("unknown-1", "http://unknown.schema/type");

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getChains()).isEmpty();
        assertThat(result.getServices()).isEmpty();
        assertThat(result.getSpecificationGroups()).isEmpty();
        assertThat(result.getSpecifications()).isEmpty();
        assertThat(result.getContextServices()).isEmpty();
    }

    @Test
    @DisplayName("Resource with encoded=false is stored with content as-is")
    void resourceNotEncodedStoredAsIs() {
        RolloutImportResourceItem resource = resourceItem(RESOURCE_FILE_NAME, "plain content", false);

        ImportConfig result = factory.fromConfigurationsAndResources(null, List.of(resource));

        assertThat(result.getResources()).containsEntry(RESOURCE_FILE_NAME, "plain content");
    }

    @Test
    @DisplayName("Resource with encoded=true is Base64-decoded before storing")
    void resourceEncodedIsDecoded() {
        String original = "decoded content";
        String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
        RolloutImportResourceItem resource = resourceItem(RESOURCE_FILE_NAME, encoded, true);

        ImportConfig result = factory.fromConfigurationsAndResources(null, List.of(resource));

        assertThat(result.getResources()).containsEntry(RESOURCE_FILE_NAME, original);
    }

    @Test
    @DisplayName("empty() returns ImportConfig where isEmpty() is true")
    void emptyReturnsEmptyConfig() {
        ImportConfig result = factory.empty();

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getChains()).isEmpty();
        assertThat(result.getServices()).isEmpty();
        assertThat(result.getResources()).isEmpty();
    }

    private RolloutImportConfigurationItem configItem(String id, String schema) {
        RolloutImportConfigurationItem item = new RolloutImportConfigurationItem();
        item.setId(id);
        item.setSchema(schema);
        return item;
    }

    private RolloutImportResourceItem resourceItem(String name, String content, boolean encoded) {
        RolloutImportResourceItem item = new RolloutImportResourceItem();
        item.setName(name);
        item.setResourceContent(content);
        item.setEncoded(encoded);
        return item;
    }
}

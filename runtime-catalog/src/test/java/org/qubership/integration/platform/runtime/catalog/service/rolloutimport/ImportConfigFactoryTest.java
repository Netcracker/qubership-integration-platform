package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportPackageContent;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportResourceItem;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportConfigFactoryTest {

    private static final String CHAIN_SCHEMA = "http://qubership.org/schemas/product/qip/chain";
    private static final String SERVICE_SCHEMA = "http://qubership.org/schemas/product/qip/service";
    private static final String CONTEXT_SERVICE_SCHEMA = "http://qubership.org/schemas/product/qip/context-service";
    private static final String SPECIFICATION_GROUP_SCHEMA = "http://qubership.org/schemas/product/qip/specification-group";
    private static final String SPECIFICATION_SCHEMA = "http://qubership.org/schemas/product/qip/specification";

    private static final String CHAIN_CONFIG_ID = "chain-1";
    private static final String SERVICE_CONFIG_ID = "svc-1";
    private static final String CONTEXT_SERVICE_CONFIG_ID = "ctx-1";
    private static final String RESOURCE_FILE_NAME = "res.txt";

    private ImportConfigFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ImportConfigFactory(new ApplicationJsonSchemaProperties());
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

    @Test
    @DisplayName("Configuration with specificationGroup schema is routed to specificationGroups map")
    void specGroupSchemaRoutedToSpecGroups() {
        RolloutImportConfigurationItem item = configItem("sg-1", SPECIFICATION_GROUP_SCHEMA);

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
    @DisplayName("Configuration with contextService schema is routed to contextServices map")
    void contextServiceSchemaRoutedToContextServices() {
        RolloutImportConfigurationItem item = configItem(CONTEXT_SERVICE_CONFIG_ID, CONTEXT_SERVICE_SCHEMA);

        ImportConfig result = factory.fromConfigurationsAndResources(List.of(item), null);

        assertThat(result.getContextServices()).containsKey(CONTEXT_SERVICE_CONFIG_ID);
        assertThat(result.getServices()).doesNotContainKey(CONTEXT_SERVICE_CONFIG_ID);
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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTypeFilesTest {

    private static final String SERVICE_ID = "system-1";
    private static final String APP_NAME = "qip";

    private final ApplicationJsonSchemaProperties schemas = new ApplicationJsonSchemaProperties();
    private final ServiceTypeFiles serviceTypeFiles = new ServiceTypeFiles(schemas);

    // --- file name -> type -----------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void resolvesEachTypeFromTheFileNameItExportsTo(IntegrationSystemType type) {
        String fileName = SERVICE_ID + serviceTypeFiles.postfix(type) + APP_NAME + ".yaml";

        assertEquals(Optional.of(type), serviceTypeFiles.typeFromFileName(fileName));
    }

    /**
     * The pre-#553 name and the legacy flat name both state no type — the document field is the only source for them,
     * which is why {@code content.integrationSystemType} stays a resolution fallback.
     */
    @ParameterizedTest
    @ValueSource(strings = {"system-1.service.qip.yaml", "service-system-1.yaml"})
    void resolvesNoTypeFromANameThatStatesNone(String fileName) {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromFileName(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "context-1.context-service.qip.yaml",
            "context-service-context-1.yaml",
            "mcp-1.mcp-service.qip.yaml",
            "mcp-service-mcp-1.yaml"})
    void neverTakesAContextOrMcpServiceForAPlainOne(String fileName) {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromFileName(fileName));
    }

    @Test
    void resolvesNoTypeFromAMissingFileName() {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromFileName(null));
    }

    // --- type -> postfix and URI -----------------------------------------------------------------------------------

    @Test
    void spellsEachTypeInTheFileNameAsTheSchemasDo() {
        assertEquals(".external-service.", serviceTypeFiles.postfix(IntegrationSystemType.EXTERNAL));
        assertEquals(".internal-service.", serviceTypeFiles.postfix(IntegrationSystemType.INTERNAL));
        assertEquals(".implemented-service.", serviceTypeFiles.postfix(IntegrationSystemType.IMPLEMENTED));
    }

    /** A postfix must not match {@code .service.}, or import discovery cannot tell the two formats apart. */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void keepsEveryPostfixDistinctFromThePlainServiceOne(IntegrationSystemType type) {
        assertTrue(serviceTypeFiles.postfix(type).endsWith("-service."),
                "the -service suffix is what keeps the name out of the plain service scan");
        assertEquals(3, ServiceTypeFiles.postfixes().size());
        assertTrue(ServiceTypeFiles.postfixes().contains(serviceTypeFiles.postfix(type)));
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void readsEachSchemaUriBackAsItsType(IntegrationSystemType type) {
        assertEquals(Optional.of(type), serviceTypeFiles.typeFromSchemaUri(serviceTypeFiles.schemaUri(type)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/service.schema.yaml",
            "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml",
            "http://qubership.org/schemas/product/acme/service"})
    void readsNoTypeFromASchemaUriThatStatesNone(String schemaUri) {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(schemaUri));
    }

    @Test
    void readsNoTypeFromAMissingSchemaUri() {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(null));
    }

    @Test
    void refusesAMissingType() {
        assertThrows(NullPointerException.class, () -> serviceTypeFiles.postfix(null));
        assertThrows(NullPointerException.class, () -> serviceTypeFiles.schemaUri(null));
    }

    @Test
    void takesTheSchemaUrisFromConfiguration() {
        ApplicationJsonSchemaProperties overridden = new ApplicationJsonSchemaProperties();
        overridden.setExternalService("http://example.org/external.yaml");

        ServiceTypeFiles configured = new ServiceTypeFiles(overridden);

        assertEquals("http://example.org/external.yaml", configured.schemaUri(IntegrationSystemType.EXTERNAL));
        assertEquals(Optional.of(IntegrationSystemType.EXTERNAL),
                configured.typeFromSchemaUri("http://example.org/external.yaml"));
    }

    // --- configuration vs the schemas themselves -------------------------------------------------------------------

    /**
     * Nothing else keeps the two in step: this module has no dependency on {@code qip-schemas}, so a renamed schema
     * would only surface as a document the revert migrations quietly stop matching.
     */
    @Test
    void configuresTheUriEachSchemaDeclaresAsItsId() throws IOException {
        Map<String, String> configuredUrisBySchemaFile = Map.of(
                "service.schema.yaml", schemas.getService(),
                "external-service.schema.yaml", schemas.getExternalService(),
                "internal-service.schema.yaml", schemas.getInternalService(),
                "implemented-service.schema.yaml", schemas.getImplementedService(),
                "context-service.schema.yaml", schemas.getContextService(),
                "mcp-service.schema.yaml", schemas.getMcpService());

        for (Map.Entry<String, String> entry : configuredUrisBySchemaFile.entrySet()) {
            assertEquals(declaredId(entry.getKey()), entry.getValue(),
                    entry.getKey() + " declares a different $id than this service is configured with");
        }
    }

    private static String declaredId(String schemaFileName) throws IOException {
        URL url = ServiceTypeFilesTest.class.getResource("/qip-model/" + schemaFileName);
        assertNotNull(url, schemaFileName + " is not on the test classpath. "
                + "Check the <testResource> for schemas/src/main/resources/qip-model in runtime-catalog/pom.xml.");
        Path path;
        try {
            path = Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        JsonNode schema = new YAMLMapper().readTree(Files.readString(path));
        return schema.path("$id").asText();
    }
}

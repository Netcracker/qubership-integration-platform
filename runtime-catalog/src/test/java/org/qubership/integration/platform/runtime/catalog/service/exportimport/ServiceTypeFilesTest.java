package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        String fileName = SERVICE_ID + ServiceTypeFiles.postfix(type) + APP_NAME + ".yaml";

        assertEquals(Optional.of(type), ServiceTypeFiles.typeFromFileName(fileName));
    }

    /**
     * The pre-#553 name and the legacy flat name both state no type — the document field is the only source for them,
     * which is why {@code content.integrationSystemType} stays a resolution fallback.
     */
    @ParameterizedTest
    @ValueSource(strings = {"system-1.service.qip.yaml", "service-system-1.yaml"})
    void resolvesNoTypeFromANameThatStatesNone(String fileName) {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "context-1.context-service.qip.yaml",
            "context-service-context-1.yaml",
            "mcp-1.mcp-service.qip.yaml",
            "mcp-service-mcp-1.yaml"})
    void neverTakesAContextOrMcpServiceForAPlainOne(String fileName) {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(fileName));
    }

    @Test
    void resolvesNoTypeFromAMissingFileName() {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(null));
    }

    /** Two postfixes state no single type, so the name resolves to none rather than to the first enum constant. */
    @Test
    void resolvesNoTypeFromANameCarryingTwoPostfixes() {
        assertEquals(Optional.empty(),
                ServiceTypeFiles.typeFromFileName("system-1.external-service.internal-service.qip.yaml"));
    }

    // --- type -> postfix and URI -----------------------------------------------------------------------------------

    @Test
    void spellsEachTypeInTheFileNameAsTheSchemasDo() {
        assertEquals(".external-service.", ServiceTypeFiles.postfix(IntegrationSystemType.EXTERNAL));
        assertEquals(".internal-service.", ServiceTypeFiles.postfix(IntegrationSystemType.INTERNAL));
        assertEquals(".implemented-service.", ServiceTypeFiles.postfix(IntegrationSystemType.IMPLEMENTED));
    }

    /** A postfix must not match {@code .service.}, or import discovery cannot tell the two formats apart. */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void keepsEveryPostfixDistinctFromThePlainServiceOne(IntegrationSystemType type) {
        assertTrue(ServiceTypeFiles.postfix(type).endsWith("-service."),
                "the -service suffix is what keeps the name out of the plain service scan");
        assertEquals(3, ServiceTypeFiles.postfixes().size());
        assertTrue(ServiceTypeFiles.postfixes().contains(ServiceTypeFiles.postfix(type)));
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
        assertThrows(NullPointerException.class, () -> ServiceTypeFiles.postfix(null));
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

    // --- per-type rules vs the schemas themselves ------------------------------------------------------------------

    /**
     * The enum and the three type schemas state one rule twice, and nothing compares them. A schema that drifts wider
     * than the enum accepts offline a document the backend rejects at import — the failure #553 set out to remove.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void allowsExactlyTheProtocolsItsSchemaEnumerates(IntegrationSystemType type) throws IOException {
        JsonNode schema = readSchema(schemaFileName(type));
        // Read where the constraint is applied, not only where it is declared: an applied $ref that stops pointing at
        // the enum leaves the declaration in place while the schema constrains nothing.
        assertEquals("#/definitions/Protocol",
                schema.path("properties").path("content").path("properties").path("protocol").path("$ref").asText(),
                schemaFileName(type) + " no longer applies its own Protocol enum to content.protocol");
        Set<String> fromSchema = new HashSet<>();
        schema.path("definitions").path("Protocol").path("enum")
                .forEach(protocol -> fromSchema.add(protocol.asText()));
        Set<String> fromEnum = type.allowedProtocols().stream()
                .map(OperationProtocol::name)
                .collect(Collectors.toSet());

        assertEquals(fromEnum, fromSchema, schemaFileName(type)
                + " enumerates different protocols than IntegrationSystemType." + type + ".allowedProtocols()");
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void limitsEnvironmentsExactlyAsItsSchemaDoes(IntegrationSystemType type) throws IOException {
        JsonNode maxItems = readSchema(schemaFileName(type))
                .path("properties").path("content").path("properties").path("environments").path("maxItems");
        // An absent maxItems is how a schema spells an unbounded list; the enum spells it Integer.MAX_VALUE.
        int fromSchema = maxItems.isMissingNode() ? Integer.MAX_VALUE : maxItems.asInt();

        assertEquals(type.maxEnvironments(), fromSchema, schemaFileName(type)
                + " states a different environment limit than IntegrationSystemType." + type + ".maxEnvironments()");
    }

    /** The file-name postfix and the schema file spell the type the same way, so one names the other. */
    private static String schemaFileName(IntegrationSystemType type) {
        return ServiceTypeFiles.postfix(type).replace(".", "") + ".schema.yaml";
    }

    private static String declaredId(String schemaFileName) throws IOException {
        return readSchema(schemaFileName).path("$id").asText();
    }

    private static JsonNode readSchema(String schemaFileName) throws IOException {
        URL url = ServiceTypeFilesTest.class.getResource("/qip-model/" + schemaFileName);
        assertNotNull(url, schemaFileName + " is not on the test classpath. "
                + "Check the <testResource> for schemas/src/main/resources/qip-model in runtime-catalog/pom.xml.");
        Path path;
        try {
            path = Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return new YAMLMapper().readTree(Files.readString(path));
    }
}

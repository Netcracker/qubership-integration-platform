package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("each type resolves from the file name it exports to")
    void typeFromExportedFileName(IntegrationSystemType type) {
        String fileName = SERVICE_ID + ServiceTypeFiles.postfix(type) + APP_NAME + ".yaml";

        assertEquals(Optional.of(type), ServiceTypeFiles.typeFromFileName(fileName));
    }

    /** The older names state no type, which is why {@code content.integrationSystemType} stays a fallback. */
    @ParameterizedTest
    @ValueSource(strings = {"system-1.service.qip.yaml", "service-system-1.yaml"})
    @DisplayName("a name that states no type resolves to none")
    void typeFromNameStatingNone(String fileName) {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "context-1.context-service.qip.yaml",
            "context-service-context-1.yaml",
            "mcp-1.mcp-service.qip.yaml",
            "mcp-service-mcp-1.yaml"})
    @DisplayName("a context or MCP service name is never taken for a plain one")
    void typeFromContextOrMcpName(String fileName) {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(fileName));
    }

    @Test
    @DisplayName("a missing file name resolves to no type")
    void typeFromNullFileName() {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(null));
    }

    /**
     * The app prefix lands between the postfix and the extension, so a prefix carrying another postfix used to make
     * the name state two types, and a two-type name resolved to none. That turned a legitimate export into a file no
     * import could type.
     */
    @Test
    @DisplayName("a postfix in the app prefix does not change the type the name states")
    void typeFromNameWithAPostfixInTheAppPrefix() {
        assertEquals(Optional.of(IntegrationSystemType.EXTERNAL),
                ServiceTypeFiles.typeFromFileName("system-1.external-service.internal-service.qip.yaml"));
    }

    /**
     * The one position read is the segment right after the id, so a dotted id shifts the postfix out of it. Such a
     * name is never written: {@code ExportImportUtils.generateMainSystemFileExportName} refuses the id instead.
     */
    @Test
    @DisplayName("a postfix anywhere but right after the id states no type")
    void typeFromNameWithAPostfixOutOfPosition() {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName("system.1.external-service.qip.yaml"));
    }

    /**
     * The legacy flat name states the id whole, dots and all, and states no type. It is told apart from a
     * current-format name by the postfix, so the id it holds must not spell one — which is why the export refuses such
     * an id in the flat format.
     */
    @ParameterizedTest
    @ValueSource(strings = {"service-system-1.yaml", "service-system.1.yaml"})
    @DisplayName("a legacy flat name states no type")
    void typeFromLegacyFlatName(String fileName) {
        assertEquals(Optional.empty(), ServiceTypeFiles.typeFromFileName(fileName));
    }

    /**
     * Autodiscovery mints a service id from the Kubernetes service name, so an id wearing the flat prefix is ordinary.
     * The postfix is what tells the two name formats apart, so such a name states its type like any other.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("a name whose id wears the legacy flat prefix states its type")
    void typeFromNameWhoseIdWearsTheLegacyFlatPrefix(IntegrationSystemType type) {
        String fileName = "service-orders" + ServiceTypeFiles.postfix(type) + APP_NAME + ".yaml";

        assertEquals(Optional.of(type), ServiceTypeFiles.typeFromFileName(fileName));
    }

    // --- type -> postfix and URI -----------------------------------------------------------------------------------

    @Test
    @DisplayName("each type is spelled in the file name the way the schemas spell it")
    void postfixPerType() {
        assertEquals(".external-service.", ServiceTypeFiles.postfix(IntegrationSystemType.EXTERNAL));
        assertEquals(".internal-service.", ServiceTypeFiles.postfix(IntegrationSystemType.INTERNAL));
        assertEquals(".implemented-service.", ServiceTypeFiles.postfix(IntegrationSystemType.IMPLEMENTED));
    }

    /** A postfix must not match {@code .service.}, or import discovery cannot tell the two formats apart. */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("every postfix stays distinct from the plain service one")
    void postfixIsDistinctFromPlainService(IntegrationSystemType type) {
        assertTrue(ServiceTypeFiles.postfix(type).endsWith("-service."),
                "the -service suffix is what keeps the name out of the plain service scan");
        assertEquals(3, ServiceTypeFiles.postfixes().size());
        assertTrue(ServiceTypeFiles.postfixes().contains(ServiceTypeFiles.postfix(type)));
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("each schema URI reads back as its type")
    void typeFromSchemaUriRoundTrips(IntegrationSystemType type) {
        assertEquals(Optional.of(type), serviceTypeFiles.typeFromSchemaUri(serviceTypeFiles.schemaUri(type)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/service.schema.yaml",
            "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml",
            "http://qubership.org/schemas/product/acme/service"})
    @DisplayName("a schema URI that states no type reads back as none")
    void typeFromSchemaUriStatingNone(String schemaUri) {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(schemaUri));
    }

    @Test
    @DisplayName("a missing schema URI reads back as no type")
    void typeFromNullSchemaUri() {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(null));
    }

    @Test
    @DisplayName("a missing type is refused")
    void nullTypeIsRefused() {
        assertThrows(NullPointerException.class, () -> ServiceTypeFiles.postfix(null));
        assertThrows(NullPointerException.class, () -> serviceTypeFiles.schemaUri(null));
    }

    @Test
    @DisplayName("the schema URIs come from configuration")
    void schemaUrisComeFromConfiguration() {
        ApplicationJsonSchemaProperties overridden = new ApplicationJsonSchemaProperties();
        overridden.setExternalService("http://example.org/external.yaml");

        ServiceTypeFiles configured = new ServiceTypeFiles(overridden);

        assertEquals("http://example.org/external.yaml", configured.schemaUri(IntegrationSystemType.EXTERNAL));
        assertEquals(Optional.of(IntegrationSystemType.EXTERNAL),
                configured.typeFromSchemaUri("http://example.org/external.yaml"));
    }

    // --- which kind of service a file holds ------------------------------------------------------------------------

    /**
     * {@code service-ctx.context-service.qip.yaml} is the context file of {@code service-ctx} and the legacy flat
     * plain-service file of {@code ctx.context-service.qip}, so two scans discover it and only the document tells them
     * apart.
     */
    @Test
    @DisplayName("a context or MCP file is recognized by its name and its document together")
    void contextAndMcpFilesAreRecognized() {
        assertTrue(serviceTypeFiles.isContextOrMCPServiceFile(
                "service-ctx.context-service.qip.yaml", documentStating(schemas.getContextService())));
        assertTrue(serviceTypeFiles.isContextOrMCPServiceFile(
                "service-mcp.mcp-service.qip.yaml", documentStating(schemas.getMcpService())));
    }

    /** Every other combination stays with the plain-service import, which is where an unclaimed file belongs. */
    @ParameterizedTest(name = "{0} stating {1}")
    @MethodSource("filesOfNoOtherKind")
    @DisplayName("a file no other import has is left to the plain-service one")
    void filesOfNoOtherKindAreLeftToThePlainServiceImport(String fileName, String schemaUri) {
        assertFalse(serviceTypeFiles.isContextOrMCPServiceFile(fileName, documentStating(schemaUri)));
    }

    @Test
    @DisplayName("a missing name or document states no other kind")
    void nullNameOrDocumentStatesNoOtherKind() {
        assertFalse(serviceTypeFiles.isContextOrMCPServiceFile(null, documentStating(schemas.getContextService())));
        assertFalse(serviceTypeFiles.isContextOrMCPServiceFile("service-ctx.context-service.qip.yaml", null));
    }

    @Test
    @DisplayName("the context and MCP URIs come from configuration as well")
    void contextAndMcpUrisComeFromConfiguration() {
        ApplicationJsonSchemaProperties overridden = new ApplicationJsonSchemaProperties();
        overridden.setContextService("http://example.org/context.yaml");

        ServiceTypeFiles configured = new ServiceTypeFiles(overridden);

        assertTrue(configured.isContextOrMCPServiceFile(
                "service-ctx.context-service.qip.yaml", documentStating("http://example.org/context.yaml")));
        assertFalse(configured.isContextOrMCPServiceFile(
                "service-ctx.context-service.qip.yaml", documentStating(schemas.getContextService())));
    }

    // --- configuration vs the schemas themselves -------------------------------------------------------------------

    /**
     * Nothing else keeps the two in step: this module has no dependency on {@code qip-schemas}, so a renamed schema
     * would only surface as a document the revert migrations quietly stop matching.
     */
    @Test
    @DisplayName("each schema is configured with the URI it declares as its $id")
    void configuredUriMatchesTheSchemaId() throws IOException {
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
     * than the enum accepts offline a document the backend rejects at import.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("the type allows exactly the protocols its schema enumerates")
    void protocolsMatchTheSchema(IntegrationSystemType type) throws IOException {
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
    @DisplayName("the type limits environments exactly as its schema does")
    void environmentLimitMatchesTheSchema(IntegrationSystemType type) throws IOException {
        JsonNode maxItems = readSchema(schemaFileName(type))
                .path("properties").path("content").path("properties").path("environments").path("maxItems");
        // An absent maxItems is how a schema spells an unbounded list; the enum spells it Integer.MAX_VALUE.
        int fromSchema = maxItems.isMissingNode() ? Integer.MAX_VALUE : maxItems.asInt();

        assertEquals(type.maxEnvironments(), fromSchema, schemaFileName(type)
                + " states a different environment limit than IntegrationSystemType." + type + ".maxEnvironments()");
    }

    /**
     * The name shapes that are nobody else's: a flat plain-service file whose id spells another kind's postfix, one
     * carrying no {@code $schema} at all, a name and a {@code $schema} naming different kinds, a per-type plain name,
     * and a flat name no context scan discovers, which a skip would drop for good.
     */
    private static Stream<Arguments> filesOfNoOtherKind() {
        ApplicationJsonSchemaProperties defaults = new ApplicationJsonSchemaProperties();
        return Stream.of(
                Arguments.of("service-ctx.context-service.qip.yaml", defaults.getService()),
                Arguments.of("service-ctx.context-service.qip.yaml", null),
                Arguments.of("service-ctx.context-service.qip.yaml", defaults.getMcpService()),
                Arguments.of("system-1.external-service.qip.yaml", defaults.getContextService()),
                Arguments.of("service-ctx.yaml", defaults.getContextService()));
    }

    private static JsonNode documentStating(String schemaUri) {
        ObjectNode document = new YAMLMapper().createObjectNode();
        if (schemaUri != null) {
            document.put("$schema", schemaUri);
        }
        return document;
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

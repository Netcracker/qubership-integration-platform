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

    // --- $schema -> type -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("each schema URI reads back as its type")
    void typeFromSchemaUriRoundTrips(IntegrationSystemType type) {
        assertEquals(Optional.of(type), serviceTypeFiles.typeFromSchemaUri(serviceTypeFiles.schemaUri(type)));
    }

    /**
     * The second layer, which is what carries a document between two installations. Every one of these is a URI this
     * installation is configured with nowhere, and the schema's own file name is all that is left to read.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://schemas.acme.internal/qip/external-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/external-service",
            "http://qubership.org/schemas/product/qip/external-service.schema.json",
            "external-service.schema.yaml"})
    @DisplayName("a foreign URI stating the schema's own file name reads back as its type")
    void typeFromForeignSchemaUri(String schemaUri) {
        assertEquals(Optional.of(IntegrationSystemType.EXTERNAL), serviceTypeFiles.typeFromSchemaUri(schemaUri));
    }

    /**
     * The two layers agree by default, and this is the only thing that says so: the file-name layer is spelled out in
     * {@code ServiceTypeFiles} and the URIs come from configuration, so a schema renamed on one side and not the other
     * would leave every cross-installation document untyped, in silence.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("the file-name layer reads back the default URI of every type")
    void theFileNameLayerReadsBackTheDefaultUris(IntegrationSystemType type) {
        String defaultUri = serviceTypeFiles.schemaUri(type);
        ApplicationJsonSchemaProperties elsewhere = new ApplicationJsonSchemaProperties();
        elsewhere.setExternalService("http://example.org/one.yaml");
        elsewhere.setInternalService("http://example.org/two.yaml");
        elsewhere.setImplementedService("http://example.org/three.yaml");

        // Configured away from every default, so only the file-name layer can answer.
        assertEquals(Optional.of(type), new ServiceTypeFiles(elsewhere).typeFromSchemaUri(defaultUri));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/service.schema.yaml",
            "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/svc-ext.schema.yaml",
            "http://qubership.org/schemas/product/acme/service"})
    @DisplayName("a schema URI that states no type reads back as none")
    void typeFromSchemaUriStatingNone(String schemaUri) {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(schemaUri));
    }

    /**
     * The stem is read off the path alone. A fragment is the one shape that could mistype rather than miss a type: it
     * carries slashes of its own, so its last word passed for the schema's file name.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/service.schema.yaml#/defs/external-service",
            "http://qubership.org/schemas/product/qip/service.schema.yaml?ref=external-service"})
    @DisplayName("a type spelled in a fragment or a query is not the schema's file name")
    void typeSpelledOutsideTheSchemaFileName(String schemaUri) {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(schemaUri));
    }

    /** The same cut the other way round: a fragment or a query behind the file name leaves the type readable. */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://schemas.acme.internal/qip/external-service.schema.yaml?v=1.2",
            "https://schemas.acme.internal/qip/external-service#frag",
            "https://schemas.acme.internal/qip/external-service.schema.yaml#/definitions/Service"})
    @DisplayName("a fragment or a query behind the schema's file name reads back as its type")
    void typeFromSchemaUriCarryingAFragmentOrQuery(String schemaUri) {
        assertEquals(Optional.of(IntegrationSystemType.EXTERNAL), serviceTypeFiles.typeFromSchemaUri(schemaUri));
    }

    /**
     * The configured layer answers first, and this is the only thing that says so — swap the two and every other case
     * here still passes. An installation configured with a URI whose file name spells another type reads its own
     * documents by its own configuration, not by the name it happened to host them under.
     */
    @Test
    @DisplayName("the configured URI wins over the file name it spells")
    void theConfiguredLayerWinsOverTheFileNameLayer() {
        String crossedUri = "https://schemas.acme.internal/qip/external-service.schema.yaml";
        ApplicationJsonSchemaProperties crossed = new ApplicationJsonSchemaProperties();
        crossed.setInternalService(crossedUri);

        assertEquals(Optional.of(IntegrationSystemType.INTERNAL),
                new ServiceTypeFiles(crossed).typeFromSchemaUri(crossedUri));
    }

    @Test
    @DisplayName("a missing or empty schema URI reads back as no type")
    void typeFromNullOrEmptySchemaUri() {
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(null));
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromSchemaUri(""));
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("a document is typed by the $schema it states")
    void typeFromDocumentSchema(IntegrationSystemType type) {
        assertEquals(Optional.of(type),
                serviceTypeFiles.typeFromDocumentSchema(documentStating(serviceTypeFiles.schemaUri(type))));
    }

    /** No {@code $schema}, or one that is not a string, leaves the caller to its fallback rather than throwing. */
    @Test
    @DisplayName("a document stating no $schema states no type")
    void typeFromDocumentStatingNoSchema() {
        ObjectNode nonTextual = new YAMLMapper().createObjectNode();
        nonTextual.putArray("$schema");

        assertEquals(Optional.empty(), serviceTypeFiles.typeFromDocumentSchema(documentStating(null)));
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromDocumentSchema(nonTextual));
        assertEquals(Optional.empty(), serviceTypeFiles.typeFromDocumentSchema(null));
    }

    @Test
    @DisplayName("a missing type is refused")
    void nullTypeIsRefused() {
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

    @Test
    @DisplayName("every plain service schema URI is offered as the remedy")
    void plainServiceSchemaUrisAreTheConfiguredThree() {
        assertEquals(Set.of(schemas.getExternalService(), schemas.getInternalService(), schemas.getImplementedService()),
                Set.copyOf(serviceTypeFiles.plainServiceSchemaUris()));
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
        assertFalse(ServiceTypeFiles.statesContextOrMCPPostfix(null));
    }

    /**
     * The name half on its own, which is what keeps discovery from reading every document in the archive: a file
     * neither kind's export could have written is answered from the name and left to the plain-service import unread.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "ctx-1.context-service.qip.yaml",
            "service-ctx.context-service.qip.yaml",
            "mcp-1.mcp-service.qip.yaml",
            "service-mcp.mcp-service.qip.yaml"})
    @DisplayName("a name a context or MCP export writes needs its document read")
    void contextAndMcpNamesNeedTheirDocument(String fileName) {
        assertTrue(ServiceTypeFiles.statesContextOrMCPPostfix(fileName));
    }

    /** The postfix counts only right after the id here as well, so an app prefix spelling one states nothing. */
    @ParameterizedTest
    @ValueSource(strings = {
            "system-1.external-service.qip.yaml",
            "system-1.internal-service.qip.yaml",
            "system-1.implemented-service.qip.yaml",
            "system-1.service.qip.yaml",
            "service-system-1.yaml",
            "grp-1.api-group.context-service.yaml"})
    @DisplayName("every other name is left to the plain-service import unread")
    void plainServiceNamesNeedNoDocument(String fileName) {
        assertFalse(ServiceTypeFiles.statesContextOrMCPPostfix(fileName));
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

    /** The schema each type is configured with, named by its own file, which is what the URI ends in. */
    private String schemaFileName(IntegrationSystemType type) {
        String uri = serviceTypeFiles.schemaUri(type);
        return uri.substring(uri.lastIndexOf('/') + 1);
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

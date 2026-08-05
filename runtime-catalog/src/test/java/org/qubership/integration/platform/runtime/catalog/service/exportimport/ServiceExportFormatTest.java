package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.ServiceDocumentMatcher;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.APP_NAME;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.PRE553_CURRENT;

/**
 * What a service export writes since #553: the file name and the {@code $schema} state the type, the document does not,
 * and the legacy format is untouched. Every assertion runs against the golden corpus in
 * {@link GoldenServiceCorpus}, so a change to the exported format shows up as a diff rather than as a green suite.
 */
class ServiceExportFormatTest {

    private static final ApplicationJsonSchemaProperties SCHEMAS = new ApplicationJsonSchemaProperties();

    private final YAMLMapper mapper = GoldenServiceCorpus.mapper();

    // --- the current format ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "EXTERNAL, svc-external, svc-external.external-service.qip.yaml, external-service.schema.yaml",
            "INTERNAL, svc-internal, svc-internal.internal-service.qip.yaml, internal-service.schema.yaml",
            "IMPLEMENTED, svc-implemented, svc-implemented.implemented-service.qip.yaml, implemented-service.schema.yaml"})
    void eachTypeExportsUnderItsOwnNameAndSchema(
            IntegrationSystemType type, String serviceId, String fileName, String schemaFile) {
        assertEquals(fileName, ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false, type));

        Path exported = GoldenServiceCorpus.serviceFile(POST553, serviceId);
        assertEquals(fileName, exported.getFileName().toString());

        ObjectNode document = GoldenServiceCorpus.read(exported);
        assertEquals("http://qubership.org/schemas/product/qip/" + schemaFile, document.path("$schema").asText());
        assertFalse(document.path("content").has("integrationSystemType"),
                "the type is stated by the name and the $schema, not by the document");
    }

    /** The type must be gone from the whole document, not only from the place the type used to sit. */
    @Test
    void noPost553DocumentMentionsTheTypeField() {
        Map<String, ObjectNode> documents = GoldenServiceCorpus.documentsOf(GoldenServiceCorpus.set(POST553));

        assertFalse(documents.isEmpty(), "the post-#553 golden set is empty");
        documents.forEach((name, document) ->
                assertFalse(containsKey(document, "integrationSystemType"), name + " still carries the type field"));
    }

    /** The archive layout around the service file is unaffected: only the service file was renamed. */
    @Test
    void onlyTheServiceFileNamesChanged() {
        assertEquals(
                GoldenServiceCorpus.fileNames(PRE553_CURRENT).stream()
                        .map(name -> name
                                .replace("svc-external.service.", "svc-external.external-service.")
                                .replace("svc-internal.service.", "svc-internal.internal-service.")
                                .replace("svc-implemented.service.", "svc-implemented.implemented-service."))
                        .sorted()
                        .toList(),
                GoldenServiceCorpus.fileNames(POST553));
    }

    /**
     * {@code @JsonProperty(WRITE_ONLY)} suppresses the field on export only. A pre-#553 archive still binds it, and it
     * is the only place those files state a type: their {@code .service.} name carries none.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "svc-external, EXTERNAL",
            "svc-internal, INTERNAL",
            "svc-implemented, IMPLEMENTED"})
    void aPre553ArchiveStillImportsWithItsTypeField(String serviceId, IntegrationSystemType type) {
        Path serviceFile = GoldenServiceCorpus.serviceFile(PRE553_CURRENT, serviceId);
        assertTrue(serviceFile.getFileName().toString().contains(ExportImportConstants.SERVICE_YAML_NAME_POSTFIX),
                "the pre-#553 set must keep the old name, or this proves nothing");

        IntegrationSystem imported = deserializer().deserializeSystem(serviceFile.toFile());

        assertEquals(type, imported.getIntegrationSystemType());
    }

    // --- the legacy format -------------------------------------------------------------------------------------------

    /**
     * The no-regression claim, measured: the {@value GoldenServiceCorpus#LEGACY_FLAT} set was captured from the
     * exporter before #553 changed it. Byte equality is unattainable — {@code ObjectNode} is insertion-ordered and
     * {@code V105RevertMigration} appends the restored keys last — so the comparison is per document and
     * order-insensitive.
     */
    @Test
    void theLegacyExportIsUnchangedBy553(@TempDir Path directory) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(true), directory);

        assertEquals(GoldenServiceCorpus.fileNames(LEGACY_FLAT), GoldenServiceCorpus.relativeFileNames(directory),
                "the legacy format keeps the flat file names #553 never touched");
        Map<String, ObjectNode> golden = GoldenServiceCorpus.documentsOf(GoldenServiceCorpus.set(LEGACY_FLAT));
        Map<String, ObjectNode> actual = GoldenServiceCorpus.documentsOf(directory);
        golden.forEach((name, document) -> assertEquals(document, actual.get(name), name + " changed"));
    }

    /**
     * The mirror of {@link #theLegacyExportIsUnchangedBy553}, and what makes {@value GoldenServiceCorpus#POST553} a
     * regression pin rather than a committed tree nothing regenerates: everything else in this class reads that set,
     * so without this the exporter could drift away from it with the whole suite green.
     */
    @Test
    void theCurrentExportStillMatchesTheRecordedFormat(@TempDir Path directory) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(false), directory);

        assertEquals(GoldenServiceCorpus.fileNames(POST553), GoldenServiceCorpus.relativeFileNames(directory),
                "the exporter writes different file names than the recorded post-#553 set");
        Map<String, ObjectNode> golden = GoldenServiceCorpus.documentsOf(GoldenServiceCorpus.set(POST553));
        Map<String, ObjectNode> actual = GoldenServiceCorpus.documentsOf(directory);
        golden.forEach((name, document) -> assertEquals(document, actual.get(name), name + " changed"));
    }

    /**
     * The restored type is what makes the legacy file importable at all: the flat name states none, and
     * {@code ServiceDeserializer} refuses a service that states its type nowhere.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "svc-external, EXTERNAL",
            "svc-internal, INTERNAL",
            "svc-implemented, IMPLEMENTED"})
    void theLegacyExportStatesTheTypeInTheDocument(String serviceId, IntegrationSystemType type) {
        Path serviceFile = GoldenServiceCorpus.serviceFile(LEGACY_FLAT, serviceId);

        assertEquals("service-" + serviceId + ".yaml", serviceFile.getFileName().toString());
        assertEquals(type.name(), GoldenServiceCorpus.read(serviceFile).path("integrationSystemType").asText());
        assertEquals(type, deserializer().deserializeSystem(serviceFile.toFile()).getIntegrationSystemType());
    }

    // --- the revert chain over a real export -------------------------------------------------------------------------

    /**
     * Task 8 proved the chain on a hand-built document. This runs it on a real post-#553 export: the per-type
     * {@code $schema} that reaches {@code V105RevertMigration} is the one the exporter actually writes, and the result
     * has to be the legacy document captured before #553.
     */
    @ParameterizedTest
    @CsvSource({"svc-external", "svc-internal", "svc-implemented"})
    void theRevertChainTurnsARealPost553ExportBackIntoTheLegacyDocument(String serviceId) {
        ObjectNode exported = GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(POST553, serviceId));

        ObjectNode reverted = GoldenServiceCorpus.migrationService(true).revertMigrationIfNeeded(exported);

        assertEquals(GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(LEGACY_FLAT, serviceId)), reverted);
    }

    /**
     * V104 and V103 are gated on {@link ServiceDocumentMatcher} and run on V105's result, so the {@code $schema}
     * restore keeps them alive. Proven on a real export carrying the new URI: the api-group list is inlined the way a
     * pre-V104 archive carried it, and the rename has to still happen.
     */
    @Test
    void theRevertChainStillRenamesTheApiGroupsOfARealPost553Export() {
        ObjectNode exported = GoldenServiceCorpus.read(
                GoldenServiceCorpus.serviceFile(POST553, GoldenServiceCorpus.EXTERNAL_SERVICE_ID));
        assertTrue(new ServiceDocumentMatcher(SCHEMAS).matches(exported),
                "the matcher has to know the per-type URIs, or nothing in the chain runs");
        ((ObjectNode) exported.path("content")).putArray("apiGroups").add(GoldenServiceCorpus.read(
                GoldenServiceCorpus.set(POST553)
                        .resolve(ExportImportConstants.ARCH_PARENT_DIR)
                        .resolve(GoldenServiceCorpus.EXTERNAL_SERVICE_ID)
                        .resolve(GoldenServiceCorpus.API_GROUP_ID + ".api-group." + APP_NAME + ".yaml")));

        ObjectNode reverted = GoldenServiceCorpus.migrationService(true).revertMigrationIfNeeded(exported);

        assertTrue(reverted.path("apiGroups").isMissingNode(), "the renamed key must not survive a legacy export");
        assertEquals(GoldenServiceCorpus.API_GROUP_ID, reverted.path("specificationGroups").path(0).path("id").asText(),
                "V104 still reverts the rename on a document exported with a per-type $schema");
        assertEquals("EXTERNAL", reverted.path("integrationSystemType").asText(), "V105 still wrote the type back");
    }

    // --- a service with no type --------------------------------------------------------------------------------------

    /**
     * The column is nullable. Before #553 such a row exported fine and blew up later as an NPE in
     * {@code EntityType.getSystemType}; now the file name needs the type, so the export says so and names the row.
     */
    @Test
    void exportingATypelessServiceNamesTheService() {
        IntegrationSystem typeless = IntegrationSystem.builder().id("svc-typeless").name("Legacy row").build();

        ServiceExportException exception = assertThrows(ServiceExportException.class,
                () -> new IntegrationSystemDtoMapper(GoldenServiceCorpus.serviceTypeFiles(), List.of())
                        .toExternalEntity(typeless));

        assertTrue(exception.getMessage().contains("svc-typeless"),
                "the message names the row to fix: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("Set the type of the service"),
                "the message says what to do: " + exception.getMessage());
    }

    @Test
    void theFileNameOfATypelessServiceIsRefusedRatherThanGuessed() {
        // Any refusal will do; the point is that no name is invented for a service that states no type.
        assertThrows(RuntimeException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName("svc-typeless", APP_NAME, false, null));
        assertEquals("service-svc-typeless.yaml",
                ExportImportUtils.generateMainSystemFileExportName("svc-typeless", APP_NAME, true, null),
                "the legacy name carries no type, so it needs none");
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private ServiceDeserializer deserializer() {
        return GoldenServiceCorpus.deserializer();
    }

    private static boolean containsKey(JsonNode node, String key) {
        if (node.isObject() && node.has(key)) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsKey(child, key)) {
                return true;
            }
        }
        return false;
    }
}

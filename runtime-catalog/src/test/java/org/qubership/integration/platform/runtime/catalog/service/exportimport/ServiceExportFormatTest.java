package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.ServiceDocumentMatcher;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.APP_NAME;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.PRE553_CURRENT;

/**
 * What a service export writes: the file name and the {@code $schema} state the type, the document does not, and the
 * legacy format is untouched. Every assertion runs against the golden corpus in {@link GoldenServiceCorpus}, so a
 * change to the exported format shows up as a diff rather than as a green suite.
 */
class ServiceExportFormatTest {

    private static final ApplicationJsonSchemaProperties SCHEMAS = new ApplicationJsonSchemaProperties();

    // --- the current format ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "EXTERNAL, svc-external, svc-external.external-service.qip.yaml, external-service.schema.yaml",
            "INTERNAL, svc-internal, svc-internal.internal-service.qip.yaml, internal-service.schema.yaml",
            "IMPLEMENTED, svc-implemented, svc-implemented.implemented-service.qip.yaml, implemented-service.schema.yaml"})
    @DisplayName("each type exports under its own file name and $schema")
    void perTypeNameAndSchema(
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
    @DisplayName("no current-format document mentions the type field")
    void noTypeFieldAnywhere() {
        Map<String, ObjectNode> documents = GoldenServiceCorpus.documentsOf(GoldenServiceCorpus.set(POST553));

        assertFalse(documents.isEmpty(), "the post-#553 golden set is empty");
        documents.forEach((name, document) ->
                assertFalse(containsKey(document, "integrationSystemType"), name + " still carries the type field"));
    }

    @Test
    @DisplayName("only the service file names changed; the archive layout around them is unaffected")
    void onlyServiceFileNamesChanged() {
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
     * {@code @JsonProperty(WRITE_ONLY)} suppresses the field on export only. An older archive still binds it, and it is
     * the only place those files state a type: their {@code .service.} name carries none.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "svc-external, EXTERNAL",
            "svc-internal, INTERNAL",
            "svc-implemented, IMPLEMENTED"})
    @DisplayName("an older archive still imports with its type field")
    void olderArchiveKeepsItsTypeField(String serviceId, IntegrationSystemType type) {
        Path serviceFile = GoldenServiceCorpus.serviceFile(PRE553_CURRENT, serviceId);
        assertTrue(serviceFile.getFileName().toString().contains(ExportImportConstants.SERVICE_YAML_NAME_POSTFIX),
                "the pre-#553 set must keep the old name, or this proves nothing");

        IntegrationSystem imported = GoldenServiceCorpus.deserializer().deserializeSystem(serviceFile.toFile());

        assertEquals(type, imported.getIntegrationSystemType());
    }

    // --- the legacy format -------------------------------------------------------------------------------------------

    /**
     * The no-regression claim, measured against a set captured before the exporter changed. Byte equality is
     * unattainable, because {@code ObjectNode} is insertion-ordered and {@code V105RevertMigration} appends the
     * restored keys last, so the comparison is per document and order-insensitive.
     */
    @Test
    @DisplayName("the legacy export is unchanged")
    void legacyExportIsUnchanged(@TempDir Path directory) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(true), directory);

        assertEquals(GoldenServiceCorpus.fileNames(LEGACY_FLAT), GoldenServiceCorpus.relativeFileNames(directory),
                "the legacy format keeps the flat file names the change never touched");
        Map<String, ObjectNode> golden = GoldenServiceCorpus.documentsOf(GoldenServiceCorpus.set(LEGACY_FLAT));
        Map<String, ObjectNode> actual = GoldenServiceCorpus.documentsOf(directory);
        golden.forEach((name, document) -> assertEquals(document, actual.get(name), name + " changed"));
    }

    /**
     * What makes {@value GoldenServiceCorpus#POST553} a regression pin rather than a committed tree nothing
     * regenerates: everything else in this class reads that set, so without this the exporter could drift away from it
     * with the whole suite green.
     */
    @Test
    @DisplayName("the current export still matches the recorded format")
    void currentExportMatchesTheRecordedFormat(@TempDir Path directory) throws IOException {
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
    @DisplayName("the legacy export states the type in the document")
    void legacyExportStatesTheTypeInTheDocument(String serviceId, IntegrationSystemType type) {
        Path serviceFile = GoldenServiceCorpus.serviceFile(LEGACY_FLAT, serviceId);

        assertEquals("service-" + serviceId + ".yaml", serviceFile.getFileName().toString());
        assertEquals(type.name(), GoldenServiceCorpus.read(serviceFile).path("integrationSystemType").asText());
        assertEquals(type,
                GoldenServiceCorpus.deserializer().deserializeSystem(serviceFile.toFile()).getIntegrationSystemType());
    }

    // --- the revert chain over a real export -------------------------------------------------------------------------

    /**
     * The revert chain on a real export, not a hand-built document: the per-type {@code $schema} that reaches
     * {@code V105RevertMigration} is the one the exporter actually writes.
     */
    @ParameterizedTest
    @CsvSource({"svc-external", "svc-internal", "svc-implemented"})
    @DisplayName("the revert chain turns a real export back into the legacy document")
    void revertChainReproducesTheLegacyDocument(String serviceId) {
        ObjectNode exported = GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(POST553, serviceId));

        ObjectNode reverted = GoldenServiceCorpus.migrationService(true).revertMigrationIfNeeded(exported);

        assertEquals(GoldenServiceCorpus.read(GoldenServiceCorpus.serviceFile(LEGACY_FLAT, serviceId)), reverted);
    }

    /**
     * V104 and V103 are gated on {@link ServiceDocumentMatcher} and run on V105's result, so the {@code $schema}
     * restore keeps them alive. Proven on a real export carrying the new URI.
     */
    @Test
    @DisplayName("the revert chain still renames the api groups of a real export")
    void revertChainStillRenamesApiGroups() {
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
     * The column is nullable. Such a row used to export fine and blow up later as an NPE in
     * {@code EntityType.getSystemType}; now the file name needs the type, so the export says so and names the row.
     */
    @Test
    @DisplayName("exporting a typeless service names the service")
    void typelessServiceIsNamedOnRefusal() {
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
    @DisplayName("the file name of a typeless service is refused rather than guessed")
    void typelessServiceFileNameIsRefused() {
        // Any refusal will do; the point is that no name is invented for a service that states no type.
        assertThrows(RuntimeException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName("svc-typeless", APP_NAME, false, null));
        assertEquals("service-svc-typeless.yaml",
                ExportImportUtils.generateMainSystemFileExportName("svc-typeless", APP_NAME, true, null),
                "the legacy name carries no type, so it needs none");
    }

    // --- the written name reads back --------------------------------------------------------------------------------

    /**
     * The app prefix is configuration, and it lands in the name behind the postfix. A prefix carrying a postfix of its
     * own used to make the name state two types, and a two-type name resolved to none, so an archive this exporter had
     * just written came back typeless and failed its own import.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("an app prefix carrying a postfix exports a name that reads back as the exported type")
    void appPrefixCarryingAPostfixReadsBack(IntegrationSystemType type) {
        for (IntegrationSystemType carried : IntegrationSystemType.values()) {
            String appPrefix = "app" + ServiceTypeFiles.postfix(carried) + APP_NAME;

            String fileName = ExportImportUtils.generateMainSystemFileExportName("svc-1", appPrefix, false, type);

            assertEquals(Optional.of(type), ServiceTypeFiles.typeFromFileName(fileName), fileName);
        }
    }

    /**
     * A dot in the id shifts the type out of the segment import reads it from, and the document no longer carries one
     * to fall back on. The export refuses instead of writing a name that reads back as another type or as none.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("a service id carrying a postfix is refused rather than exported unreadable")
    void serviceIdCarryingAPostfixIsRefused(IntegrationSystemType type) {
        String serviceId = "svc" + ServiceTypeFiles.postfix(type) + "1";

        ServiceExportException exception = assertThrows(ServiceExportException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false, type));

        assertTrue(exception.getMessage().contains(serviceId),
                "the message names the id to fix: " + exception.getMessage());
        assertEquals("service-" + serviceId + ".yaml",
                ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, true, type),
                "the legacy name states no type, so a dotted id stays exportable");
    }

    /**
     * The flat prefix is what tells the two name formats apart, so an id carrying it makes a current-format name read
     * as legacy: another id, and no type at all.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("a service id carrying the legacy flat prefix is refused rather than exported unreadable")
    void serviceIdCarryingTheLegacyFlatPrefixIsRefused(IntegrationSystemType type) {
        String serviceId = "service-1";

        ServiceExportException exception = assertThrows(ServiceExportException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false, type));

        assertTrue(exception.getMessage().contains(serviceId),
                "the message names the id to fix: " + exception.getMessage());
        assertEquals("service-" + serviceId + ".yaml",
                ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, true, type),
                "the legacy name states the id whole, so it stays exportable");
    }

    // --- a service over its environment limit ------------------------------------------------------------------------

    /**
     * IMPLEMENTED was never checked before the rule and INTERNAL was unchecked on import-create, so rows holding more
     * environments than their type allows exist. Such a row still exports, because refusing would leave no way to
     * extract it, and the warning is what tells the operator the archive does not import as it stands.
     */
    @Test
    @DisplayName("a service over its environment limit warns and still exports")
    void overTheEnvironmentLimitWarns() {
        IntegrationSystem overPopulated = serviceWithEnvironments(2);

        List<ILoggingEvent> events =
                capture(ServiceSerializer.class, () -> assertNotNull(exportOf(overPopulated)));

        assertTrue(events.stream().anyMatch(event -> event.getFormattedMessage().contains("svc-internal")
                        && event.getFormattedMessage().contains("re-importing")),
                "the export names the row and says the archive does not come back in: " + events);
    }

    @Test
    @DisplayName("a service inside its environment limit exports silently")
    void withinTheEnvironmentLimitIsSilent() {
        IntegrationSystem withinLimit = serviceWithEnvironments(1);

        List<ILoggingEvent> events = capture(ServiceSerializer.class, () -> exportOf(withinLimit));

        assertTrue(events.isEmpty(), "a service inside its limit exports silently: " + events);
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static IntegrationSystem serviceWithEnvironments(int environmentCount) {
        List<Environment> environments = new ArrayList<>();
        for (int i = 0; i < environmentCount; i++) {
            environments.add(new Environment());
        }
        return IntegrationSystem.builder()
                .id("svc-internal")
                .name("Billing")
                .integrationSystemType(IntegrationSystemType.INTERNAL)
                .environments(environments)
                .apiGroups(new ArrayList<>())
                .build();
    }

    private static ExportedSystemObject exportOf(IntegrationSystem system) {
        return GoldenServiceCorpus.serviceSerializer(false).serialize(system);
    }

    private static List<ILoggingEvent> capture(Class<?> loggerClass, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
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

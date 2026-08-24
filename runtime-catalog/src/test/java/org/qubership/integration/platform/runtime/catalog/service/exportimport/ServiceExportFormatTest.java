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
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.io.model.exportimport.ExportImportConstants;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.APP_NAME;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553_DOTTED;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.PRE553_CURRENT;

/**
 * What a service export writes: the {@code $schema} states the type, neither the file name nor the document does, and
 * the legacy format is untouched. Every assertion runs against the golden corpus in {@link GoldenServiceCorpus}, so a
 * change to the exported format shows up as a diff rather than as a green suite.
 */
class ServiceExportFormatTest {

    private static final ApplicationJsonSchemaProperties SCHEMAS = new ApplicationJsonSchemaProperties();

    // --- the current format ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "svc-external, external-service.schema.yaml",
            "svc-internal, internal-service.schema.yaml",
            "svc-implemented, implemented-service.schema.yaml"})
    @DisplayName("each type exports under its own $schema and the one type-less file name")
    void perTypeSchemaUnderOneName(String serviceId, String schemaFile) {
        String fileName = serviceId + ".service." + APP_NAME + ".yaml";
        assertEquals(fileName, ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false));

        Path exported = GoldenServiceCorpus.serviceFile(POST553, serviceId);
        assertEquals(fileName, exported.getFileName().toString());

        ObjectNode document = GoldenServiceCorpus.read(exported);
        assertEquals("http://qubership.org/schemas/product/qip/" + schemaFile, document.path("$schema").asText());
        assertFalse(document.path("content").has("integrationSystemType"),
                "the type is stated by the $schema, not by the document");
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

    /** The whole file-name change of #553 was undone, so the two sets are named identically, file for file. */
    @Test
    @DisplayName("no service file name changed since before #553")
    void noServiceFileNameChanged() {
        assertEquals(GoldenServiceCorpus.fileNames(PRE553_CURRENT), GoldenServiceCorpus.fileNames(POST553));
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
        assertEquals(SCHEMAS.getService(), GoldenServiceCorpus.read(serviceFile).path("$schema").asText(),
                "the pre-#553 set must keep the plain service schema, or this proves nothing");

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

        GoldenServiceCorpus.assertMatchesRecordedSet(LEGACY_FLAT, directory,
                "the legacy format keeps the flat file names the change never touched");
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

        GoldenServiceCorpus.assertMatchesRecordedSet(POST553, directory,
                "the exporter writes different file names than the recorded post-#553 set");
    }

    /**
     * The same pin over {@value GoldenServiceCorpus#POST553_DOTTED}. That set exists because the VS Code extension
     * opens it: only the service id has to be one dot-free segment, so a real export names its api group and api files
     * after ids carrying dots, and the extension strips the whole extension end-anchored to read the id back.
     */
    @Test
    @DisplayName("the dotted-id export still matches the recorded format")
    void dottedExportMatchesTheRecordedFormat(@TempDir Path directory) throws IOException {
        GoldenServiceCorpus.unzipInto(
                GoldenServiceCorpus.archive(
                        GoldenServiceCorpus.exportServices(List.of(GoldenServiceCorpus.dottedApiService()), false),
                        false),
                directory);

        GoldenServiceCorpus.assertMatchesRecordedSet(POST553_DOTTED, directory,
                "the exporter writes different file names than the recorded dotted-id set");
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
    @DisplayName("a typeless service still gets a file name, because the name states no type")
    void typelessServiceStillGetsAFileName() {
        // The refusal moved: it is the DTO mapper that needs the type, to stamp the right $schema.
        assertEquals("svc-typeless.service." + APP_NAME + ".yaml",
                ExportImportUtils.generateMainSystemFileExportName("svc-typeless", APP_NAME, false));
        assertEquals("service-svc-typeless.yaml",
                ExportImportUtils.generateMainSystemFileExportName("svc-typeless", APP_NAME, true));
    }

    // --- the written name reads back --------------------------------------------------------------------------------

    /**
     * The app prefix is configuration, and it lands in the name behind the postfix. A prefix carrying a service
     * postfix of its own is what used to shift the one readable position; the id is still read at the first dot.
     */
    @ParameterizedTest
    @ValueSource(strings = {".service.", ".external-service.", ".internal-service.", ".implemented-service."})
    @DisplayName("an app prefix carrying a postfix exports a name whose id reads back")
    void appPrefixCarryingAPostfixReadsBack(String carried) {
        String appPrefix = "app" + carried + APP_NAME;

        String fileName = ExportImportUtils.generateMainSystemFileExportName("svc-1", appPrefix, false);

        assertEquals("svc-1", ExportImportUtils.extractSystemIdFromFileName(new File(fileName)), fileName);
    }

    /**
     * A dot in the id shifts the id out of the segment import reads it from. The export refuses instead of writing a
     * name that reads back as another id. The flat name states such an id whole, so it stays the way out.
     */
    @Test
    @DisplayName("a dotted service id is refused rather than exported unreadable")
    void dottedServiceIdIsRefused() {
        String serviceId = "svc.1";

        ServiceExportException exception = assertThrows(ServiceExportException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false));

        assertTrue(exception.getMessage().contains(serviceId),
                "the message names the id to fix: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("QIP_EXPORT_LEGACY_FORMAT"),
                "the flat name states this id, so the refusal points at it: " + exception.getMessage());
        assertEquals("service-" + serviceId + ".yaml",
                ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, true),
                "the legacy name states such an id whole, so a dotted id stays exportable");
    }

    /**
     * The one id neither format states: its flat name is also the current-format name of another service, and the
     * current format wins that tie. Both refusals name the id, and neither offers the other format as a way out.
     */
    @ParameterizedTest
    @ValueSource(strings = {".service.", ".external-service.", ".internal-service.", ".implemented-service."})
    @DisplayName("a service id whose second segment spells a postfix is refused in both formats")
    void serviceIdCarryingAPostfixIsRefusedInBothFormats(String postfix) {
        String serviceId = "svc" + postfix + "1";

        ServiceExportException current = assertThrows(ServiceExportException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false));
        ServiceExportException legacy = assertThrows(ServiceExportException.class,
                () -> ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, true));

        assertTrue(current.getMessage().contains(serviceId) && legacy.getMessage().contains(serviceId),
                "both messages name the id to fix: " + current.getMessage() + " / " + legacy.getMessage());
        assertFalse(current.getMessage().contains("QIP_EXPORT_LEGACY_FORMAT"),
                "the flat name does not state this id either: " + current.getMessage());
    }

    /**
     * Autodiscovery mints a plain service id from the Kubernetes service name, so an id wearing the legacy flat prefix
     * is ordinary rather than hand-authored. Refusing it made a discovered service unexportable and aborted the whole
     * archive, because the postfix, not the prefix, is what tells the two name formats apart.
     */
    @Test
    @DisplayName("a service id wearing the legacy flat prefix exports in the current format")
    void serviceIdWearingTheLegacyFlatPrefixExports() {
        String serviceId = "service-orders";

        String fileName = ExportImportUtils.generateMainSystemFileExportName(serviceId, APP_NAME, false);

        assertEquals(serviceId + ".service." + APP_NAME + ".yaml", fileName);
        assertEquals(serviceId, ExportImportUtils.extractSystemIdFromFileName(new File(fileName)));
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

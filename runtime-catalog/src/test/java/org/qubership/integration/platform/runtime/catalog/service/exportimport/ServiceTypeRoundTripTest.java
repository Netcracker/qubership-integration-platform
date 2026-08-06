package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportContextServiceAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.system.SystemsCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.ContextBaseService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.MCPSystemService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ContextServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.MCPSystemSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.APP_NAME;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.CONTEXT_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.EXTERNAL_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.INTERNAL_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.MCP_SERVICE_ID;

/**
 * The whole loop, over real archive bytes: the {@link GoldenServiceCorpus} fixtures are serialized by the production
 * serializers, zipped, unzipped, and imported by the production import services.
 *
 * <p>Every other test in this area looks at one half of that loop. This one sees the two halves disagree: an exporter
 * that writes a file name the importer never looks for passes both sides' unit tests and fails only here.
 */
@ExtendWith(MockitoExtension.class)
class ServiceTypeRoundTripTest {

    private static final String IMPORT_ID = "import-1";
    private static final Map<String, IntegrationSystemType> TYPES_BY_SERVICE_ID = Map.of(
            EXTERNAL_SERVICE_ID, IntegrationSystemType.EXTERNAL,
            INTERNAL_SERVICE_ID, IntegrationSystemType.INTERNAL,
            IMPLEMENTED_SERVICE_ID, IntegrationSystemType.IMPLEMENTED);
    private static final List<String> PLAIN_SERVICE_IDS =
            TYPES_BY_SERVICE_ID.keySet().stream().sorted().toList();

    @Mock TransactionTemplate transactionTemplate;
    @Mock SystemService systemService;
    @Mock EnvironmentService environmentService;
    @Mock SystemModelService systemModelService;
    @Mock ActionsLogService actionLogger;
    @Mock AuditingHandler auditingHandler;
    @Mock ServiceSerializer serviceSerializer;
    @Mock ArchiveWriter archiveWriter;
    @Mock ImportSessionService importProgressService;
    @Mock ImportInstructionsService importInstructionsService;
    @Mock ElementHelperService elementHelperService;
    @Mock ChainService chainService;
    @Mock ApiGroupService apiGroupService;
    @Mock ContextBaseService contextBaseService;
    @Mock ContextServiceSerializer contextServiceSerializer;
    @Mock MCPSystemService mcpSystemService;
    @Mock MCPSystemSerializer mcpSystemSerializer;

    @TempDir Path unpacked;

    private void runTransactionsInline() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    // --- the type survives the loop, in both formats ------------------------------------------------------------------

    /**
     * Create, export, import, and read the persisted type back. The type must be non-null: the column is nullable, so
     * a lost type is not an import failure but a row that fails much later, inside {@code EntityType.getSystemType}.
     */
    @DisplayName("a service round trips with its type")
    @ParameterizedTest(name = "{1} through a {0}-format archive")
    @CsvSource({
            "current, EXTERNAL, svc-external",
            "current, INTERNAL, svc-internal",
            "current, IMPLEMENTED, svc-implemented",
            "legacy, EXTERNAL, svc-external",
            "legacy, INTERNAL, svc-internal",
            "legacy, IMPLEMENTED, svc-implemented"})
    void serviceRoundTripsWithItsType(String format, IntegrationSystemType type, String serviceId) {
        runTransactionsInline();
        importingIntoAnEmptyCatalog();

        List<ImportSystemResult> results =
                systemImport().importSystemRequest(archiveOf(isLegacy(format)), null, null, Set.of());

        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(results));
        assertEquals(type, typeOf(createdServices().get(serviceId)));
    }

    // --- create and update are separate code paths --------------------------------------------------------------------

    @Test
    @DisplayName("the create path persists the type of every service")
    void createPathPersistsEveryType() throws IOException {
        runTransactionsInline();
        importingIntoAnEmptyCatalog();
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(false), unpacked);

        ImportSystemsAndInstructionsResult result = systemImport()
                .importSystems(unpacked.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(result.importSystemResults()));
        assertEquals(TYPES_BY_SERVICE_ID, typesOf(createdServices()));
        verify(systemService, never()).update(any());
    }

    /** The update branch merges into a stored service instead of preparing a new one, and must keep the type too. */
    @Test
    @DisplayName("the update path persists the type of every service")
    void updatePathPersistsEveryType() throws IOException {
        runTransactionsInline();
        importingOverStoredServices();
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(false), unpacked);

        ImportSystemsAndInstructionsResult result = systemImport()
                .importSystems(unpacked.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(Set.of(ImportSystemStatus.UPDATED), statusesOf(result.importSystemResults()));
        assertEquals(TYPES_BY_SERVICE_ID, typesOf(updatedServices()));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    // --- across the two formats ---------------------------------------------------------------------------------------

    /**
     * A legacy archive read by this QIP and written back out in the current format. The file name and the
     * {@code $schema} now have to state the type the flat document stated in its body, and the result has to import
     * again.
     */
    @DisplayName("a legacy archive re-exports and re-imports in the current format")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "EXTERNAL, svc-external, .external-service.",
            "INTERNAL, svc-internal, .internal-service.",
            "IMPLEMENTED, svc-implemented, .implemented-service."})
    void legacyArchiveReExportsInTheCurrentFormat(
            IntegrationSystemType type, String serviceId, String postfix, @TempDir Path reExported)
            throws IOException {
        runTransactionsInline();
        importingIntoAnEmptyCatalog();
        systemImport().importSystemRequest(archiveOf(true), null, null, Set.of());
        IntegrationSystem imported = createdServices().get(serviceId);

        GoldenServiceCorpus.unzipInto(
                GoldenServiceCorpus.archive(GoldenServiceCorpus.exportServices(List.of(imported), false), false),
                reExported);

        Path serviceFile = GoldenServiceCorpus.serviceFileIn(reExported, serviceId);
        assertEquals(serviceId + postfix + APP_NAME + ".yaml", serviceFile.getFileName().toString());
        ObjectNode document = GoldenServiceCorpus.read(serviceFile);
        assertEquals(GoldenServiceCorpus.serviceTypeFiles().schemaUri(type), document.path("$schema").asText());
        assertFalse(document.path("content").has("integrationSystemType"),
                "the current format states the type in the name and the $schema, not in the document");
        assertEquals(type,
                typeOf(GoldenServiceCorpus.deserializer().deserializeSystem(serviceFile.toFile())));
    }

    // --- the legacy export stays readable by an older QIP --------------------------------------------------------------

    /**
     * {@code ContextServiceDtoMapper} stamps context services from the service migration list, so a current-format
     * export claims 105 on them as well. The legacy export has to strip that claim, or an older QIP rejects the
     * context service of an archive whose plain services it still reads fine. That is the regression V105's broad
     * {@code supportsDocument} exists to prevent, and no test that looks only at plain services sees it.
     */
    @Test
    @DisplayName("a context service exported alongside a plain service imports into an older QIP")
    void contextServiceImportsIntoAnOlderQip() throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(true), unpacked);
        Path contextFile = GoldenServiceCorpus.serviceFileIn(unpacked, CONTEXT_SERVICE_ID);
        Path serviceFile = GoldenServiceCorpus.serviceFileIn(unpacked, EXTERNAL_SERVICE_ID);

        assertFalse(GoldenServiceCorpus.read(contextFile).path("migrations").asText().contains("105"),
                "an older QIP refuses every version it does not know");

        ContextSystem context = GoldenServiceCorpus.contextServiceDeserializer(migrationsBefore105())
                .deserializeSystem(contextFile.toFile());
        IntegrationSystem service = GoldenServiceCorpus.deserializer(migrationsBefore105())
                .deserializeSystem(serviceFile.toFile());

        assertEquals(CONTEXT_SERVICE_ID, context.getId());
        assertEquals(IntegrationSystemType.EXTERNAL, typeOf(service),
                "the plain service of the same archive still states its type in the document");
    }

    // --- one archive, all five kinds ----------------------------------------------------------------------------------

    /**
     * The three plain types, a context service, and an MCP service in one current-format archive, through the preview
     * and the commit path of all three import services. Each service discovers its own files by name, so the five
     * kinds are also each other's negative cases: a postfix that matches one neighbor too many shows up here.
     */
    @Test
    @DisplayName("an archive of all five kinds imports through the preview and the commit paths")
    void archiveOfAllFiveKindsImports() throws IOException {
        runTransactionsInline();
        importingIntoAnEmptyCatalog();
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(false), unpacked);
        ImportInstructionsConfig noInstructions = ImportInstructionsConfig.builder().build();

        List<ImportSystemResult> servicePreview =
                systemImport().getSystemsImportPreview(unpacked.toFile(), noInstructions);
        List<ImportSystemResult> contextPreview =
                contextImport().getContextServiceImportPreview(unpacked.toFile(), noInstructions);
        List<ImportSystemResult> mcpPreview = mcpImport().getImportPreview(unpacked.toFile(), noInstructions);

        assertEquals(PLAIN_SERVICE_IDS, idsOf(servicePreview));
        assertEquals(List.of(CONTEXT_SERVICE_ID), idsOf(contextPreview));
        assertEquals(List.of(MCP_SERVICE_ID), idsOf(mcpPreview));
        servicePreview.forEach(result -> assertEquals(SystemCompareAction.CREATE, result.getRequiredAction()));
        contextPreview.forEach(result -> assertEquals(SystemCompareAction.CREATE, result.getRequiredAction()));
        mcpPreview.forEach(result -> assertEquals(SystemCompareAction.CREATE, result.getRequiredAction()));

        ImportSystemsAndInstructionsResult services = systemImport()
                .importSystems(unpacked.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());
        ImportContextServiceAndInstructionsResult contexts = contextImport()
                .importContextService(unpacked.toFile(), new SystemsCommitRequest(), IMPORT_ID);
        ImportSystemsAndInstructionsResult mcps = mcpImport()
                .importSystems(unpacked.toFile(), new SystemsCommitRequest(), IMPORT_ID);

        assertEquals(PLAIN_SERVICE_IDS, idsOf(services.importSystemResults()));
        assertEquals(TYPES_BY_SERVICE_ID, typesOf(createdServices()));
        assertEquals(List.of(CONTEXT_SERVICE_ID), idsOf(contexts.importSystemResults()));
        assertEquals(List.of(MCP_SERVICE_ID), idsOf(mcps.importSystemResults()));
        verify(contextBaseService).create(any(ContextSystem.class), anyBoolean());
        verify(mcpSystemService).create(any(MCPSystem.class), anyBoolean());
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private SystemExportImportService systemImport() {
        return new SystemExportImportService(
                transactionTemplate,
                systemService,
                environmentService,
                systemModelService,
                GoldenServiceCorpus.mapper(),
                actionLogger,
                auditingHandler,
                serviceSerializer,
                GoldenServiceCorpus.deserializer(),
                archiveWriter,
                importProgressService,
                importInstructionsService,
                elementHelperService,
                chainService,
                apiGroupService);
    }

    private ContextExportImportService contextImport() {
        return new ContextExportImportService(
                transactionTemplate,
                contextBaseService,
                GoldenServiceCorpus.mapper(),
                actionLogger,
                contextServiceSerializer,
                GoldenServiceCorpus.contextServiceDeserializer(),
                archiveWriter,
                importProgressService,
                importInstructionsService,
                URI.create(GoldenServiceCorpus.schemas().getContextService()));
    }

    private MCPSystemImportExportService mcpImport() {
        return new MCPSystemImportExportService(
                transactionTemplate,
                GoldenServiceCorpus.mapper(),
                mcpSystemService,
                actionLogger,
                mcpSystemSerializer,
                GoldenServiceCorpus.mcpSystemDeserializer(),
                archiveWriter,
                importInstructionsService,
                importProgressService,
                URI.create(GoldenServiceCorpus.schemas().getMcpService()));
    }

    private void importingIntoAnEmptyCatalog() {
        lenient().when(importInstructionsService.performServiceIgnoreInstructions(any(), anyBoolean()))
                .thenAnswer(invocation -> new IgnoreResult(invocation.getArgument(0), List.of()));
        lenient().when(systemService.getByIdOrNull(any())).thenReturn(null);
    }

    /** Every fixture already stored under its own type, so the import merges instead of creating. */
    private void importingOverStoredServices() {
        lenient().when(importInstructionsService.performServiceIgnoreInstructions(any(), anyBoolean()))
                .thenAnswer(invocation -> new IgnoreResult(invocation.getArgument(0), List.of()));
        when(systemService.getByIdOrNull(any())).thenAnswer(invocation -> {
            String serviceId = invocation.getArgument(0);
            return switch (serviceId) {
                case EXTERNAL_SERVICE_ID -> stored(GoldenServiceCorpus.externalService());
                case INTERNAL_SERVICE_ID -> stored(GoldenServiceCorpus.internalService());
                case IMPLEMENTED_SERVICE_ID -> stored(GoldenServiceCorpus.implementedService());
                default -> null;
            };
        });
    }

    // A fixture read back from the database, where an @ElementCollection comes back empty rather than null. The
    // difference matters: Environment.equals compares two null label lists by iterating one of them.
    private static IntegrationSystem stored(IntegrationSystem fixture) {
        fixture.getEnvironments().forEach(environment -> environment.setLabels(new ArrayList<>()));
        return fixture;
    }

    private Map<String, IntegrationSystem> createdServices() {
        ArgumentCaptor<IntegrationSystem> captor = ArgumentCaptor.forClass(IntegrationSystem.class);
        verify(systemService, times(TYPES_BY_SERVICE_ID.size())).create(captor.capture(), anyBoolean());
        return byId(captor.getAllValues());
    }

    private Map<String, IntegrationSystem> updatedServices() {
        ArgumentCaptor<IntegrationSystem> captor = ArgumentCaptor.forClass(IntegrationSystem.class);
        verify(systemService, times(TYPES_BY_SERVICE_ID.size())).update(captor.capture());
        return byId(captor.getAllValues());
    }

    private static Map<String, IntegrationSystem> byId(List<IntegrationSystem> systems) {
        return systems.stream().collect(Collectors.toMap(IntegrationSystem::getId, system -> system));
    }

    private static Map<String, IntegrationSystemType> typesOf(Map<String, IntegrationSystem> systems) {
        return systems.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> typeOf(entry.getValue())));
    }

    private static IntegrationSystemType typeOf(IntegrationSystem system) {
        assertNotNull(system, "the service was never persisted");
        return requireNonNull(system.getIntegrationSystemType(),
                () -> "service " + system.getId() + " landed with no type, which only surfaces much later as an NPE");
    }

    private static List<String> idsOf(List<ImportSystemResult> results) {
        return results.stream().map(ImportSystemResult::getId).sorted().toList();
    }

    private static Set<ImportSystemStatus> statusesOf(List<ImportSystemResult> results) {
        return results.stream().map(ImportSystemResult::getStatus).collect(Collectors.toSet());
    }

    /** A live export of the fixture set, as the bytes the download endpoint returns. */
    private static MockMultipartFile archiveOf(boolean legacy) {
        return new MockMultipartFile("file", (legacy ? "legacy" : "current") + ".zip", "application/zip",
                GoldenServiceCorpus.archive(legacy));
    }

    private static boolean isLegacy(String format) {
        return "legacy".equals(format);
    }

    /** The service migration registry of a QIP that predates #553. */
    private static List<ServiceImportFileMigration> migrationsBefore105() {
        return TestServiceMigrations.all().stream()
                .filter(migration -> migration.getVersion() != 105)
                .toList();
    }
}

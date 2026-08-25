package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportMode;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.system.SystemsCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.EXTERNAL_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.IMPLEMENTED_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.INTERNAL_SERVICE_ID;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.PRE553_CURRENT;
import static org.qubership.integration.platform.runtime.catalog.testutils.ServiceFixtures.SYSTEM_ID;
import static org.qubership.integration.platform.runtime.catalog.testutils.ServiceFixtures.SYSTEM_NAME;
import static org.qubership.integration.platform.runtime.catalog.testutils.ServiceFixtures.systemWith;

@ExtendWith(MockitoExtension.class)
class SystemExportImportServiceTest {

    private static final String IMPORT_ID = "import-1";
    private static final String EXTERNAL_FILE_NAME = EXTERNAL_SERVICE_ID + ".service.qip.yaml";
    private static final String INTERNAL_FILE_NAME = INTERNAL_SERVICE_ID + ".service.qip.yaml";
    /** A second discovered file for the same id: the per-type name the #553 versions wrote, which is still read. */
    private static final String EXTERNAL_UNDER_A_PER_TYPE_NAME = EXTERNAL_SERVICE_ID + ".internal-service.qip.yaml";

    /** The plain services of every golden set. The context and the MCP service are imported elsewhere. */
    private static final List<String> GOLDEN_SERVICE_IDS =
            Stream.of(EXTERNAL_SERVICE_ID, IMPLEMENTED_SERVICE_ID, INTERNAL_SERVICE_ID).sorted().toList();

    private static final Map<String, IntegrationSystemType> GOLDEN_TYPES_BY_ID = Map.of(
            EXTERNAL_SERVICE_ID, IntegrationSystemType.EXTERNAL,
            INTERNAL_SERVICE_ID, IntegrationSystemType.INTERNAL,
            IMPLEMENTED_SERVICE_ID, IntegrationSystemType.IMPLEMENTED);

    @Mock TransactionTemplate transactionTemplate;
    @Mock SystemService systemService;
    @Mock EnvironmentService environmentService;
    @Mock SystemModelService systemModelService;
    @Mock ActionsLogService actionLogger;
    @Mock AuditingHandler auditingHandler;
    @Mock ServiceSerializer serviceSerializer;
    @Mock ServiceDeserializer serviceDeserializer;
    @Mock ArchiveWriter archiveWriter;
    @Mock ImportSessionService importProgressService;
    @Mock ImportInstructionsService importInstructionsService;
    @Mock ElementHelperService elementHelperService;
    @Mock ChainService chainService;
    @Mock ApiGroupService apiGroupService;

    @TempDir Path tempDir;

    private SystemExportImportService service;
    private File serviceFile;

    @BeforeEach
    void setUp() throws IOException {
        service = new SystemExportImportService(
                transactionTemplate,
                systemService,
                environmentService,
                systemModelService,
                new YAMLMapper(),
                actionLogger,
                auditingHandler,
                serviceSerializer,
                serviceDeserializer,
                archiveWriter,
                importProgressService,
                importInstructionsService,
                elementHelperService,
                chainService,
                apiGroupService,
                GoldenServiceCorpus.serviceTypeFiles());

        serviceFile = tempDir.resolve(SYSTEM_ID + ".service.qip.yaml").toFile();
        Files.writeString(serviceFile.toPath(),
                "id: " + SYSTEM_ID + "\nname: " + SYSTEM_NAME + "\n", StandardCharsets.UTF_8);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    // --- the environment limit -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a second environment is rejected when creating an internal service on import")
    void secondEnvironmentIsRejectedOnCreateForInternalService() {
        importing(systemWith(IntegrationSystemType.INTERNAL, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertMessageContains(result, "internal");
        assertMessageContains(result, SYSTEM_ID);
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("a second environment is rejected when creating an implemented service on import")
    void secondEnvironmentIsRejectedOnCreateForImplementedService() {
        importing(systemWith(IntegrationSystemType.IMPLEMENTED, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertMessageContains(result, "implemented");
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("a second environment is accepted when creating an external service on import")
    void secondEnvironmentIsAcceptedOnCreateForExternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.EXTERNAL, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.CREATED, result.getStatus());
        verify(systemService).create(system, true);
    }

    @Test
    @DisplayName("a single environment is accepted when creating an internal service on import")
    void singleEnvironmentIsAcceptedOnCreateForInternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.INTERNAL, 1);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.CREATED, result.getStatus());
        verify(systemService).create(system, true);
    }

    @Test
    @DisplayName("a service row with no type does not crash the import")
    void typelessServiceDoesNotCrashTheImport() {
        IntegrationSystem system = systemWith(null, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.CREATED, result.getStatus());
    }

    @Test
    @DisplayName("a second environment is rejected when updating an internal service on import")
    void secondEnvironmentIsRejectedOnUpdateForInternalService() {
        importing(systemWith(IntegrationSystemType.INTERNAL, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertMessageContains(result, "internal");
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("a second environment is rejected when updating an implemented service on import")
    void secondEnvironmentIsRejectedOnUpdateForImplementedService() {
        importing(systemWith(IntegrationSystemType.IMPLEMENTED, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.IMPLEMENTED, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertMessageContains(result, "implemented");
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("a second environment is accepted when updating an external service on import")
    void secondEnvironmentIsAcceptedOnUpdateForExternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.EXTERNAL, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.EXTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        verify(systemService).update(system);
    }

    // --- service type ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("importing an internal-service file over a stored external service is rejected")
    void importingADifferentTypeOverAStoredServiceIsRejected() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL, 1);
        importing(systemWith(IntegrationSystemType.INTERNAL, 1));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(stored);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertMessageContains(result, "EXTERNAL");
        assertMessageContains(result, "INTERNAL");
        assertMessageContains(result, SYSTEM_ID);
        assertEquals(IntegrationSystemType.EXTERNAL, stored.getIntegrationSystemType());
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("importing the stored type is not treated as a type change")
    void importingTheSameTypeIsAccepted() {
        IntegrationSystem system = systemWith(IntegrationSystemType.INTERNAL, 1);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        verify(systemService).update(system);
    }

    /** A row that predates the type column has nothing to change, so the import that states a type repairs it. */
    @Test
    @DisplayName("importing a type over a typeless stored service is accepted")
    void importingATypeOverATypelessStoredServiceIsAccepted() {
        IntegrationSystem system = systemWith(IntegrationSystemType.INTERNAL, 1);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(null, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        verify(systemService).update(system);
    }

    // --- archive discovery -----------------------------------------------------------------------------------------

    /**
     * Discovery has to read all four service postfixes plus the legacy flat prefix. Run over the golden corpus, so a
     * format the exporter writes and the importer cannot find fails here rather than in production.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    @DisplayName("the import preview finds every plain service of an archive")
    void previewFindsEveryPlainService(String setName) {
        List<ImportSystemResult> preview = service.getSystemsImportPreview(
                GoldenServiceCorpus.set(setName).toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(GOLDEN_SERVICE_IDS, idsOf(preview));
        preview.forEach(result -> assertEquals(SystemCompareAction.CREATE, result.getRequiredAction()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    @DisplayName("the same discovery through the zip entry point the UI calls")
    void previewRequestFindsEveryPlainService(String setName) throws IOException {
        when(importInstructionsService.getServiceImportInstructionsConfig(any()))
                .thenReturn(ImportInstructionsConfig.builder().build());

        List<ImportSystemResult> preview = service.getSystemsImportPreviewRequest(archiveOf(setName));

        assertEquals(GOLDEN_SERVICE_IDS, idsOf(preview));
        preview.forEach(result -> assertEquals(SystemCompareAction.CREATE, result.getRequiredAction()));
    }

    /**
     * The commit path, with the real deserializer. A null type here is the failure mode this whole area is exposed to:
     * the column is nullable, and a null only surfaces later, as an NPE in {@code EntityType.getSystemType}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    @DisplayName("every archive format imports with its type")
    void everyArchiveFormatImportsWithItsType(String setName) {
        importingEverything();

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer().importSystems(
                GoldenServiceCorpus.set(setName).toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(GOLDEN_SERVICE_IDS, idsOf(result.importSystemResults()));
        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(result.importSystemResults()));
        assertEquals(GOLDEN_TYPES_BY_ID, createdTypes());
    }

    /**
     * The children, through the import service rather than the deserializer. {@code ServiceDeserializerTest} covers
     * the parse; what it cannot answer is whether a group, its api and that api's source survive the archive walk,
     * the instruction filter and the per-service transaction that sit between the parse and {@code systemService}.
     * Measured: dropping every api group in the deserializer reddens six classes and leaves this one green.
     *
     * <p>All three sets name these files differently — {@code .api-group.}, {@code .specification-group.} and the
     * flat {@code specGroup-} prefix — and all three have to arrive under the same ids.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    @DisplayName("every archive format imports the children of its services")
    void everyArchiveFormatImportsItsChildren(String setName) {
        importingEverything();

        serviceWithRealDeserializer().importSystems(
                GoldenServiceCorpus.set(setName).toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        Map<String, IntegrationSystem> created = createdSystems();
        assertEquals(GOLDEN_SERVICE_IDS, created.keySet().stream().sorted().toList());

        // Only the external service carries children in the fixture set; the other two carrying none is half the
        // claim, because a scan that attached every group to every service would also look green on the first half.
        assertEquals(List.of(GoldenServiceCorpus.API_GROUP_ID), groupIdsOf(created.get(EXTERNAL_SERVICE_ID)));
        assertEquals(List.of(), groupIdsOf(created.get(INTERNAL_SERVICE_ID)));
        assertEquals(List.of(), groupIdsOf(created.get(IMPLEMENTED_SERVICE_ID)));

        ApiGroup group = created.get(EXTERNAL_SERVICE_ID).getApiGroups().get(0);
        assertEquals(List.of(GoldenServiceCorpus.API_ID),
                group.getSystemModels().stream().map(SystemModel::getId).sorted().toList());
        SystemModel model = group.getSystemModels().get(0);
        assertEquals(List.of("orders.yaml"),
                model.getSpecificationSources().stream().map(SpecificationSource::getName).sorted().toList());
        assertEquals(List.of("op-create-order", "op-get-order", "op-list-orders"),
                model.getOperations().stream().map(Operation::getId).sorted().toList());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    @DisplayName("the same, through the zip entry point")
    void everyArchiveFormatImportsWithItsTypeThroughTheZipRequest(String setName) throws IOException {
        importingEverything();

        List<ImportSystemResult> results =
                serviceWithRealDeserializer().importSystemRequest(archiveOf(setName), null, null, Set.of());

        assertEquals(GOLDEN_SERVICE_IDS, idsOf(results));
        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(results));
        assertEquals(GOLDEN_TYPES_BY_ID, createdTypes());
    }

    /**
     * Two files for one id can only be resolved by guessing, and the per-file loop would import both in separate
     * transactions, letting the last one win. The id gets an error row and the rest of the archive still imports: a
     * throw here would end the whole import session, which by then has already applied instructions and variables.
     */
    @Test
    @DisplayName("two service files for one service are previewed as an error")
    void twoFilesForOneServiceArePreviewedAsAnError(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_UNDER_A_PER_TYPE_NAME);
        writeServiceFile(archive, INTERNAL_SERVICE_ID, INTERNAL_FILE_NAME);

        List<ImportSystemResult> preview = service.getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        ImportSystemResult colliding = resultFor(preview, EXTERNAL_SERVICE_ID);
        assertEquals(SystemCompareAction.ERROR, colliding.getRequiredAction());
        assertMessageContains(colliding, EXTERNAL_FILE_NAME);
        assertMessageContains(colliding, EXTERNAL_UNDER_A_PER_TYPE_NAME);
        assertEquals(SystemCompareAction.CREATE, resultFor(preview, INTERNAL_SERVICE_ID).getRequiredAction());
    }

    @Test
    @DisplayName("two service files for one service import neither of them")
    void twoFilesForOneServiceImportNeither(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, "service-" + EXTERNAL_SERVICE_ID + ".yaml");
        importingEveryDiscoveredId();

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer().importSystems(
                archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(Set.of(ImportSystemStatus.ERROR), statusesOf(result.importSystemResults()));
        verify(systemService, never()).create(any(), anyBoolean());
        verify(systemService, never()).update(any());
    }

    /** The same degradation through the zip entry point, whose temp directory is also cleaned up on the way out. */
    @Test
    @DisplayName("two service files for one service are reported through the zip request")
    void twoFilesForOneServiceAreReportedThroughTheZipRequest(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_UNDER_A_PER_TYPE_NAME);
        importingEveryDiscoveredId();

        List<ImportSystemResult> results = serviceWithRealDeserializer()
                .importSystemRequest(archiveOf(archive, "duplicates"), null, null, Set.of());

        assertEquals(List.of(EXTERNAL_SERVICE_ID), idsOf(results));
        assertEquals(Set.of(ImportSystemStatus.ERROR), statusesOf(results));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    /**
     * A colliding id has to pass the same selection and ignore filters as every other id. A service the request never
     * selected produces no row at all, and a single error row is enough to mark the whole session failed.
     */
    @Test
    @DisplayName("a colliding id the request did not select is not reported at all")
    void unselectedCollidingIdIsNotReported(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_UNDER_A_PER_TYPE_NAME);
        writeServiceFile(archive, INTERNAL_SERVICE_ID, INTERNAL_FILE_NAME);
        importingEveryDiscoveredId();
        when(systemService.getByIdOrNull(any())).thenReturn(null);

        SystemsCommitRequest request = new SystemsCommitRequest();
        request.setImportMode(ImportMode.PARTIAL);
        request.setSystemIds(List.of(INTERNAL_SERVICE_ID));
        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer()
                .importSystems(archive.toFile(), request, IMPORT_ID, Set.of());

        assertEquals(List.of(INTERNAL_SERVICE_ID), idsOf(result.importSystemResults()));
        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(result.importSystemResults()));
    }

    /** An IGNORE instruction excludes the id before the collision matters, so the row says IGNORED, not ERROR. */
    @Test
    @DisplayName("a colliding id excluded by an ignore instruction is reported as ignored")
    void ignoredCollidingIdIsReportedAsIgnored(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_UNDER_A_PER_TYPE_NAME);
        when(importInstructionsService.performServiceIgnoreInstructions(any(), anyBoolean()))
                .thenReturn(new IgnoreResult(Set.of(), List.of()));

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer()
                .importSystems(archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(ImportSystemStatus.IGNORED,
                resultFor(result.importSystemResults(), EXTERNAL_SERVICE_ID).getStatus());
    }

    /** The same two filters on the zip entry point, which carries its selection as the {@code systemIds} argument. */
    @Test
    @DisplayName("a colliding id is filtered the same way through the zip request")
    void collidingIdIsFilteredThroughTheZipRequest(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_UNDER_A_PER_TYPE_NAME);
        importingEveryDiscoveredId();

        List<ImportSystemResult> results = serviceWithRealDeserializer().importSystemRequest(
                archiveOf(archive, "duplicates"), List.of(INTERNAL_SERVICE_ID), null, Set.of());

        assertTrue(results.isEmpty(), "an unselected colliding id produces no row: " + results);
    }

    /**
     * A dotted id groups under itself, not under its first segment. Cutting the id at the first dot piled
     * {@code a.b} onto service {@code a}: discovery un-hid the dotted file only for the collision grouping
     * to refuse both, and an ignore instruction for {@code a} claimed a file of another service.
     */
    @Test
    @DisplayName("a dotted id does not collide with the service its first segment names")
    void dottedIdDoesNotCollideWithItsFirstSegment(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "a", "a.service.qip.yaml");
        writeServiceFile(archive, "a.b", "a.b.service.qip.yaml");

        List<ImportSystemResult> preview = service.getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(List.of("a", "a.b"), idsOf(preview));
        preview.forEach(result -> assertEquals(SystemCompareAction.CREATE, result.getRequiredAction()));
    }

    @Test
    @DisplayName("a dotted id and its first-segment namesake both import")
    void dottedIdAndItsFirstSegmentNamesakeBothImport(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "a", "a.service.qip.yaml");
        writeServiceFile(archive, "a.b", "a.b.service.qip.yaml");
        importingEverything();

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer().importSystems(
                archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(List.of("a", "a.b"), idsOf(result.importSystemResults()));
        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(result.importSystemResults()));
    }

    /**
     * The exact shape every archive exported during the #553 window has: the service stored only under its
     * per-type name, the matching per-type {@code $schema} inside. The name stays discovered, the type comes
     * off the schema, and the service imports like any other.
     */
    @Test
    @DisplayName("a #553-window archive imports its per-type-named service")
    void aPost553WindowArchiveImportsItsPerTypeNamedService(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-553", "svc-553.external-service.qip.yaml", IntegrationSystemType.EXTERNAL);
        importingEverything();

        List<ImportSystemResult> preview = service.getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());
        assertEquals(SystemCompareAction.CREATE, resultFor(preview, "svc-553").getRequiredAction());

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer().importSystems(
                archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(ImportSystemStatus.CREATED, resultFor(result.importSystemResults(), "svc-553").getStatus());
        assertEquals(Map.of("svc-553", IntegrationSystemType.EXTERNAL), createdTypes(1));
    }

    // --- a name two scans claim --------------------------------------------------------------------------------------

    /**
     * {@code service-ctx.context-service.qip.yaml} is the context file of {@code service-ctx} and the legacy flat
     * plain-service file of {@code ctx.context-service.qip}. Both scans discover it, and only the document says which
     * kind it is. When it says context or MCP, that import creates the service and this one has to stay quiet: an
     * error row here marks the whole session failed over an import that succeeded.
     */
    @DisplayName("a file another import already has produces no plain-service row")
    @ParameterizedTest(name = "{0}")
    @MethodSource("filesOfAnotherKind")
    void fileOfAnotherKindProducesNoPlainServiceRow(String fileName, String schemaUri, @TempDir Path archive)
            throws IOException {
        writeDocument(archive, fileName, "$schema: \"" + schemaUri + "\"\nid: service-other\nname: Other service\n"
                + "content:\n  migrations: \"[100, 101, 102, 103, 104, 105]\"\n");
        importingEveryDiscoveredId();

        SystemExportImportService importService = serviceWithRealDeserializer();
        List<ImportSystemResult> preview =
                importService.getSystemsImportPreview(archive.toFile(), ImportInstructionsConfig.builder().build());
        ImportSystemsAndInstructionsResult imported =
                importService.importSystems(archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(List.of(), preview);
        assertEquals(List.of(), imported.importSystemResults());
        verify(systemService, never()).create(any(), anyBoolean());
    }

    /**
     * The same name, on the file it really is the flat name of. An id whose second segment spells another kind's
     * postfix is what every pre-#553 archive states whole, so this file has to keep importing through the plain scan —
     * the {@code $schema} is the plain service's, or an old archive carries none at all.
     */
    @DisplayName("a legacy flat service whose id spells another kind's postfix still imports")
    @ParameterizedTest(name = "{0} stating {2}")
    @MethodSource("legacyFlatFilesSpellingAnotherKindsPostfix")
    void legacyFlatServiceSpellingAnotherKindsPostfixStillImports(
            String fileName, String serviceId, String schemaUri, @TempDir Path archive) throws IOException {
        writeDocument(archive, fileName, (schemaUri == null ? "" : "$schema: \"" + schemaUri + "\"\n")
                + "id: " + serviceId + "\nname: Orders service\ncontent:\n"
                + "  integrationSystemType: EXTERNAL\n  migrations: \"[100, 101, 102, 103, 104, 105]\"\n");
        importingEverything();

        ImportSystemsAndInstructionsResult imported = serviceWithRealDeserializer()
                .importSystems(archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of());

        assertEquals(List.of(serviceId), idsOf(imported.importSystemResults()));
        assertEquals(Set.of(ImportSystemStatus.CREATED), statusesOf(imported.importSystemResults()));
        ArgumentCaptor<IntegrationSystem> created = ArgumentCaptor.forClass(IntegrationSystem.class);
        verify(systemService).create(created.capture(), anyBoolean());
        assertEquals(serviceId, created.getValue().getId());
        assertEquals(IntegrationSystemType.EXTERNAL, created.getValue().getIntegrationSystemType());
    }

    // --- what discovery reads --------------------------------------------------------------------------------------

    /**
     * Discovery reads a document only to settle a name two imports claim, and every other name settles itself. Reading
     * each candidate instead parses the whole archive twice, once here and once in the import that follows.
     */
    @Test
    @DisplayName("a file no other import could claim is not read during discovery")
    void unambiguousFileIsNotReadDuringDiscovery(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME);
        YAMLMapper mapper = spy(GoldenServiceCorpus.mapper());

        List<ImportSystemResult> preview = serviceReadingWith(mapper)
                .getSystemsImportPreview(archive.toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(List.of(EXTERNAL_SERVICE_ID), idsOf(preview));
        verify(mapper, times(1)).readTree(any(File.class));
    }

    /** The counter-case: the one name shape whose document decides which import has the file is read here. */
    @Test
    @DisplayName("a name another import may claim is read during discovery")
    void ambiguousFileIsReadDuringDiscovery(@TempDir Path archive) throws IOException {
        writeDocument(archive, "service-ctx.context-service.qip.yaml",
                "$schema: \"" + GoldenServiceCorpus.schemas().getService() + "\"\n"
                        + "id: ctx.context-service.qip\nname: Orders service\ncontent:\n"
                        + "  integrationSystemType: EXTERNAL\n  migrations: \"[100, 101, 102, 103, 104, 105]\"\n");
        YAMLMapper mapper = spy(GoldenServiceCorpus.mapper());

        List<ImportSystemResult> preview = serviceReadingWith(mapper)
                .getSystemsImportPreview(archive.toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(List.of("ctx.context-service.qip"), idsOf(preview));
        verify(mapper, times(2)).readTree(any(File.class));
    }

    // --- the service type on the preview path ------------------------------------------------------------------------

    /**
     * The preview runs the commit path's type rule, so a file whose $schema and document disagree is an error row
     * here rather than a clean CREATE followed by a failure the user only sees after committing.
     */
    @Test
    @DisplayName("a file whose $schema and document disagree on the type is previewed as an error")
    void schemaAndDocumentDisagreementIsPreviewedAsAnError(@TempDir Path archive) throws IOException {
        Path path = archive.resolve("services").resolve(EXTERNAL_SERVICE_ID).resolve(EXTERNAL_FILE_NAME);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "id: " + EXTERNAL_SERVICE_ID + "\n" + schemaLine(IntegrationSystemType.INTERNAL)
                + "name: Orders service\ncontent:\n  integrationSystemType: EXTERNAL\n");

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        ImportSystemResult result = resultFor(preview, EXTERNAL_SERVICE_ID);
        assertEquals(SystemCompareAction.ERROR, result.getRequiredAction());
        assertMessageContains(result, "INTERNAL");
        assertMessageContains(result, "EXTERNAL");
    }

    @Test
    @DisplayName("a file stating no type at all is previewed as an error")
    void fileStatingNoTypeIsPreviewedAsAnError(@TempDir Path archive) throws IOException {
        writeDocument(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME,
                "id: " + EXTERNAL_SERVICE_ID + "\nname: Orders service\ncontent:\n"
                        + "  description: \"\"\n  migrations: \"[100, 101, 102, 103, 104, 105]\"\n");

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(SystemCompareAction.ERROR, resultFor(preview, EXTERNAL_SERVICE_ID).getRequiredAction());
    }

    /**
     * The third refusal rule of the commit path. Without it, a file that would switch a stored service's type previews
     * as a clean UPDATE and only fails once the user has committed.
     */
    @Test
    @DisplayName("a file switching the type of a stored service is previewed as an error")
    void typeSwitchIsPreviewedAsAnError(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME, IntegrationSystemType.INTERNAL);
        when(systemService.getByIdOrNull(EXTERNAL_SERVICE_ID)).thenReturn(stored(IntegrationSystemType.EXTERNAL));

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        ImportSystemResult result = resultFor(preview, EXTERNAL_SERVICE_ID);
        assertEquals(SystemCompareAction.ERROR, result.getRequiredAction());
        assertMessageContains(result, "EXTERNAL");
        assertMessageContains(result, "INTERNAL");
    }

    @Test
    @DisplayName("a file keeping the type of a stored service is still previewed as an update")
    void unchangedTypeIsStillPreviewedAsAnUpdate(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME, IntegrationSystemType.INTERNAL);
        when(systemService.getByIdOrNull(EXTERNAL_SERVICE_ID)).thenReturn(stored(IntegrationSystemType.INTERNAL));

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        assertEquals(SystemCompareAction.UPDATE, resultFor(preview, EXTERNAL_SERVICE_ID).getRequiredAction());
    }

    // --- a row this version cannot export ----------------------------------------------------------------------------

    /**
     * "Export all services" is the operator's only way to get data out of an installation, and every refusal this
     * change added ran inside the loop over every service of the archive. One row of a shape no file name states, or
     * one legacy row with no type, returned an error and no archive at all — including for the services that were
     * fine.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unexportableRows")
    @DisplayName("one unexportable row costs one service, not the archive")
    void anUnexportableRowCostsOneService(String shape, String badId, IntegrationSystemType badType, boolean legacy)
            throws IOException {
        IntegrationSystem bad = systemWith(badType, 1);
        bad.setId(badId);
        when(systemService.getAll()).thenReturn(new ArrayList<>(
                List.of(plainService(EXTERNAL_SERVICE_ID, IntegrationSystemType.EXTERNAL), bad,
                        plainService(INTERNAL_SERVICE_ID, IntegrationSystemType.INTERNAL))));

        byte[] archive = serviceExporting(legacy).exportSystemsRequest(null, List.of());

        assertNotNull(archive, "an unexportable row must not take the archive with it: " + shape);
        assertEquals(List.of(EXTERNAL_SERVICE_ID, INTERNAL_SERVICE_ID), serviceDirectoriesOf(archive),
                "every other service is in the archive: " + shape);
        assertEquals(List.of(EXTERNAL_SERVICE_ID, INTERNAL_SERVICE_ID), loggedExportIds(),
                "the action log records what the archive holds: " + shape);
    }

    /** Nothing exportable at all reads as an empty export, which the controller answers 204 for. */
    @Test
    @DisplayName("an archive of nothing but unexportable rows is not produced")
    void anArchiveOfUnexportableRowsOnlyIsNotProduced() {
        IntegrationSystem typeless = systemWith(null, 1);
        typeless.setId("svc-typeless");
        when(systemService.getAll()).thenReturn(new ArrayList<>(List.of(typeless)));

        assertNull(serviceExporting(false).exportSystemsRequest(null, List.of()));
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** One row of each shape the export refuses, in the format that refuses it. */
    private static Stream<Arguments> unexportableRows() {
        return Stream.of(
                Arguments.of("a dotted id", "a.b", IntegrationSystemType.EXTERNAL, false),
                Arguments.of("an id whose second segment spells a postfix", "svc.internal-service.1",
                        IntegrationSystemType.INTERNAL, false),
                Arguments.of("the same id in the legacy format", "svc.internal-service.1",
                        IntegrationSystemType.INTERNAL, true),
                Arguments.of("no type", "svc-typeless", null, false),
                Arguments.of("no type in the legacy format", "svc-typeless", null, true));
    }

    /** The name shape both scans claim, on the two kinds whose own import reads the document to confirm it. */
    private static Stream<Arguments> filesOfAnotherKind() {
        return Stream.of(
                Arguments.of("service-ctx.context-service.qip.yaml", GoldenServiceCorpus.schemas().getContextService()),
                Arguments.of("service-mcp.mcp-service.qip.yaml", GoldenServiceCorpus.schemas().getMcpService()));
    }

    /** The same names as flat plain-service ones: stating the plain {@code $schema}, and stating none. */
    private static Stream<Arguments> legacyFlatFilesSpellingAnotherKindsPostfix() {
        return Stream.of(
                Arguments.of("service-ctx.context-service.qip.yaml", "ctx.context-service.qip",
                        GoldenServiceCorpus.schemas().getService()),
                Arguments.of("service-ctx.context-service.qip.yaml", "ctx.context-service.qip", null),
                Arguments.of("service-mcp.mcp-service.qip.yaml", "mcp.mcp-service.qip",
                        GoldenServiceCorpus.schemas().getService()),
                Arguments.of("service-mcp.mcp-service.qip.yaml", "mcp.mcp-service.qip", null));
    }

    private void importing(IntegrationSystem system) {
        when(serviceDeserializer.deserializeSystem(serviceFile)).thenReturn(system);
    }

    private void importingEverything() {
        importingEveryDiscoveredId();
        when(systemService.getByIdOrNull(any())).thenReturn(null);
    }

    private void importingEveryDiscoveredId() {
        when(importInstructionsService.performServiceIgnoreInstructions(any(), anyBoolean()))
                .thenAnswer(invocation -> new IgnoreResult(invocation.getArgument(0), List.of()));
    }

    /** The preview path over a caller-supplied mapper, for the tests that count what the archive walk reads. */
    private SystemExportImportService serviceReadingWith(YAMLMapper mapper) {
        return new SystemExportImportService(
                transactionTemplate,
                systemService,
                environmentService,
                systemModelService,
                mapper,
                actionLogger,
                auditingHandler,
                serviceSerializer,
                serviceDeserializer,
                archiveWriter,
                importProgressService,
                importInstructionsService,
                elementHelperService,
                chainService,
                apiGroupService,
                GoldenServiceCorpus.serviceTypeFiles());
    }

    /** The export path over the production serializer and archive writer, which is where the refusals live. */
    private SystemExportImportService serviceExporting(boolean legacy) {
        SystemExportImportService exporting = new SystemExportImportService(
                transactionTemplate,
                systemService,
                environmentService,
                systemModelService,
                GoldenServiceCorpus.mapper(),
                actionLogger,
                auditingHandler,
                GoldenServiceCorpus.serviceSerializer(legacy),
                serviceDeserializer,
                GoldenServiceCorpus.archiveWriter(legacy),
                importProgressService,
                importInstructionsService,
                elementHelperService,
                chainService,
                apiGroupService,
                GoldenServiceCorpus.serviceTypeFiles());
        ReflectionTestUtils.setField(exporting, "removeUnusedSpecs", false);
        return exporting;
    }

    private static IntegrationSystem plainService(String id, IntegrationSystemType type) {
        IntegrationSystem system = systemWith(type, 1);
        system.setId(id);
        system.setName(id);
        return system;
    }

    /** The service directories an archive holds, which is one per exported service. */
    private static List<String> serviceDirectoriesOf(byte[] archive) throws IOException {
        Set<String> directories = new TreeSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String[] segments = entry.getName().split("/");
                if (segments.length > 1) {
                    directories.add(segments[1]);
                }
            }
        }
        return List.copyOf(directories);
    }

    private List<String> loggedExportIds() {
        ArgumentCaptor<ActionLog> logged = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogger, atLeastOnce()).logAction(logged.capture());
        return logged.getAllValues().stream().map(ActionLog::getEntityId).sorted().toList();
    }

    private SystemExportImportService serviceWithRealDeserializer() {
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
                apiGroupService,
                GoldenServiceCorpus.serviceTypeFiles());
    }

    /**
     * A version claim no migration registry holds. {@code FileMigrationService} throws on it, and the question this
     * answers is where that throw surfaces: the per-service catch in {@code importOneSystemInTransaction} turns it
     * into one ERROR row, so the rest of the archive still imports. Measured rather than read off the barrier's
     * documentation — a throw that escaped that catch would end the whole import session, which by then has already
     * applied instructions and variables.
     */
    @Test
    @DisplayName("a service claiming an unknown migration version becomes one error row")
    void unknownMigrationVersionIsOneErrorRow(@TempDir Path archive) throws IOException {
        importingEverything();
        writeDocument(archive, EXTERNAL_SERVICE_ID, EXTERNAL_FILE_NAME,
                "id: " + EXTERNAL_SERVICE_ID + "\n" + schemaLine(IntegrationSystemType.EXTERNAL)
                + "name: Orders service\ncontent:\n  description: \"\"\n  migrations: \"[100, 999]\"\n");
        writeServiceFile(archive, INTERNAL_SERVICE_ID, INTERNAL_FILE_NAME);

        List<ImportSystemResult> results = serviceWithRealDeserializer().importSystems(
                archive.toFile(), new SystemsCommitRequest(), IMPORT_ID, Set.of()).importSystemResults();

        assertEquals(List.of(EXTERNAL_SERVICE_ID, INTERNAL_SERVICE_ID), idsOf(results));
        assertEquals(Map.of(
                        EXTERNAL_SERVICE_ID, ImportSystemStatus.ERROR,
                        INTERNAL_SERVICE_ID, ImportSystemStatus.CREATED),
                results.stream().collect(Collectors.toMap(ImportSystemResult::getId, ImportSystemResult::getStatus)),
                "the unknown version has to cost one service, not the archive");
    }

    /** Every service the commit path handed to {@code systemService.create}, keyed by id. */
    private Map<String, IntegrationSystem> createdSystems() {
        return createdSystems(GOLDEN_SERVICE_IDS.size());
    }

    private Map<String, IntegrationSystem> createdSystems(int count) {
        ArgumentCaptor<IntegrationSystem> created = ArgumentCaptor.forClass(IntegrationSystem.class);
        verify(systemService, times(count)).create(created.capture(), anyBoolean());
        return created.getAllValues().stream().collect(Collectors.toMap(IntegrationSystem::getId, system -> system));
    }

    private static List<String> groupIdsOf(IntegrationSystem system) {
        return system.getApiGroups() == null
                ? List.of()
                : system.getApiGroups().stream().map(ApiGroup::getId).sorted().toList();
    }

    private Map<String, IntegrationSystemType> createdTypes() {
        return createdTypes(GOLDEN_SERVICE_IDS.size());
    }

    private Map<String, IntegrationSystemType> createdTypes(int count) {
        return createdSystems(count).values().stream().collect(Collectors.toMap(
                IntegrationSystem::getId,
                system -> requireNonNull(system.getIntegrationSystemType(),
                        () -> "service " + system.getId() + " was imported with no type")));
    }

    private static void assertMessageContains(ImportSystemResult result, String expected) {
        assertTrue(result.getMessage() != null && result.getMessage().contains(expected),
                () -> "expected the message to contain '" + expected + "', got: " + result.getMessage());
    }

    private static Set<ImportSystemStatus> statusesOf(List<ImportSystemResult> results) {
        return results.stream().map(ImportSystemResult::getStatus).collect(Collectors.toSet());
    }

    private static List<String> idsOf(List<ImportSystemResult> results) {
        return results.stream().map(ImportSystemResult::getId).sorted().toList();
    }

    /** A golden set, zipped the way an exported archive is laid out: every entry under {@code services/}. */
    private static MockMultipartFile archiveOf(String setName) throws IOException {
        return archiveOf(GoldenServiceCorpus.set(setName), setName);
    }

    private static MockMultipartFile archiveOf(Path root, String setName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace(File.separatorChar, '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", setName + ".zip", "application/zip", bytes.toByteArray());
    }

    private static void writeServiceFile(Path archive, String serviceId, String fileName) throws IOException {
        writeServiceFile(archive, serviceId, fileName, IntegrationSystemType.EXTERNAL);
    }

    /**
     * Carries a `$schema`, which is what states the type, and a `content` block with a version claim, so the same
     * file is importable on the commit path and not only readable by the preview, which reads the raw node.
     */
    private static void writeServiceFile(
            Path archive, String serviceId, String fileName, IntegrationSystemType type) throws IOException {
        writeDocument(archive, serviceId, fileName, "id: " + serviceId + "\n" + schemaLine(type)
                + "name: Orders service\ncontent:\n"
                + "  description: \"\"\n  migrations: \"[100, 101, 102, 103, 104, 105]\"\n");
    }

    private static String schemaLine(IntegrationSystemType type) {
        return "$schema: \"" + GoldenServiceCorpus.serviceTypeFiles().schemaUri(type) + "\"\n";
    }

    /** A file under a caller-chosen name, for the cases where the name is what is under test. */
    private static void writeDocument(Path archive, String fileName, String yaml) throws IOException {
        writeDocument(archive, "svc", fileName, yaml);
    }

    private static void writeDocument(Path archive, String directory, String fileName, String yaml)
            throws IOException {
        Path path = archive.resolve("services").resolve(directory).resolve(fileName);
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
    }

    private static ImportSystemResult resultFor(List<ImportSystemResult> results, String serviceId) {
        return results.stream()
                .filter(result -> serviceId.equals(result.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result for " + serviceId + " in " + idsOf(results)));
    }

    /** The stored counterpart of the file the preview tests write. */
    private static IntegrationSystem stored(IntegrationSystemType type) {
        return IntegrationSystem.builder()
                .id(EXTERNAL_SERVICE_ID)
                .name("Orders service")
                .integrationSystemType(type)
                .build();
    }
}

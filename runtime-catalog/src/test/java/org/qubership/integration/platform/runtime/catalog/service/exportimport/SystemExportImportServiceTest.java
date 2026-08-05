package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.LEGACY_FLAT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.POST553;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus.PRE553_CURRENT;

@ExtendWith(MockitoExtension.class)
class SystemExportImportServiceTest {

    private static final String SYSTEM_ID = "system-1";
    private static final String SYSTEM_NAME = "Test service";
    /** The plain services of every golden set. The context and the MCP service are imported elsewhere. */
    private static final List<String> GOLDEN_SERVICE_IDS =
            List.of("svc-external", "svc-implemented", "svc-internal");

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
                apiGroupService);

        serviceFile = tempDir.resolve(SYSTEM_ID + ".service.qip.yaml").toFile();
        Files.writeString(serviceFile.toPath(),
                "id: " + SYSTEM_ID + "\nname: " + SYSTEM_NAME + "\n", StandardCharsets.UTF_8);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    @DisplayName("a second environment is rejected when creating an internal service on import")
    void secondEnvironmentIsRejectedOnCreateForInternalService() {
        importing(systemWith(IntegrationSystemType.INTERNAL, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("internal"));
        assertThat(result.getMessage(), containsString(SYSTEM_ID));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("a second environment is rejected when creating an implemented service on import")
    void secondEnvironmentIsRejectedOnCreateForImplementedService() {
        importing(systemWith(IntegrationSystemType.IMPLEMENTED, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("implemented"));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("a second environment is accepted when creating an external service on import")
    void secondEnvironmentIsAcceptedOnCreateForExternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.EXTERNAL, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.CREATED));
        verify(systemService).create(system, true);
    }

    @Test
    @DisplayName("a single environment is accepted when creating an internal service on import")
    void singleEnvironmentIsAcceptedOnCreateForInternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.INTERNAL, 1);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.CREATED));
        verify(systemService).create(system, true);
    }

    @Test
    @DisplayName("a service row with no type does not crash the import")
    void typelessServiceDoesNotCrashTheImport() {
        IntegrationSystem system = systemWith(null, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.CREATED));
    }

    @Test
    @DisplayName("a second environment is rejected when updating an internal service on import")
    void secondEnvironmentIsRejectedOnUpdateForInternalService() {
        importing(systemWith(IntegrationSystemType.INTERNAL, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("internal"));
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("a second environment is rejected when updating an implemented service on import")
    void secondEnvironmentIsRejectedOnUpdateForImplementedService() {
        importing(systemWith(IntegrationSystemType.IMPLEMENTED, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.IMPLEMENTED, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("implemented"));
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("a second environment is accepted when updating an external service on import")
    void secondEnvironmentIsAcceptedOnUpdateForExternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.EXTERNAL, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.EXTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.UPDATED));
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

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("EXTERNAL"));
        assertThat(result.getMessage(), containsString("INTERNAL"));
        assertThat(result.getMessage(), containsString(SYSTEM_ID));
        assertThat(stored.getIntegrationSystemType(), equalTo(IntegrationSystemType.EXTERNAL));
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("importing the stored type is not treated as a type change")
    void importingTheSameTypeIsAccepted() {
        IntegrationSystem system = systemWith(IntegrationSystemType.INTERNAL, 1);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.UPDATED));
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

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.UPDATED));
        verify(systemService).update(system);
    }

    // --- archive discovery -----------------------------------------------------------------------------------------

    /**
     * Discovery has to read all four service postfixes plus the legacy flat prefix. Run over the golden corpus, so a
     * format the exporter writes and the importer cannot find fails here rather than in production.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    void theImportPreviewFindsEveryPlainServiceOfAnArchive(String setName) {
        List<ImportSystemResult> preview = service.getSystemsImportPreview(
                GoldenServiceCorpus.set(setName).toFile(), ImportInstructionsConfig.builder().build());

        assertThat(idsOf(preview), equalTo(GOLDEN_SERVICE_IDS));
        preview.forEach(result -> assertThat(result.getRequiredAction(), equalTo(SystemCompareAction.CREATE)));
    }

    /** The same discovery through the zip entry point the UI calls. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    void theImportPreviewRequestFindsEveryPlainServiceOfAnArchive(String setName) throws IOException {
        when(importInstructionsService.getServiceImportInstructionsConfig(any()))
                .thenReturn(ImportInstructionsConfig.builder().build());

        List<ImportSystemResult> preview = service.getSystemsImportPreviewRequest(archiveOf(setName));

        assertThat(idsOf(preview), equalTo(GOLDEN_SERVICE_IDS));
        preview.forEach(result -> assertThat(result.getRequiredAction(), equalTo(SystemCompareAction.CREATE)));
    }

    /**
     * The commit path, with the real deserializer: every archive format imports, and each service lands under the type
     * its file states. A null type here is the failure mode the whole of #553 is exposed to — the column is nullable
     * and a null only surfaces later, as an NPE in {@code EntityType.getSystemType}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    void everyArchiveFormatImportsWithItsType(String setName) {
        importingEverything();

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer().importSystems(
                GoldenServiceCorpus.set(setName).toFile(), new SystemsCommitRequest(), "import-1", Set.of());

        assertThat(idsOf(result.importSystemResults()), equalTo(GOLDEN_SERVICE_IDS));
        assertThat(statusesOf(result.importSystemResults()), equalTo(Set.of(ImportSystemStatus.CREATED)));
        assertThat(createdTypes(), equalTo(Map.of(
                "svc-external", IntegrationSystemType.EXTERNAL,
                "svc-internal", IntegrationSystemType.INTERNAL,
                "svc-implemented", IntegrationSystemType.IMPLEMENTED)));
    }

    /** The same, through the zip entry point. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PRE553_CURRENT, POST553, LEGACY_FLAT})
    void everyArchiveFormatImportsWithItsTypeThroughTheZipRequest(String setName) throws IOException {
        importingEverything();

        List<ImportSystemResult> results =
                serviceWithRealDeserializer().importSystemRequest(archiveOf(setName), null, null, Set.of());

        assertThat(idsOf(results), equalTo(GOLDEN_SERVICE_IDS));
        assertThat(statusesOf(results), equalTo(Set.of(ImportSystemStatus.CREATED)));
        assertThat(createdTypes(), equalTo(Map.of(
                "svc-external", IntegrationSystemType.EXTERNAL,
                "svc-internal", IntegrationSystemType.INTERNAL,
                "svc-implemented", IntegrationSystemType.IMPLEMENTED)));
    }

    /**
     * Two files for one id can only be resolved by guessing, and the per-file loop would import both in separate
     * transactions, letting the last one win. The id gets an error row and the rest of the archive still imports —
     * a throw here would end the whole import session, which by then has already applied instructions and variables.
     */
    @Test
    void twoServiceFilesForOneServiceArePreviewedAsAnError(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.external-service.qip.yaml");
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        writeServiceFile(archive, "svc-internal", "svc-internal.internal-service.qip.yaml");

        List<ImportSystemResult> preview = service.getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        ImportSystemResult colliding = resultFor(preview, "svc-external");
        assertThat(colliding.getRequiredAction(), equalTo(SystemCompareAction.ERROR));
        assertThat(colliding.getMessage(), containsString("svc-external.external-service.qip.yaml"));
        assertThat(colliding.getMessage(), containsString("svc-external.internal-service.qip.yaml"));
        assertThat(resultFor(preview, "svc-internal").getRequiredAction(), equalTo(SystemCompareAction.CREATE));
    }

    @Test
    void twoServiceFilesForOneServiceImportNeitherOfThem(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.external-service.qip.yaml");
        writeServiceFile(archive, "svc-external", "service-svc-external.yaml");
        importingEveryDiscoveredId();

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer().importSystems(
                archive.toFile(), new SystemsCommitRequest(), "import-1", Set.of());

        assertThat(statusesOf(result.importSystemResults()), equalTo(Set.of(ImportSystemStatus.ERROR)));
        verify(systemService, never()).create(any(), anyBoolean());
        verify(systemService, never()).update(any());
    }

    /** The same degradation through the zip entry point, whose temp directory is also cleaned up on the way out. */
    @Test
    void twoServiceFilesForOneServiceAreReportedThroughTheZipRequest(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.external-service.qip.yaml");
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        importingEveryDiscoveredId();

        List<ImportSystemResult> results = serviceWithRealDeserializer()
                .importSystemRequest(archiveOf(archive, "duplicates"), null, null, Set.of());

        assertThat(idsOf(results), equalTo(List.of("svc-external")));
        assertThat(statusesOf(results), equalTo(Set.of(ImportSystemStatus.ERROR)));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    /**
     * A colliding id has to pass the same selection and ignore filters as every other id. A service the request never
     * selected produces no row at all, and a single error row is enough to mark the whole session failed.
     */
    @Test
    void aCollidingIdTheRequestDidNotSelectIsNotReportedAtAll(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.external-service.qip.yaml");
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        writeServiceFile(archive, "svc-internal", "svc-internal.internal-service.qip.yaml");
        importingEveryDiscoveredId();
        when(systemService.getByIdOrNull(any())).thenReturn(null);

        SystemsCommitRequest request = new SystemsCommitRequest();
        request.setImportMode(ImportMode.PARTIAL);
        request.setSystemIds(List.of("svc-internal"));
        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer()
                .importSystems(archive.toFile(), request, "import-1", Set.of());

        assertThat(idsOf(result.importSystemResults()), equalTo(List.of("svc-internal")));
        assertThat(statusesOf(result.importSystemResults()), equalTo(Set.of(ImportSystemStatus.CREATED)));
    }

    /** An IGNORE instruction excludes the id before the collision matters, so the row says IGNORED, not ERROR. */
    @Test
    void aCollidingIdExcludedByAnIgnoreInstructionIsReportedAsIgnored(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.external-service.qip.yaml");
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        when(importInstructionsService.performServiceIgnoreInstructions(any(), anyBoolean()))
                .thenReturn(new IgnoreResult(Set.of(), List.of()));

        ImportSystemsAndInstructionsResult result = serviceWithRealDeserializer()
                .importSystems(archive.toFile(), new SystemsCommitRequest(), "import-1", Set.of());

        ImportSystemResult row = resultFor(result.importSystemResults(), "svc-external");
        assertThat(row.getStatus(), equalTo(ImportSystemStatus.IGNORED));
    }

    /** The same two filters on the zip entry point, which carries its selection as the {@code systemIds} argument. */
    @Test
    void aCollidingIdIsFilteredTheSameWayThroughTheZipRequest(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.external-service.qip.yaml");
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        importingEveryDiscoveredId();

        List<ImportSystemResult> results = serviceWithRealDeserializer().importSystemRequest(
                archiveOf(archive, "duplicates"), List.of("svc-internal"), null, Set.of());

        assertThat(results, empty());
    }

    // --- the service type on the preview path ------------------------------------------------------------------------

    /**
     * The preview runs the commit path's type rule, so a file whose name and document disagree is an error row here
     * rather than a clean CREATE followed by a failure the user only sees after committing.
     */
    @Test
    void aFileWhoseNameAndDocumentDisagreeOnTheTypeIsPreviewedAsAnError(@TempDir Path archive) throws IOException {
        Path path = archive.resolve("services").resolve("svc-external")
                .resolve("svc-external.internal-service.qip.yaml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "id: svc-external\nname: Orders service\ncontent:\n"
                + "  integrationSystemType: EXTERNAL\n");

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        ImportSystemResult result = resultFor(preview, "svc-external");
        assertThat(result.getRequiredAction(), equalTo(SystemCompareAction.ERROR));
        assertThat(result.getMessage(), containsString("INTERNAL"));
        assertThat(result.getMessage(), containsString("EXTERNAL"));
    }

    @Test
    void aFileStatingNoTypeAtAllIsPreviewedAsAnError(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.service.qip.yaml");

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        assertThat(resultFor(preview, "svc-external").getRequiredAction(), equalTo(SystemCompareAction.ERROR));
    }

    /**
     * The third refusal rule of the commit path. Without it, a file that would switch a stored service's type previews
     * as a clean UPDATE and only fails once the user has committed.
     */
    @Test
    void aFileSwitchingTheTypeOfAStoredServiceIsPreviewedAsAnError(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        when(systemService.getByIdOrNull("svc-external")).thenReturn(stored(IntegrationSystemType.EXTERNAL));

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        ImportSystemResult result = resultFor(preview, "svc-external");
        assertThat(result.getRequiredAction(), equalTo(SystemCompareAction.ERROR));
        assertThat(result.getMessage(), containsString("EXTERNAL"));
        assertThat(result.getMessage(), containsString("INTERNAL"));
    }

    @Test
    void aFileKeepingTheTypeOfAStoredServiceIsStillPreviewedAsAnUpdate(@TempDir Path archive) throws IOException {
        writeServiceFile(archive, "svc-external", "svc-external.internal-service.qip.yaml");
        when(systemService.getByIdOrNull("svc-external")).thenReturn(stored(IntegrationSystemType.INTERNAL));

        List<ImportSystemResult> preview = serviceWithRealDeserializer().getSystemsImportPreview(
                archive.toFile(), ImportInstructionsConfig.builder().build());

        assertThat(resultFor(preview, "svc-external").getRequiredAction(), equalTo(SystemCompareAction.UPDATE));
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

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
                apiGroupService);
    }

    private Map<String, IntegrationSystemType> createdTypes() {
        ArgumentCaptor<IntegrationSystem> created = ArgumentCaptor.forClass(IntegrationSystem.class);
        verify(systemService, times(GOLDEN_SERVICE_IDS.size())).create(created.capture(), anyBoolean());
        return created.getAllValues().stream().collect(Collectors.toMap(
                IntegrationSystem::getId,
                system -> requireNonNull(system.getIntegrationSystemType(),
                        () -> "service " + system.getId() + " was imported with no type")));
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
        Path path = archive.resolve("services").resolve(serviceId).resolve(fileName);
        Files.createDirectories(path.getParent());
        // Carries a `content` block with a version claim, so the same file is importable on the commit path and not
        // only readable by the preview, which reads the raw node.
        Files.writeString(path, "id: " + serviceId + "\nname: Orders service\ncontent:\n"
                + "  description: \"\"\n  migrations: \"[100, 101, 102, 103, 104, 105]\"\n");
    }

    private static ImportSystemResult resultFor(List<ImportSystemResult> results, String serviceId) {
        return results.stream()
                .filter(result -> serviceId.equals(result.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result for " + serviceId + " in " + idsOf(results)));
    }

    /** The stored counterpart of the {@code svc-external} file the preview tests write. */
    private static IntegrationSystem stored(IntegrationSystemType type) {
        return IntegrationSystem.builder()
                .id("svc-external")
                .name("Orders service")
                .integrationSystemType(type)
                .build();
    }

    private static IntegrationSystem systemWith(IntegrationSystemType type, int environmentCount) {
        List<Environment> environments = new LinkedList<>();
        for (int i = 0; i < environmentCount; i++) {
            Environment environment = new Environment();
            environment.setId("environment-" + (i + 1));
            environment.setLabels(new ArrayList<>());
            environments.add(environment);
        }
        return IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .integrationSystemType(type)
                .environments(environments)
                .build();
    }
}

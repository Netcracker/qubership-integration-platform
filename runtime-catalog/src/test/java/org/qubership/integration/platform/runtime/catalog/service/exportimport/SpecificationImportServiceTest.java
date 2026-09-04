package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SystemModelLibraryGenerationException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.service.ConfigParameterService;
import org.qubership.integration.platform.runtime.catalog.service.SystemBaseService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.OperationParserService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A specification import runs asynchronously, so the stored status is the only place its failure is
 * reported. These tests pin what a failure leaves behind: the status stays readable, the group the
 * import created is removed, and a rollback that fails cannot take the place of the real cause.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpecificationImportServiceTest {

    private static final String IMPORT_ID = "import-1";
    private static final String GROUP_ID = "system-1-group";

    @Mock
    private OperationParserService operationParserService;
    @Mock
    private SpecificationGroupRepository specificationGroupRepository;
    @Mock
    private SpecificationSourceRepository specificationSourceRepository;
    @Mock
    private ConfigParameterService configParameterService;
    @Mock
    private ProtocolExtractionService protocolExtractionService;
    @Mock
    private SystemBaseService systemBaseService;
    @Mock
    private SystemModelBaseService systemModelService;

    private SpecificationImportService importService;

    @BeforeEach
    void setUp() {
        importService = new SpecificationImportService(operationParserService, specificationGroupRepository,
                specificationSourceRepository, configParameterService, protocolExtractionService,
                new ObjectMapper(), systemBaseService, systemModelService, null);
    }

    private MultipartFile[] specificationFiles() {
        return new MultipartFile[]{
                new MockMultipartFile("files", "api.json", "application/json", "{}".getBytes())};
    }

    private void givenAnHttpGroup() {
        IntegrationSystem system = new IntegrationSystem();
        system.setId("system-1");
        system.setProtocol(OperationProtocol.HTTP);
        SpecificationGroup group = new SpecificationGroup();
        group.setId(GROUP_ID);
        group.setSystem(system);
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(group);
        when(protocolExtractionService.getOperationProtocol(any())).thenReturn(OperationProtocol.HTTP);
    }

    private void givenParseFails(Throwable failure) {
        when(operationParserService.parse(anyString(), anyString(), any(), anyBoolean(), anySet(), any()))
                .thenReturn(CompletableFuture.failedFuture(failure));
    }

    private String storedStatus() {
        ArgumentCaptor<ConfigParameter> captor = ArgumentCaptor.forClass(ConfigParameter.class);
        verify(configParameterService, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1).getString();
    }

    private void givenStatus(String json) {
        ConfigParameter parameter = new ConfigParameter(
                SpecificationImportService.SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, IMPORT_ID);
        parameter.setString(json);
        when(configParameterService.findByName(
                SpecificationImportService.SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, IMPORT_ID))
                .thenReturn(parameter);
    }

    @Test
    @DisplayName("a failed import reports the same cause on every poll")
    void aFailedImportReportsTheSameCauseOnEveryPoll() {
        givenStatus("{\"importIsDone\":true,\"errorMessage\":\"protoc is missing\",\"business\":false}");

        for (int poll = 0; poll < 3; poll++) {
            assertThatThrownBy(() -> importService.importSessionIsDone(IMPORT_ID))
                    .isInstanceOf(SpecificationImportException.class)
                    .hasMessage("protoc is missing");
        }

        verify(configParameterService, never()).deleteByName(anyString(), anyString());
    }

    @Test
    @DisplayName("a finished import drops its status once it has been read")
    void aFinishedImportDropsItsStatusOnceItHasBeenRead() {
        givenStatus("{\"importIsDone\":true,\"business\":false}");

        assertThat(importService.importSessionIsDone(IMPORT_ID)).isTrue();

        verify(configParameterService).deleteByName(
                SpecificationImportService.SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, IMPORT_ID);
    }

    @Test
    @DisplayName("a failed import removes the specification group it created itself")
    void aFailedImportRemovesTheSpecificationGroupItCreatedItself() {
        givenAnHttpGroup();
        givenParseFails(new SystemModelLibraryGenerationException(
                "Failed to generate source code.", new IllegalStateException("protoc is missing")));

        importService.importSpecification(GROUP_ID, specificationFiles(), true);

        verify(specificationGroupRepository).deleteById(GROUP_ID);
        assertThat(storedStatus()).contains("Failed to generate source code.");
    }

    @Test
    @DisplayName("an import into an existing group leaves that group alone")
    void anImportIntoAnExistingGroupLeavesThatGroupAlone() {
        givenAnHttpGroup();
        givenParseFails(new IllegalStateException("parser blew up"));

        importService.importSpecification(GROUP_ID, specificationFiles());

        verify(specificationGroupRepository, never()).deleteById(anyString());
        assertThat(storedStatus()).contains("parser blew up");
    }

    @Test
    @DisplayName("a group that cannot be removed does not replace the reported cause")
    void aGroupThatCannotBeRemovedDoesNotReplaceTheReportedCause() {
        givenAnHttpGroup();
        givenParseFails(new IllegalStateException("parser blew up"));
        doThrow(new IllegalStateException("group is still referenced"))
                .when(specificationGroupRepository).deleteById(GROUP_ID);

        importService.importSpecification(GROUP_ID, specificationFiles(), true);

        assertThat(storedStatus())
                .contains("parser blew up")
                .doesNotContain("group is still referenced");
    }

    @Test
    @DisplayName("a re-imported version is recorded as a rejection, not a failure")
    void aReImportedVersionIsRecordedAsARejection() {
        givenAnHttpGroup();
        givenParseFails(new SpecificationSimilarVersionException("1.0.0"));

        importService.importSpecification(GROUP_ID, specificationFiles(), true);

        assertThat(storedStatus())
                .contains("1.0.0")
                .contains("\"business\":true");
    }

    @Test
    @DisplayName("a rollback that fails does not replace the reported cause")
    void aRollbackThatFailsDoesNotReplaceTheReportedCause() {
        SystemModel model = new SystemModel();
        model.setId("system-1-group-1.0.0");
        when(operationParserService.parse(anyString(), anyString(), any(), anyBoolean(), anySet(), any()))
                .thenReturn(CompletableFuture.completedFuture(model));
        doThrow(new SystemModelLibraryGenerationException("Failed to compile code.", new IllegalStateException("javac")))
                .when(systemModelService).patchModelWithCompiledLibrary(model);
        doThrow(new IllegalStateException("delete failed")).when(systemModelService).delete(model);

        CompletableFuture<SystemModel> future = importService.importSimpleSpecification(
                "api.json", GROUP_ID, "http", "{}", java.util.Collections.emptySet(), message -> { });

        assertThatThrownBy(future::join)
                .hasRootCauseMessage("Failed to compile code.")
                .satisfies(thrown -> assertThat(thrown.getCause().getSuppressed())
                        .as("the rollback failure is suppressed, not reported in its place")
                        .extracting(Throwable::getMessage)
                        .containsExactly("delete failed"));
        verify(systemModelService).delete(model);
    }
}

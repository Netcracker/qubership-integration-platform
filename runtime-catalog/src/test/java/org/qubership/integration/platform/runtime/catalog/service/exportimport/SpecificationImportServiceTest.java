package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.service.ConfigParameterService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The import status is the only place a failed asynchronous import reports its cause, so it has to
 * survive a repeated poll — a retry, a reloaded page, a second client.
 */
@ExtendWith(MockitoExtension.class)
class SpecificationImportServiceTest {

    private static final String IMPORT_ID = "import-1";

    @Mock
    private ConfigParameterService configParameterService;

    private SpecificationImportService importService() {
        return new SpecificationImportService(null, null, null, configParameterService,
                null, new ObjectMapper(), null, null, null);
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
        SpecificationImportService service = importService();

        for (int poll = 0; poll < 3; poll++) {
            assertThatThrownBy(() -> service.importSessionIsDone(IMPORT_ID))
                    .isInstanceOf(SpecificationImportException.class)
                    .hasMessage("protoc is missing");
        }

        verify(configParameterService, never()).deleteByName(anyString(), anyString());
    }

    @Test
    @DisplayName("a finished import drops its status once it has been read")
    void aFinishedImportDropsItsStatusOnceItHasBeenRead() {
        givenStatus("{\"importIsDone\":true,\"business\":false}");
        SpecificationImportService service = importService();

        assertThat(service.importSessionIsDone(IMPORT_ID)).isTrue();

        verify(configParameterService).deleteByName(
                SpecificationImportService.SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, IMPORT_ID);
    }
}

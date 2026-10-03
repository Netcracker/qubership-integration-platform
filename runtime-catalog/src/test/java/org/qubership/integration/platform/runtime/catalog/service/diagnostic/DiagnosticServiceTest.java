package org.qubership.integration.platform.runtime.catalog.service.diagnostic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic.ValidationStatusRepository;
import org.qubership.integration.platform.runtime.catalog.service.ConfigParameterService;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.ValidationAlreadyInProgressUnexpectedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagnosticServiceTest {

    @Mock
    private ValidationStatusRepository validationStatusRepository;
    @Mock
    private ConfigParameterService configParameterService;
    @Mock
    private TransactionHandler transactionHandler;

    private DiagnosticService diagnosticService;

    @BeforeEach
    void setUp() {
        diagnosticService = new DiagnosticService(null, List.of(), validationStatusRepository,
                configParameterService, transactionHandler, null, null);
    }

    @Test
    void runValidationsAsyncRejectsRunWhenLockIsTaken() {
        when(configParameterService.tryLock(eq("diagnostic"), eq("diagnosticValidationUpdateLock"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> diagnosticService.runValidationsAsync(null))
                .isInstanceOf(ValidationAlreadyInProgressUnexpectedException.class);
        verifyNoInteractions(transactionHandler, validationStatusRepository);
    }
}

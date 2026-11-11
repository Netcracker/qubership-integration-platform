package org.qubership.integration.platform.runtime.catalog.service.diagnostic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationState;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationStatus;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic.ValidationChainAlertRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic.ValidationStatusRepository;
import org.qubership.integration.platform.runtime.catalog.service.ConfigParameterService;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.ValidationAlreadyInProgressUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.builtin.BuiltinValidation;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
    @Mock
    private ValidationChainAlertRepository chainAlertRepository;
    @Mock
    private BuiltinValidation validation;

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

    @Test
    void runValidationsAsyncSavesInProgressBeforeReturning() {
        when(validation.getId()).thenReturn("built-in_rule");
        when(validation.getEntityType()).thenReturn(ValidationEntityType.CHAIN);
        diagnosticService = new DiagnosticService(chainAlertRepository, List.of(validation), validationStatusRepository,
                configParameterService, transactionHandler, null, null);
        when(configParameterService.tryLock(eq("diagnostic"), eq("diagnosticValidationUpdateLock"), any()))
                .thenReturn(true);
        when(configParameterService.findByName("diagnostic", "diagnosticValidationUpdateLock"))
                .thenReturn(new ConfigParameter());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(transactionHandler).runInNewTransaction(any());
        List<String> inProgressSavedOn = new CopyOnWriteArrayList<>();
        when(validationStatusRepository.save(any())).thenAnswer(invocation -> {
            ValidationStatus status = invocation.getArgument(0);
            if (status.getState() == ValidationState.IN_PROGRESS) {
                inProgressSavedOn.add(Thread.currentThread().getName());
            }
            return status;
        });

        CompletableFuture<Void> run = diagnosticService.runValidationsAsync(null);

        assertThat(inProgressSavedOn).containsExactly(Thread.currentThread().getName());
        run.join();
    }

    @Test
    void runValidationsAsyncReleasesLockWhenInProgressSaveFails() {
        ConfigParameter lock = new ConfigParameter();
        when(configParameterService.tryLock(eq("diagnostic"), eq("diagnosticValidationUpdateLock"), any()))
                .thenReturn(true);
        when(configParameterService.findByName("diagnostic", "diagnosticValidationUpdateLock")).thenReturn(lock);
        doThrow(new IllegalStateException("database is down")).when(transactionHandler).runInNewTransaction(any());

        assertThatThrownBy(() -> diagnosticService.runValidationsAsync(null))
                .isInstanceOf(IllegalStateException.class);
        verify(configParameterService).update(lock);
    }
}

package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainRuntimePropertiesServiceTest {
    @Mock
    ConsulService consulService;
    @InjectMocks
    ChainRuntimePropertiesService chainRuntimePropertiesService;

    @BeforeEach
    void startTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void deletesCustomPropertiesWhenTransactionCommits() {
        chainRuntimePropertiesService.deleteCustomRuntimePropertiesAfterCommit(List.of("first", "second"));
        verifyNoInteractions(consulService);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(consulService).deleteChainRuntimeConfig("first");
        verify(consulService).deleteChainRuntimeConfig("second");
    }

    @Test
    void keepsCustomPropertiesWhenTransactionRollsBack() {
        chainRuntimePropertiesService.deleteCustomRuntimePropertiesAfterCommit(List.of("kept"));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(consulService);
    }
}

package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.events.ChainsDeletedEvent;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ChainsDeletedEventListenerTest.Config.class)
class ChainsDeletedEventListenerTest {
    @MockitoBean
    ConsulService consulService;
    @MockitoBean
    ActionsLogService actionsLogService;
    @MockitoBean
    ChainRepository chainRepository;
    @MockitoBean
    ChainFinderService chainFinderService;

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void deletesCustomPropertiesAfterCommit() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new ChainsDeletedEvent(List.of("first", "second")));
            verifyNoInteractions(consulService);
        });

        verify(consulService).deleteChainRuntimeConfig("first");
        verify(consulService).deleteChainRuntimeConfig("second");
    }

    @Test
    void keepsCustomPropertiesOnRollback() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new ChainsDeletedEvent(List.of("kept")));
            status.setRollbackOnly();
        });

        verifyNoInteractions(consulService);
    }

    @Test
    void consulFailureDoesNotFailCommittedDeletion() {
        doThrow(new IllegalStateException("Consul is unavailable")).when(consulService).deleteChainRuntimeConfig("orphan");

        assertDoesNotThrow(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> publisher.publishEvent(new ChainsDeletedEvent(List.of("orphan")))));
        verify(consulService).deleteChainRuntimeConfig("orphan");
    }

    @Configuration
    @EnableTransactionManagement
    @Import(ChainRuntimePropertiesService.class)
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}

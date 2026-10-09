/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.runtime.catalog.consul.CompiledLibrarySpringEventListener;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.scheduler.TasksScheduler;
import org.qubership.integration.platform.runtime.catalog.service.EventService;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.DetailedDesignService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.variables.RestoreVariablesListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS;

@SpringBootTest(webEnvironment = NONE, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "cip.standalone=true",
        "cip.datasource.configuration.enabled=false",
        "cip.deploy.classic.enabled=false",
        "cip.deploy.micro.enabled=false",
        "kubernetes.devmode=true",
        "kubernetes.localdev=true",
        "db.hikari.datasources.configs-datasource.driver-class-name="
                + "org.testcontainers.jdbc.ContainerDatabaseDriver",
        "db.hikari.datasources.configs-datasource.jdbcUrl=jdbc:tc:postgresql:17.2:///postgres",
        "db.hikari.datasources.configs-datasource.username=postgres",
        "db.hikari.datasources.configs-datasource.password=postgres",
        "db.hikari.datasources.configs-datasource.maximum-pool-size=5",
        "db.hikari.datasources.configs-datasource.minimum-idle=0"
})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@DirtiesContext(classMode = AFTER_CLASS)
@TestExecutionListeners(
        listeners = ChainImportUpdateRollbackIntegrationTest.ConsulStubLifecycle.class,
        mergeMode = MERGE_WITH_DEFAULTS
)
class ChainImportUpdateRollbackIntegrationTest {

    private static final String CHAIN_ID = "import-update-rollback-chain";
    private static final String ELEMENT_ID = "import-update-rollback-element";
    private static final String CONSUL_URL_PROPERTY = "CONSUL_URL";

    @Autowired
    private ChainImportService chainImportService;

    @Autowired
    private ChainRepository chainRepository;

    @Autowired
    private ElementRepository elementRepository;

    @Autowired
    @Qualifier("configsTransactionManager")
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ChainExternalEntityMapper chainExternalEntityMapper;

    @MockitoBean
    private CompiledLibrarySpringEventListener compiledLibrarySpringEventListener;

    @MockitoBean
    private TasksScheduler tasksScheduler;

    @MockitoBean
    private RestoreVariablesListener restoreVariablesListener;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private DetailedDesignService detailedDesignService;

    @MockitoBean
    private ImportArchiveOnStartup importArchiveOnStartup;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void seedChainWithElement() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            elementRepository.deleteAllByChainId(CHAIN_ID);
            chainRepository.findById(CHAIN_ID).ifPresent(chainRepository::delete);

            Chain chain = chainRepository.save(
                    Chain.builder().id(CHAIN_ID).name("Rollback test chain").build());
            elementRepository.save(ChainElement.builder()
                    .id(ELEMENT_ID)
                    .type("script")
                    .chain(chain)
                    .build());
        });
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    @DisplayName("failed v3 chain update import rolls back element deletion")
    void shouldPreserveElementsWhenUpdateImportFails() {
        doThrow(new IllegalStateException("simulated mapping failure"))
                .when(chainExternalEntityMapper).toInternalEntity(any());

        ChainExternalEntity external = ChainExternalEntity.builder()
                .id(CHAIN_ID)
                .name("Rollback test chain")
                .content(ChainExternalContentEntity.builder().build())
                .build();

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                chainImportService,
                "saveChainInTransaction",
                external,
                null,
                Set.of()));

        List<ChainElement> elements = elementRepository.findAllByChainId(CHAIN_ID);
        assertThat(elements).hasSize(1);
        assertThat(elements.get(0).getId()).isEqualTo(ELEMENT_ID);
    }

    static final class ConsulStubLifecycle extends AbstractTestExecutionListener {
        private HttpServer consulStub;
        private String previousConsulUrl;

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void beforeTestClass(TestContext testContext) {
            previousConsulUrl = System.getProperty(CONSUL_URL_PROPERTY);
            consulStub = startConsulStub();
            System.setProperty(
                    CONSUL_URL_PROPERTY,
                    "http://127.0.0.1:" + consulStub.getAddress().getPort());
        }

        @Override
        public void afterTestClass(TestContext testContext) {
            consulStub.stop(0);
            if (previousConsulUrl == null) {
                System.clearProperty(CONSUL_URL_PROPERTY);
            } else {
                System.setProperty(CONSUL_URL_PROPERTY, previousConsulUrl);
            }
        }

        private static HttpServer startConsulStub() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
                server.start();
                return server;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}

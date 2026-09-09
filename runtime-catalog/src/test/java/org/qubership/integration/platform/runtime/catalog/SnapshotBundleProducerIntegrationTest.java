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

package org.qubership.integration.platform.runtime.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.camelk.sources.IntegrationSourceBuilderFactory;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.adapters.SnapshotAdapter;
import org.qubership.integration.platform.runtime.catalog.consul.CompiledLibrarySpringEventListener;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DeploymentRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.scheduler.TasksScheduler;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.EventService;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.DetailedDesignService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ChainImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ContextExportImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportArchiveOnStartup;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportSessionService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.MCPSystemImportExportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.SystemExportImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.variables.CommonVariablesService;
import org.qubership.integration.platform.runtime.catalog.service.variables.RestoreVariablesListener;
import org.qubership.integration.platform.runtime.catalog.snapshotbundle.SnapshotBundleImportService;
import org.qubership.integration.platform.snapshotbundle.SnapshotBundleSource;
import org.qubership.integration.platform.snapshotbundle.SnapshotBundleWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction.SNAPSHOT;
import static org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus.CREATED;
import static org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus.UPDATED;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS;

@Tag("component")
@Tag("snapshotbundle")
@EnabledIfSystemProperty(named = "snapshot.bundle.generate", matches = "true")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@DirtiesContext(classMode = AFTER_CLASS)
@TestExecutionListeners(
        listeners = SnapshotBundleProducerIntegrationTest.ConsulStubLifecycle.class,
        mergeMode = MERGE_WITH_DEFAULTS
)
@Import(SnapshotBundleProducerIntegrationTest.SnapshotImportConfiguration.class)
@SpringBootTest(webEnvironment = NONE, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "qip.standalone=true",
        "qip.datasource.configuration.enabled=false",
        "qip.deploy.classic.enabled=false",
        "qip.deploy.micro.enabled=false",
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
class SnapshotBundleProducerIntegrationTest {

    private static final String MICRO_ENGINE_XML_NAMESPACE = "https://camel.apache.org/schema/xml-io";
    private static final String DOMAIN_NAME = "snapshot-tests";
    private static final String BUILD_NAME = "snapshot-bundle";
    private static final Instant BUILD_TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONSUL_URL_PROPERTY = "CONSUL_URL";
    private static final String OUTPUT_DIRECTORY_PROPERTY = "snapshot.bundle.output";
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "snapshotbundle");

    private final SnapshotBundleWriter snapshotBundleWriter = new SnapshotBundleWriter();

    @TempDir
    private Path temporaryDirectory;

    @Autowired
    private SnapshotBundleImportService snapshotBundleImportService;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private IntegrationSourceBuilderFactory integrationSourceBuilderFactory;

    @Autowired
    private IntegrationServiceCatalog integrationServiceCatalog;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    @Qualifier("configsTransactionManager")
    private PlatformTransactionManager transactionManager;

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

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void shouldGenerateSnapshotBundleWhenConfigurationsAreImported() throws IOException {
        Path sourceDirectory = new ClassPathResource("testConfigurations").getFile().toPath();
        Path importDirectory = temporaryDirectory.resolve("testConfigurations");
        copyDirectory(sourceDirectory, importDirectory);

        Set<String> chainIds = findChainIds(importDirectory);
        ImportRequest importRequest = createImportRequest(chainIds);

        ImportResult importResult = snapshotBundleImportService.importDirectoryAndAwaitCompletion(
                importDirectory.toFile(),
                importRequest,
                Set.of(),
                false
        );

        assertSuccessfulImport(importResult, chainIds);
        assertThat(deploymentRepository.count()).isZero();

        List<SnapshotBundleSource> sources = buildLatestSnapshotSources(chainIds);
        Path outputDirectory = resolveOutputDirectory();
        assertThat(snapshotBundleWriter.write(outputDirectory, sources)).isNotNull();
        assertThat(outputDirectory).isDirectory();
    }

    private static ImportRequest createImportRequest(Set<String> chainIds) {
        List<ChainCommitRequest> chainCommitRequests = chainIds.stream()
                .map(chainId -> ChainCommitRequest.builder()
                        .id(chainId)
                        .deployAction(SNAPSHOT)
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
        return ImportRequest.builder()
                .chainCommitRequests(chainCommitRequests)
                .build();
    }

    private static void assertSuccessfulImport(ImportResult importResult, Set<String> expectedChainIds) {
        assertThat(importResult).isNotNull();
        assertThat(importResult.getChains())
                .extracting(ImportChainResult::getId)
                .containsExactlyInAnyOrderElementsOf(expectedChainIds);
        assertThat(importResult.getChains()).allSatisfy(chainResult ->
                assertThat(chainResult.getStatus())
                        .as(
                                "import status for chain %s: %s",
                                chainResult.getId(),
                                chainResult.getErrorMessage()
                        )
                        .isIn(CREATED, UPDATED)
        );
        assertThat(importResult.hasErrors())
                .as("import result contains errors")
                .isFalse();
    }

    private List<SnapshotBundleSource> buildLatestSnapshotSources(Set<String> expectedChainIds) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setReadOnly(true);

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            List<Snapshot> snapshots = snapshotRepository.findAllLastCreated(expectedChainIds);
            assertThat(snapshots)
                    .extracting(snapshot -> snapshot.getChain().getId())
                    .containsExactlyInAnyOrderElementsOf(expectedChainIds);
            SourceBuilderContext sourceBuilderContext = SourceBuilderContext.builder()
                    .domainName(DOMAIN_NAME)
                    .buildName(BUILD_NAME)
                    .buildTimestamp(BUILD_TIMESTAMP)
                    .integrationServiceCatalog(integrationServiceCatalog)
                    .build();
            return snapshots.stream()
                    .map(snapshot -> buildSnapshotSource(snapshot, sourceBuilderContext))
                    .toList();
        }));
    }

    private static void materializeSnapshot(Snapshot snapshot) {
        assertThat(snapshot.getChain().getId()).isNotBlank();
        assertThat(snapshot.getChain().getName()).isNotBlank();
        snapshot.getElements().forEach(element -> {
            assertThat(element.getId()).isNotBlank();
            assertThat(element.getOriginalId())
                    .as("original ID for snapshot element %s", element.getId())
                    .isNotBlank();
        });
    }

    private SnapshotBundleSource buildSnapshotSource(Snapshot snapshot, SourceBuilderContext context) {
        materializeSnapshot(snapshot);
        try {
            SnapshotAdapter snapshotAdapter = new SnapshotAdapter(snapshot);
            String xml = integrationSourceBuilderFactory.getBuilder("xml")
                    .build(snapshotAdapter, context);
            assertMicroEngineSource(snapshot, xml);
            return new SnapshotBundleSource(snapshotAdapter, xml);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot generate micro-engine XML for chain '" + snapshot.getChain().getName()
                            + "' (" + snapshot.getChain().getId() + ").",
                    exception
            );
        }
    }

    private static void assertMicroEngineSource(Snapshot snapshot, String xml) throws Exception {
        assertThat(xml).isNotBlank().doesNotContain("Optional[");
        Document document = DocumentBuilderFactory.newDefaultNSInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        Element root = document.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("camel");
        assertThat(root.getNamespaceURI()).isEqualTo(MICRO_ENGINE_XML_NAMESPACE);
        assertThat(xmlElements(root, "route").map(route -> route.getAttribute("group")))
                .as("route groups for snapshot %s", snapshot.getId())
                .contains(snapshot.getId());

        Map<String, Element> beans = xmlElements(root, "bean")
                .collect(Collectors.toMap(bean -> bean.getAttribute("name"), bean -> bean));
        assertThat(beans).containsKey("DeploymentInfo-" + snapshot.getId());
        assertThat(beanProperties(beans.get("DeploymentInfo-" + snapshot.getId())))
                .containsEntry("id", DOMAIN_NAME + "-" + snapshot.getId())
                .containsEntry("name", BUILD_NAME)
                .containsEntry("timestamp", Long.toString(BUILD_TIMESTAMP.getEpochSecond()))
                .containsEntry("chain.id", snapshot.getChain().getId())
                .containsEntry("snapshot.id", snapshot.getId());
        snapshot.getElements().forEach(element -> {
            String beanName = "ElementInfo-" + element.getId();
            assertThat(beans).containsKey(beanName);
            assertThat(beanProperties(beans.get(beanName)))
                    .containsEntry("id", element.getOriginalId())
                    .containsEntry("snapshotElementId", element.getId())
                    .containsEntry("snapshotId", snapshot.getId());
        });
    }

    private static Map<String, String> beanProperties(Element bean) {
        return xmlElements(bean, "property")
                .collect(Collectors.toMap(property -> property.getAttribute("key"),
                        property -> property.getAttribute("value")));
    }

    private static Stream<Element> xmlElements(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(MICRO_ENGINE_XML_NAMESPACE, localName);
        return IntStream.range(0, nodes.getLength()).mapToObj(index -> (Element) nodes.item(index));
    }

    private static Set<String> findChainIds(Path importDirectory) throws IOException {
        Path chainsDirectory = importDirectory.resolve("chains");
        Set<String> chainIds;
        try (Stream<Path> entries = Files.list(chainsDirectory)) {
            chainIds = entries
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        assertThat(chainIds).isNotEmpty();
        chainIds.forEach(chainId -> assertThat(
                chainsDirectory.resolve(chainId).resolve(chainId + ".chain.qip.yaml")
        ).isRegularFile());
        return chainIds;
    }

    private static void copyDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
        try (Stream<Path> sourcePaths = Files.walk(sourceDirectory)) {
            for (Path sourcePath : sourcePaths.toList()) {
                Path targetPath = targetDirectory.resolve(sourceDirectory.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath);
                }
            }
        }
    }

    private static Path resolveOutputDirectory() {
        String configuredDirectory = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
        Path outputDirectory = configuredDirectory == null || configuredDirectory.isBlank()
                ? DEFAULT_OUTPUT_DIRECTORY
                : Path.of(configuredDirectory);
        return outputDirectory.toAbsolutePath().normalize();
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
                    "http://127.0.0.1:" + consulStub.getAddress().getPort()
            );
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
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SnapshotImportConfiguration {

        @Bean
        @Primary
        SnapshotBundleImportService snapshotBundleImportService(
                CommonVariablesService commonVariablesService,
                SystemExportImportService systemExportImportService,
                ContextExportImportService contextExportImportService,
                MCPSystemImportExportService mcpSystemImportExportService,
                ChainImportService chainImportService,
                ImportSessionService importSessionService,
                ActionsLogService actionsLogService,
                ImportInstructionsService importInstructionsService,
                GeneralInstructionsMapper generalInstructionsMapper
        ) {
            return new SnapshotBundleImportService(
                    commonVariablesService,
                    systemExportImportService,
                    contextExportImportService,
                    mcpSystemImportExportService,
                    chainImportService,
                    importSessionService,
                    actionsLogService,
                    importInstructionsService,
                    generalInstructionsMapper
            );
        }
    }
}

package org.qubership.integration.platform.engine.routes.tests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.jackson.JacksonConstants;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.StepDefinition;
import org.apache.camel.reifier.ProcessorReifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.Isolated;
import org.qubership.integration.platform.engine.camel.ChainsAggregationStrategy;
import org.qubership.integration.platform.engine.camel.components.directvm.ChainComponent;
import org.qubership.integration.platform.engine.camel.processors.EmptyProcessor;
import org.qubership.integration.platform.engine.camel.processors.HeaderModificationProcessor;
import org.qubership.integration.platform.engine.camel.processors.MapperProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.SessionWarningStatusProcessor;
import org.qubership.integration.platform.engine.camel.reifiers.CustomStepReifier;
import org.qubership.integration.platform.engine.routes.driver.SnapshotScenarioDriver;
import org.qubership.integration.platform.engine.routes.driver.SnapshotScenarioDriverRegistry;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotDeployment;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExchangeHeaders;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.SnapshotFixture;
import org.qubership.integration.platform.engine.routes.fixture.SnapshotFixtureRegistry;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

@Tag("component")
@Execution(SAME_THREAD)
@Isolated
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
abstract class SnapshotRouteExecutionTest {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotRouteExecutionTest.class);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMappers.getObjectMapper();
    private static final SnapshotFixtureRegistry FIXTURE_REGISTRY =
            SnapshotFixtureRegistry.withDefaultProviders();
    private static final SnapshotScenarioDriverRegistry DRIVER_REGISTRY =
            SnapshotScenarioDriverRegistry.withDefaultProviders();
    private static Locale originalFormatLocale;

    static {
        ProcessorReifier.registerReifier(StepDefinition.class, CustomStepReifier::new);
    }

    @BeforeAll
    static void useDeterministicFormatLocale() {
        originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }

    @AfterAll
    static void restoreFormatLocale() {
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
    }

    protected void executeScenario(
            SnapshotExecutionTarget target,
            SnapshotExecutionScenario scenario
    ) throws Exception {
        SnapshotExecutionTarget scenarioTarget = target.forScenario(scenario);
        String progressName = target.getId() + "/" + scenario.getId();
        long startedAt = System.nanoTime();
        LOG.info("Snapshot {} [{}/{}]: preparing {} deployment(s), {} fixture(s).",
                progressName, target.getScenarios().indexOf(scenario) + 1, target.getScenarios().size(),
                scenarioTarget.getDeployments().size(), scenarioTarget.getFixtures().size());
        SnapshotScenarioDriver driver = DRIVER_REGISTRY.createDriver(scenarioTarget, scenario);
        List<InvocationResult> invocationResults = new ArrayList<>();
        try (DeploymentEnvironment environment = new DeploymentEnvironment(scenarioTarget);
                SnapshotInvocationRunner invocationRunner = SnapshotInvocationRunner.fromSystemProperties()) {
            ProducerTemplate producerTemplate = environment.deploy(scenarioTarget, scenario, driver);
            int invocationIndex = 0;
            for (SnapshotScenarioInvocation invocation : scenario.getInvocations()) {
                LOG.info("Snapshot {}: invocation {}/{} ({}).", progressName, ++invocationIndex,
                        scenario.getInvocations().size(), invocation.getId());
                AtomicReference<String> currentExecution = new AtomicReference<>(
                        invocationStageName(scenario, invocation, "setup")
                );
                invocationRunner.execute(currentExecution::get, () -> {
                    environment.beforeInvocation(invocation);
                    for (int repetition = 1; repetition <= invocation.getRepeat(); repetition++) {
                        currentExecution.set(executionName(scenario, invocation, repetition));
                        Exchange exchange = driver.execute(producerTemplate, invocation);
                        assertScenarioResult(exchange, scenario, invocation, repetition);
                        invocationResults.add(new InvocationResult(exchange, invocation, repetition));
                    }
                    currentExecution.set(invocationStageName(scenario, invocation, "verification"));
                    environment.verifyInvocation(invocation);
                    return null;
                }, environment::abort);
            }
            LOG.info("Snapshot {}: verifying fixtures.", progressName);
            invocationRunner.execute(
                    () -> "Snapshot scenario '" + scenario.getId() + "' fixture verification",
                    () -> {
                        environment.verifyFixtures();
                        for (InvocationResult result : invocationResults) {
                            assertExpectedProperties(
                                    result.exchange(),
                                    result.invocation(),
                                    executionName(scenario, result.invocation(), result.repetition())
                                            + " after fixture completion"
                            );
                        }
                        return null;
                    },
                    environment::abort
            );
            LOG.info("Snapshot {}: closing environment.", progressName);
        } catch (Exception | AssertionError failure) {
            LOG.error("Snapshot {}: failed after {} ms.", progressName,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            throw failure;
        }
        LOG.info("Snapshot {}: passed in {} ms.", progressName,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    static void assertScenarioResult(
            Exchange exchange,
            SnapshotExecutionScenario scenario,
            SnapshotScenarioInvocation invocation,
            int repetition
    ) throws JsonProcessingException {
        String executionName = executionName(scenario, invocation, repetition);
        SnapshotFailureAssertions.assertMatches(invocation.getExpectedFailure(), exchange.getException(), executionName);
        if (invocation.hasExpectedBody()) {
            assertExpectedBody(exchange, invocation, executionName);
        }
        SnapshotValueAssertions.assertMapValues(
                invocation.getExpectedHeaders(),
                exchange.getMessage().getHeaders(),
                executionName + " returned an unexpected header"
        );
        invocation.getExpectedAbsentHeaders().forEach(headerName -> assertFalse(
                exchange.getMessage().getHeaders().containsKey(headerName),
                () -> executionName + " returned unexpected header '" + headerName + "'."
        ));
        Map<String, Object> exchangeHeaders = SnapshotExchangeHeaders.get(exchange);
        SnapshotValueAssertions.assertMapValues(
                invocation.getExpectedExchangeHeaders(),
                exchangeHeaders,
                executionName + " returned an unexpected exchange header"
        );
        invocation.getExpectedAbsentExchangeHeaders().forEach(headerName -> assertFalse(
                exchangeHeaders.containsKey(headerName),
                () -> executionName + " retained unexpected exchange header '" + headerName + "'."
        ));
        assertExpectedProperties(exchange, invocation, executionName);
    }

    private static void assertExpectedProperties(
            Exchange exchange,
            SnapshotScenarioInvocation invocation,
            String executionName
    ) {
        SnapshotValueAssertions.assertValues(
                invocation.getExpectedProperties(),
                exchange::getProperty,
                executionName + " returned an unexpected property"
        );
    }

    private static void assertExpectedBody(
            Exchange exchange,
            SnapshotScenarioInvocation invocation,
            String executionName
    ) throws JsonProcessingException {
        Object expectedBody = invocation.getExpectedBody();
        Object actualBody = exchange.getMessage().getBody();
        if (expectedBody instanceof Map<?, ?> || expectedBody instanceof List<?>) {
            JsonNode expectedJson = OBJECT_MAPPER.valueToTree(expectedBody);
            JsonNode actualJson = actualBodyAsJson(
                    expectedJson,
                    actualBody,
                    exchange.getMessage().getBody(String.class)
            );
            SnapshotValueAssertions.assertMatches(
                    expectedJson,
                    actualJson,
                    executionName + " returned an unexpected body."
            );
            return;
        }
        if (expectedBody instanceof String) {
            actualBody = exchange.getMessage().getBody(String.class);
        }
        assertEquals(
                expectedBody,
                actualBody,
                () -> executionName + " returned an unexpected body."
        );
    }

    static JsonNode actualBodyAsJson(
            JsonNode expectedJson,
            Object actualBody,
            String convertedBody
    ) throws JsonProcessingException {
        if (SnapshotValueAssertions.isMatcher(expectedJson)
                || actualBody instanceof Map<?, ?>
                || actualBody instanceof List<?>
                || actualBody instanceof JsonNode) {
            return OBJECT_MAPPER.valueToTree(actualBody);
        }
        return OBJECT_MAPPER.readTree(convertedBody);
    }

    private static String executionName(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioInvocation invocation,
            int repetition
    ) {
        return "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                + "' repetition " + repetition + " of " + invocation.getRepeat();
    }

    private static String invocationStageName(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioInvocation invocation,
            String stage
    ) {
        return "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                + "' " + stage;
    }

    protected abstract DefaultCamelContext createCamelContext();

    protected abstract void loadXmlRouteDefinitions(
            DefaultCamelContext camelContext,
            String routeSourceLocation
    ) throws Exception;

    private record InvocationResult(Exchange exchange, SnapshotScenarioInvocation invocation, int repetition) {
    }

    private final class DeploymentEnvironment implements AutoCloseable {
        private static final long CONTEXT_SHUTDOWN_TIMEOUT_SECONDS = 5;
        private final Map<String, DefaultCamelContext> contextsByDeploymentId = new LinkedHashMap<>();
        private final Map<String, List<RouteDefinition>> routesByDeploymentId = new LinkedHashMap<>();
        private final List<SnapshotFixture> fixtures = new ArrayList<>();
        private final SnapshotResourceStager resourceStager;
        private SnapshotScenarioDriver driver;
        private ProducerTemplate producerTemplate;
        private String subjectDeploymentId;
        private boolean beforeContextStopInvoked;

        private DeploymentEnvironment(SnapshotExecutionTarget target) throws IOException {
            resourceStager = new SnapshotResourceStager(target);
        }

        private ProducerTemplate deploy(
                SnapshotExecutionTarget target,
                SnapshotExecutionScenario scenario,
                SnapshotScenarioDriver driver
        ) throws Exception {
            this.driver = driver;
            subjectDeploymentId = target.getSubjectDeploymentId();
            resourceStager.stage(target.getResources());
            SnapshotFixtureStartup startup = SnapshotFixtureStartup.fromSystemProperties();
            List<SnapshotFixture> pendingFixtures = FIXTURE_REGISTRY.createFixtures(target, scenario);
            startup.start(pendingFixtures, target.getId() + "/" + scenario.getId());
            fixtures.addAll(pendingFixtures);

            for (SnapshotDeployment deployment : target.getDeployments()) {
                DefaultCamelContext camelContext = createCamelContext();
                boolean newContext = !contextsByDeploymentId.containsValue(camelContext);
                contextsByDeploymentId.put(deployment.getId(), camelContext);
                if (newContext) {
                    configureRuntime(camelContext);
                }
                for (SnapshotFixture fixture : fixtures) {
                    if (deployment.getId().equals(fixture.getDeploymentId())) {
                        fixture.beforeRouteLoad(camelContext);
                    }
                }
                int previousRouteCount = camelContext.getRouteDefinitions().size();
                loadXmlRouteDefinitions(camelContext, deployment.getRouteSourceLocation());
                List<RouteDefinition> routes = camelContext.getRouteDefinitions();
                routesByDeploymentId.put(
                        deployment.getId(),
                        List.copyOf(routes.subList(previousRouteCount, routes.size()))
                );
            }

            for (SnapshotFixture fixture : fixtures) {
                DefaultCamelContext camelContext = contextsByDeploymentId.get(fixture.getDeploymentId());
                if (camelContext == null) {
                    throw new IllegalStateException(
                            "Snapshot fixture references undeployed context '" + fixture.getDeploymentId() + "'."
                    );
                }
                fixture.configure(camelContext, routesByDeploymentId.get(fixture.getDeploymentId()));
            }

            DefaultCamelContext subjectContext = contextsByDeploymentId.get(target.getSubjectDeploymentId());
            if (subjectContext == null) {
                throw new IllegalStateException(
                        "Snapshot execution target '" + target.getId() + "' does not have a deployed subject context."
                );
            }
            driver.configure(subjectContext, routesByDeploymentId.get(target.getSubjectDeploymentId()));

            for (DefaultCamelContext camelContext : contextsByDeploymentId.values().stream().distinct().toList()) {
                camelContext.start();
            }

            producerTemplate = subjectContext.createProducerTemplate();
            return producerTemplate;
        }

        private void configureRuntime(DefaultCamelContext camelContext) {
            camelContext.getShutdownStrategy().setTimeout(CONTEXT_SHUTDOWN_TIMEOUT_SECONDS);
            camelContext.getShutdownStrategy().setTimeUnit(TimeUnit.SECONDS);
            camelContext.getShutdownStrategy().setShutdownNowOnTimeout(true);

            camelContext.getGlobalOptions().put(
                    JacksonConstants.ENABLE_TYPE_CONVERTER,
                    Boolean.TRUE.toString()
            );
            camelContext.getRegistry().bind(
                    "chainsAggregationStrategy",
                    AggregationStrategy.class,
                    new ChainsAggregationStrategy(OBJECT_MAPPER)
            );
            camelContext.getRegistry().bind(
                    "emptyProcessor",
                    Processor.class,
                    new EmptyProcessor()
            );
            SimpleLanguage simpleLanguage = (SimpleLanguage) camelContext.resolveLanguage("simple");
            camelContext.getRegistry().bind(
                    "headerModificationProcessor",
                    Processor.class,
                    new HeaderModificationProcessor(simpleLanguage)
            );
            MapperProcessor mapperProcessor = new MapperProcessor(OBJECT_MAPPER);
            mapperProcessor.setCacheEnabled(true);
            camelContext.getRegistry().bind(
                    "mapperProcessor",
                    Processor.class,
                    mapperProcessor
            );
            camelContext.getRegistry().bind(
                    "sessionWarningStatusProcessor",
                    Processor.class,
                    new SessionWarningStatusProcessor()
            );
            camelContext.addComponent("cip-chain", new ChainComponent());
        }

        private void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (SnapshotFixture fixture : fixtures) {
                fixture.beforeInvocation(invocation);
            }
        }

        private void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (SnapshotFixture fixture : fixtures) {
                fixture.verifyInvocation(invocation);
            }
        }

        private void verifyFixtures() throws Exception {
            for (SnapshotFixture fixture : fixtures) {
                fixture.releasePendingWork();
            }
            for (SnapshotFixture fixture : fixtures) {
                fixture.awaitCompletion();
            }
            fixtures.forEach(SnapshotFixture::verify);
        }

        private void abort() throws Exception {
            Exception cleanupFailure = beforeContextStop();
            cleanupFailure = stopProducerTemplate(cleanupFailure);
            cleanupFailure = stopContexts(cleanupFailure, true);
            cleanupFailure = closeDriver(cleanupFailure);
            throwIfCleanupFailed(cleanupFailure);
        }

        @Override
        public void close() throws Exception {
            Exception cleanupFailure = beforeContextStop();
            cleanupFailure = stopProducerTemplate(cleanupFailure);
            cleanupFailure = stopContexts(cleanupFailure, false);
            cleanupFailure = closeDriver(cleanupFailure);

            for (int index = fixtures.size() - 1; index >= 0; index--) {
                try {
                    fixtures.get(index).close();
                } catch (Exception exception) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
                }
            }

            try {
                resourceStager.close();
            } catch (Exception exception) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
            }
            throwIfCleanupFailed(cleanupFailure);
        }

        private Exception beforeContextStop() {
            if (beforeContextStopInvoked) {
                return null;
            }
            beforeContextStopInvoked = true;

            Exception cleanupFailure = null;
            for (int index = fixtures.size() - 1; index >= 0; index--) {
                try {
                    fixtures.get(index).beforeContextStop();
                } catch (Exception exception) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
                }
            }
            if (driver != null) {
                try {
                    driver.beforeContextStop();
                } catch (Exception exception) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
                }
            }
            return cleanupFailure;
        }

        private Exception stopProducerTemplate(Exception cleanupFailure) {
            if (producerTemplate == null) {
                return cleanupFailure;
            }
            try {
                producerTemplate.stop();
                producerTemplate = null;
            } catch (Exception exception) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
            }
            return cleanupFailure;
        }

        private Exception stopContexts(Exception cleanupFailure, boolean subjectFirst) {
            List<String> deploymentIds = new ArrayList<>(contextsByDeploymentId.keySet());
            if (subjectFirst && subjectDeploymentId != null) {
                cleanupFailure = stopContext(subjectDeploymentId, cleanupFailure);
            }
            for (int index = deploymentIds.size() - 1; index >= 0; index--) {
                String deploymentId = deploymentIds.get(index);
                if (subjectFirst && deploymentId.equals(subjectDeploymentId)) {
                    continue;
                }
                cleanupFailure = stopContext(deploymentId, cleanupFailure);
            }
            return cleanupFailure;
        }

        private Exception stopContext(String deploymentId, Exception cleanupFailure) {
            DefaultCamelContext camelContext = contextsByDeploymentId.get(deploymentId);
            if (camelContext == null) {
                return cleanupFailure;
            }
            try {
                camelContext.stop();
                contextsByDeploymentId.values().removeIf(context -> context == camelContext);
            } catch (Exception exception) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
            }
            return cleanupFailure;
        }

        private Exception closeDriver(Exception cleanupFailure) {
            if (driver == null) {
                return cleanupFailure;
            }
            try {
                driver.close();
                driver = null;
            } catch (Exception exception) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
            }
            return cleanupFailure;
        }

        private static Exception appendCleanupFailure(
                Exception cleanupFailure,
                Exception additionalFailure
        ) {
            if (cleanupFailure == null) {
                return additionalFailure;
            }
            cleanupFailure.addSuppressed(additionalFailure);
            return cleanupFailure;
        }

        private static void throwIfCleanupFailed(Exception cleanupFailure) throws Exception {
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }
}

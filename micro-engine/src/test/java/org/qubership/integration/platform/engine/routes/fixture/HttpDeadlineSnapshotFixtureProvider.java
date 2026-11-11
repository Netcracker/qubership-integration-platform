package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.configuration.ExchangePropertyAutoConfiguration;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.AbstractChainLogger;
import org.qubership.integration.platform.engine.service.debugger.masking.MaskingService;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.JsonSessionStepCoordinator;
import org.qubership.integration.platform.engine.service.debugger.sessions.OpenSearchWriter;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.util.ExchangeUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpDeadlineSnapshotFixtureProvider implements SnapshotFixtureProvider {
    @Override
    public String getId() {
        return "http-session-deadline";
    }

    @Override
    public boolean requiresNodeId() {
        return false;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.size() != 1) {
            throw new IllegalArgumentException("HTTP session deadline requires one fixture binding.");
        }
        SnapshotFixtureBinding binding = bindings.getFirst();
        binding.interactionsByInvocationId().values().forEach(interaction -> {
            if (interaction.getExpectedRequest() == null || interaction.getExpectedRequest().getCount() != 1
                    || !controls(interaction).keySet().equals(Set.of("expectTimeout"))
                    || !(controls(interaction).get("expectTimeout") instanceof Boolean)) {
                throw new IllegalArgumentException("HTTP session deadline requires one request and a boolean expectTimeout.");
            }
        });
        return new Fixture(deploymentId, binding);
    }

    private static Map<String, Object> controls(SnapshotFixtureInteraction interaction) {
        return interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
    }

    private static final class Fixture implements SnapshotFixture {
        private static final Pattern TIMEOUT_MESSAGE = Pattern.compile(
                "Chain execution timed out after ([0-9]+) ms\\. Desired limit is 5000 ms\\.");
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final HttpDeadlineSnapshotProbe probe = new HttpDeadlineSnapshotProbe();
        private final Set<String> sessionIds = new HashSet<>();
        private int verifiedInvocations;

        private Fixture(String deploymentId, SnapshotFixtureBinding binding) {
            this.deploymentId = deploymentId;
            this.binding = binding;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void beforeRouteLoad(CamelContext context) {
            context.getRegistry().bind(HttpDeadlineSnapshotProbe.BEAN_NAME, probe);
            context.setDebugger(newDebugger());
            context.setDebugging(true);
        }

        @Override
        public void configure(CamelContext context) {
            assertInstanceOf(CamelDebugger.class, context.getDebugger(), "The deadline requires the production debugger.");
            assertTrue(context.isDebugging(), "The production debugger must observe processor completion.");
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) {
            probe.reset();
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            boolean timedOut = Boolean.TRUE.equals(controls(binding.interaction(invocation.getId())).get("expectTimeout"));
            Exchange exchange = probe.exchange();
            assertNotNull(exchange, "The HTTP route must reach its first generated processor.");
            assertEquals(1, probe.firstCalls(), "The first processor must finish once.");
            assertTrue(probe.processorFinishedAt() > 0, "The first processor must return before the deadline is checked.");
            assertEquals(timedOut ? 0 : 1, probe.followingCalls(), "The following processor must run only before the deadline.");
            assertEquals(timedOut, exchange.getProperty(Properties.CHAIN_TIMED_OUT, false, Boolean.class),
                    "The production debugger must set the timeout state.");
            assertTrue(sessionIds.add(ExchangeUtil.getSessionId(exchange)), "Each HTTP request must have a fresh session.");
            long started = exchange.getProperty(Properties.START_TIME_MS, Long.class);
            Long timeout = assertInstanceOf(Long.class, exchange.getProperty(Properties.CHAIN_TIME_OUT_AFTER));
            assertEquals(5000L, timeout.longValue(), "The generated HTTP trigger must retain its configured session timeout.");
            if (timedOut) {
                assertTrue(probe.processorFinishedAt() > started + timeout,
                        "The slow processor must finish after the generated deadline.");
                Throwable failure = exchange.getException();
                if (failure == null) {
                    failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                }
                ChainExecutionTimeoutException timeoutFailure = assertInstanceOf(ChainExecutionTimeoutException.class, failure);
                Matcher message = TIMEOUT_MESSAGE.matcher(timeoutFailure.getMessage());
                assertTrue(message.matches(), "The timeout failure must report its elapsed time and configured deadline.");
                assertTrue(Long.parseLong(message.group(1)) > timeout, "The timeout must follow the configured deadline.");
            } else {
                assertTrue(probe.processorFinishedAt() < started + timeout, "The fresh request must finish before its deadline.");
            }
            verifiedInvocations++;
        }

        @Override
        public void verify() {
            assertEquals(binding.interactionsByInvocationId().size(), verifiedInvocations,
                    "Every HTTP deadline invocation must verify its processing effects.");
        }

        @Override
        public void releasePendingWork() {
            probe.release();
        }

        @Override
        public void beforeContextStop() {
            probe.release();
        }

        @Override
        public void close() {
            probe.release();
        }

        @SuppressWarnings("unchecked")
        private static CamelDebugger newDebugger() {
            CDI<Object> container = CDI.current();
            EngineInfo engineInfo = container.select(EngineInfo.class).get();
            VariablesService variables = container.select(VariablesService.class).get();
            CamelExchangeContextPropagation propagation = container.select(CamelExchangeContextPropagation.class).get();
            ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
            ChainRuntimePropertiesService properties = new ChainRuntimePropertiesService();
            PayloadExtractor payload = new PayloadExtractor(new MaskingService(objectMapper), objectMapper, properties, propagation);
            SessionsService sessions = new SessionsService(payload, mock(OpenSearchWriter.class), engineInfo, properties);
            JsonSessionStepCoordinator coordinator = new JsonSessionStepCoordinator(sessions, payload, engineInfo, properties);
            Instance<SessionsKafkaReportingService> reporting = mock(Instance.class);
            when(reporting.stream()).thenAnswer(ignored -> Stream.empty());
            return new CamelDebugger(engineInfo, mock(TracingService.class), mock(CheckpointSessionService.class),
                    mock(MetricsService.class), mock(AbstractChainLogger.class), reporting, sessions, payload, variables,
                    properties, propagation, new ExchangePropertyAutoConfiguration().defaultExchangePropertyService(), coordinator);
        }
    }
}

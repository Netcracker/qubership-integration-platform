package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.component.jackson.JacksonConstants;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.camel.processors.LogRecordProcessor;
import org.qubership.integration.platform.engine.model.constants.BusinessIds;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureLogExpectation;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

class LogCaptureSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "log-capture";
    private static final String LOG_RECORD_PROCESSOR_BEAN_NAME = "logRecordProcessor";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean requiresNodeId() {
        return false;
    }

    @Override
    public boolean supportsExpectedLogs() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        SnapshotFixtureBinding binding = SnapshotFixtureValidation.requireSingleBinding(bindings, "Log capture");
        SnapshotFixtureDefinition definition = binding.definition();
        SnapshotFixtureInteraction interaction = binding.interaction();
        SnapshotFixtureValidation.requireNoNodeId(definition, "Log capture");
        SnapshotFixtureValidation.requireNoRequestOrResponse(definition, interaction, "Log capture");
        if (!interaction.hasExpectedLogs()) {
            throw new IllegalArgumentException(
                    "Log capture fixture '" + definition.getId() + "' must define expected logs."
            );
        }
        return new LogCaptureSnapshotFixture(
                deploymentId,
                definition.getId(),
                interaction.getExpectedLogs()
        );
    }

    private static final class LogCaptureSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final String fixtureId;
        private final List<SnapshotFixtureLogExpectation> expectedLogs;
        private final RecordingChainLogger recordingChainLogger = new RecordingChainLogger();

        private LogCaptureSnapshotFixture(
                String deploymentId,
                String fixtureId,
                List<SnapshotFixtureLogExpectation> expectedLogs
        ) {
            this.deploymentId = deploymentId;
            this.fixtureId = fixtureId;
            this.expectedLogs = List.copyOf(expectedLogs);
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() {
            recordingChainLogger.clear();
            MDC.remove(BusinessIds.BUSINESS_IDS);
        }

        @Override
        public void configure(CamelContext camelContext) {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) {
            camelContext.getGlobalOptions().put(JacksonConstants.ENABLE_TYPE_CONVERTER, Boolean.TRUE.toString());
            SimpleLanguage simpleLanguage = (SimpleLanguage) camelContext.resolveLanguage("simple");
            SnapshotFixtureRouteScope.bind(camelContext, routes, deploymentId + ':' + fixtureId, Map.of(
                    LOG_RECORD_PROCESSOR_BEAN_NAME, new LogRecordProcessor(recordingChainLogger, simpleLanguage)
            ));
        }

        @Override
        public void verify() {
            List<CapturedLog> actualLogs = recordingChainLogger.snapshot();
            for (SnapshotFixtureLogExpectation expectation : expectedLogs) {
                long matchingLogCount = actualLogs.stream()
                        .filter(actualLog -> matches(expectation, actualLog))
                        .count();
                assertEquals(
                        expectation.getCount(),
                        matchingLogCount,
                        () -> "Log capture fixture '" + fixtureId + "' recorded an unexpected number of "
                                + expectation.getLevel() + " messages matching '" + expectation.getMessage() + "'."
                );
            }

            int expectedLogCount = expectedLogs.stream()
                    .mapToInt(SnapshotFixtureLogExpectation::getCount)
                    .sum();
            assertEquals(
                    expectedLogCount,
                    actualLogs.size(),
                    () -> "Log capture fixture '" + fixtureId + "' recorded unexpected log messages."
            );
            assertNull(
                    MDC.get(BusinessIds.BUSINESS_IDS),
                    () -> "Log capture fixture '" + fixtureId + "' left business identifiers in MDC."
            );
        }

        @Override
        public void close() {
            recordingChainLogger.clear();
            MDC.remove(BusinessIds.BUSINESS_IDS);
        }

        private static boolean matches(
                SnapshotFixtureLogExpectation expectation,
                CapturedLog actualLog
        ) {
            return expectation.getLevel().equals(actualLog.level())
                    && expectation.getMessage().equals(actualLog.message())
                    && expectation.getMdc().entrySet().stream().allMatch(entry ->
                    entry.getValue().equals(actualLog.mdc().get(entry.getKey())));
        }
    }

    private static final class RecordingChainLogger extends ChainLogger {
        private final List<CapturedLog> logs = new CopyOnWriteArrayList<>();

        @Override
        public void info(String format, Object... arguments) {
            record("INFO", format);
        }

        @Override
        public void warn(String format, Object... arguments) {
            record("WARN", format);
        }

        @Override
        public void error(String format, Object... arguments) {
            record("ERROR", format);
        }

        private void record(String level, String message) {
            logs.add(new CapturedLog(
                    level,
                    message,
                    immutableMapOrEmpty(MDC.getCopyOfContextMap())
            ));
        }

        private List<CapturedLog> snapshot() {
            return List.copyOf(logs);
        }

        private void clear() {
            logs.clear();
        }
    }

    private record CapturedLog(
            String level,
            String message,
            Map<String, String> mdc
    ) {
    }
}

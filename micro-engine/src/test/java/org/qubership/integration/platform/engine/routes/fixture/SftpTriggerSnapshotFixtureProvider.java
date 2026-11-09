package org.qubership.integration.platform.engine.routes.fixture;

import com.jcraft.jsch.SftpException;
import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.file.remote.SftpConsumer;
import org.apache.camel.component.file.remote.SftpEndpoint;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.pollconsumer.quartz.QuartzScheduledPollConsumerScheduler;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.EventNotifierSupport;
import org.quartz.CronExpression;
import org.quartz.TriggerKey;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;

import java.net.ConnectException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    @Override
    public String getId() {
        return "sftp-trigger";
    }

    @Override
    public boolean supportsExpectedExchanges() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.size() != 1) {
            throw new IllegalArgumentException("SFTP trigger fixture requires one generated consumer per deployment.");
        }
        SnapshotFixtureBinding binding = bindings.getFirst();
        for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
            if (interaction.getExpectedRequest() == null) {
                throw new IllegalArgumentException("SFTP trigger fixture requires expectedRequest.count.");
            }
        }
        return new Fixture(deploymentId, binding);
    }

    private static final class Fixture implements SnapshotFixture {
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final SftpTriggerSnapshotServer server = new SftpTriggerSnapshotServer();
        private final Map<String, Object> permissionEvidence = new LinkedHashMap<>();
        private CamelContext context;
        private RouteDefinition route;
        private SftpEndpoint endpoint;
        private SftpTriggerSnapshotRuntime runtime;
        private SftpTriggerSnapshotPollObserver polls;
        private SnapshotScenarioInvocation lastInvocation;
        private QuartzScheduledPollConsumerScheduler scheduler;
        private SftpTriggerSnapshotScheduler schedulerControl;
        private SftpConsumer nativeConsumer;
        private Map<String, String> exported;
        private SftpTriggerSnapshotLogObserver connectionLogs;
        private EventNotifierSupport failureObserver;
        private long publicationPolls;
        private boolean liveContextCloseExpected;
        private boolean nativeConsumerLiveBeforeContextStop;
        private int nativeConnectionsBeforeContextStop;

        private Fixture(String deploymentId, SnapshotFixtureBinding binding) {
            this.deploymentId = deploymentId;
            this.binding = binding;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            server.start();
            if (Boolean.TRUE.equals(initialControls().get("missingDirectory"))) {
                server.directoryPresent(false);
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            context = camelContext;
            schedulerControl = SftpTriggerSnapshotScheduler.install(context);
            String node = binding.definition().getNodeId();
            route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, node),
                    "SFTP trigger fixture requires one generated trigger step").route();
            String original = route.getInput().getUri();
            assertTrue(original.startsWith("sftp:"), "SFTP trigger must retain its generated native consumer.");
            exported = query(original);
            String authority = original.substring("sftp:".length());
            if (authority.startsWith("//")) {
                authority = authority.substring(2);
            }
            int path = authority.indexOf('/');
            assertTrue(path >= 0, "SFTP trigger export must contain its polling directory.");
            String uri = "sftp://" + server.host() + ':' + server.port() + authority.substring(path);
            route.getInput().setUri(uri);
            endpoint = context.getEndpoint(uri, SftpEndpoint.class);
            verifyGeneratedOptions();
            polls = new SftpTriggerSnapshotPollObserver(endpoint.getPollStrategy());
            connectionLogs = new SftpTriggerSnapshotLogObserver(server.port());
            endpoint.setPollStrategy(polls);
            runtime = new SftpTriggerSnapshotRuntime(server, context, endpoint, polls, route.getId(), initialControls());
            failureObserver = new EventNotifierSupport() {
                @Override
                public boolean isEnabled(CamelEvent event) {
                    return event instanceof CamelEvent.ExchangeFailedEvent;
                }

                @Override
                public void notify(CamelEvent event) {
                    runtime.failed(((CamelEvent.ExchangeFailedEvent) event).getExchange());
                }
            };
            context.getManagementStrategy().addEventNotifier(failureObserver);
            context.getRegistry().bind(SftpTriggerSnapshotRuntime.registryName(node), runtime);
            context.getRegistry().bind("snapshot-sftp-fixture:" + binding.definition().getId(), this);
            SnapshotFixtureRouteScope.bind(context, List.of(route), deploymentId + ':' + node,
                    Map.of("chainStartProcessor", new ChainStartProcessor(), "interruptExchangeProcessor", new InterruptExchangeProcessor(),
                            "chainFinishProcessor", (Processor) runtime::completed));
            AdviceWith.adviceWith(context, route, false, advice -> advice.weaveById(node).before().process(runtime::received));
            if (initialControls().get("preloadFiles") instanceof List<?> files) {
                runtime.publishFiles(files);
            }
        }

        private void verifyGeneratedOptions() {
            assertEquals(exported.get("username"), endpoint.getConfiguration().getUsername(), "Generated SFTP username");
            assertEquals(exported.get("password"), endpoint.getConfiguration().getPassword(), "Generated SFTP password");
            assertEquals(Boolean.parseBoolean(exported.get("streamDownload")), endpoint.getConfiguration().isStreamDownload(),
                    "Generated SFTP streaming setting");
            assertEquals(Boolean.parseBoolean(exported.get("autoCreate")), endpoint.isAutoCreate(), "Generated SFTP directory creation");
            assertEquals(Boolean.parseBoolean(exported.get("idempotent")), endpoint.getIdempotent(), "Generated SFTP native idempotency");
            assertEquals(exported.get("antInclude"), endpoint.getAntInclude(), "Generated SFTP file mask");
            assertFalse(endpoint.isRecursive(), "SFTP trigger must retain native nonrecursive traversal.");
            assertEquals("none", endpoint.getReadLock(), "SFTP trigger must retain native read-lock defaults.");
            assertEquals("quartz", String.valueOf(endpoint.getScheduler()), "Generated SFTP Quartz scheduler");
            assertTrue(exported.get("scheduler.triggerId").endsWith('-' + binding.definition().getNodeId()),
                    "SFTP Quartz trigger ID must retain the generated deployment and node identity.");
            assertEquals(route.getId(), exported.get("scheduler.triggerId"),
                    "SFTP Quartz trigger ID must retain the complete generated deployment and node identity.");
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            lastInvocation = invocation;
            Map<String, Object> controls = controls(binding.interaction(invocation.getId()));
            runtime.beforeInvocation(controls);
            publicationPolls = runtime.invocationPolls();
            connectionLogs.clear();
            updateNativeConsumer();
            if (Boolean.TRUE.equals(controls.get("verifyCreatedDirectory"))) {
                SftpTriggerSnapshotRuntime.await(this::existsDirectory, "SFTP native directory creation");
                assertTrue(endpoint.isAutoCreate(), "Directory creation must be enabled in the export.");
            }
            String action = String.valueOf(controls.getOrDefault("consumerAction", ""));
            switch (action) {
                case "" -> {
                    // Preserve the consumer lifecycle when no action is requested.
                }
                case "stop" -> stop();
                case "start" -> startRoute();
                case "restart-route" -> {
                    IdempotentRepository original = endpoint.getIdempotentRepository();
                    stop();
                    startRoute();
                    assertSame(original, endpoint.getIdempotentRepository(), "Route stop/start must retain the native SFTP repository.");
                }
                case "stop-until-published" -> {
                    stop();
                    runtime.afterPublish(() -> {
                        runtime.assertNothingDelivered();
                        assertTrue(nativeConsumer.isStopped(), "SFTP consumer must remain stopped while backlog is uploaded.");
                        startRoute();
                    });
                }
                case "stop-during-processing" -> {
                    runtime.holdFirst();
                    runtime.afterPublish(this::gracefulStop);
                }
                case "redeploy" -> reload();
                case "restart-server" -> {
                    polls.pause();
                    runtime.afterPublish(() -> {
                        long connections = server.acceptedConnections();
                        server.restart();
                        polls.resume();
                        SftpTriggerSnapshotRuntime.await(() -> server.acceptedConnections() > connections, "new SFTP connection after restart");
                    });
                }
                case "deny-access-until-published", "deny-password-until-published", "refuse-until-published" -> accessFault(action);
                case "missing-directory-until-published" -> {
                    assertFalse(endpoint.isAutoCreate(), "Missing-directory recovery requires autoCreate=false.");
                    long before = polls.completed();
                    SftpTriggerSnapshotRuntime.await(() -> polls.completed() >= before + 2, "native polls of a missing SFTP directory");
                    assertFalse(server.exists(""), "The SFTP consumer must not create the missing directory.");
                    runtime.assertNothingDelivered();
                    server.directoryPresent(true);
                }
                case "interrupted-download" -> runtime.interruptNextDownload();
                default -> throw new IllegalArgumentException("Unsupported SFTP trigger consumerAction '" + action + "'.");
            }
            if (controls.get("publishFiles") instanceof List<?> files) {
                runtime.publishFiles(files);
                publicationPolls = polls.completed();
            }
        }

        private boolean existsDirectory() {
            try {
                return server.exists("");
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot inspect the SFTP polling directory.", exception);
            }
        }

        private void updateNativeConsumer() {
            nativeConsumer = assertInstanceOf(SftpConsumer.class, context.getRoute(route.getId()).getConsumer());
            scheduler = assertInstanceOf(QuartzScheduledPollConsumerScheduler.class, nativeConsumer.getScheduler());
            assertEquals(exported.get("scheduler.cron"), scheduler.getCron(), "Generated SFTP Quartz cron");
            assertEquals(exported.get("scheduler.triggerId"), scheduler.getTriggerId(), "Generated SFTP Quartz trigger ID");
            assertEquals(exported.get("scheduler.triggerGroup"), scheduler.getTriggerGroup(), "Generated SFTP Quartz trigger group");
            assertFalse(scheduler.isDeleteJob(), "SFTP fixture must retain the exported deleteJob=false setting.");
        }

        private void accessFault(String action) throws Exception {
            polls.pause();
            runtime.afterPublish(() -> {
                long authenticationFailures = server.authenticationFailures();
                long closed = server.closedConnections();
                int active = server.activeConnections();
                permissionEvidence.clear();
                if ("deny-access-until-published".equals(action)) {
                    permissionEvidence.put("keysBeforeDeniedGet", runtime.retainedKeys());
                    permissionEvidence.put("files", runtime.publishedNames());
                }
                switch (action) {
                    case "deny-access-until-published" -> server.permissions(false);
                    case "deny-password-until-published" -> server.password("invalid-snapshot-password");
                    case "refuse-until-published" -> server.listenerAvailable(false);
                    default -> throw new IllegalArgumentException("Unsupported SFTP access fault.");
                }
                if (!"deny-access-until-published".equals(action)) {
                    server.disconnect();
                }
                long before = polls.completed();
                polls.resume();
                SftpTriggerSnapshotRuntime.await(() -> polls.completed() >= before + 2, "native SFTP polls during " + action);
                runtime.assertNothingDelivered();
                if ("deny-access-until-published".equals(action)) {
                    permissionEvidence.put("keysAfterDeniedGet", runtime.retainedKeys());
                    permissionEvidence.put("nativeFailedKeys", polls.failureExchanges().stream()
                            .map(exchange -> endpoint.getIdempotentKey().evaluate(exchange, String.class)).toList());
                    runtime.faultEvidence(permissionEvidence);
                    for (String fileName : runtime.publishedNames()) {
                        assertEquals(3, server.probeDownloadFailure(fileName), "SFTP must allow listing and deny reading the published file.");
                    }
                    boolean permissionFailure = polls.failures().stream().anyMatch(Fixture::permissionDenied);
                    assertEquals(!endpoint.getConfiguration().isIgnoreFileNotFoundOrPermissionError(), permissionFailure,
                            "SFTP retrieval error reporting must follow the exported ignore flag.");
                }
                if ("deny-password-until-published".equals(action)) {
                    assertTrue(server.authenticationFailures() > authenticationFailures, "SFTP server must reject the native consumer password.");
                } else if ("refuse-until-published".equals(action)) {
                    if (active > 0) {
                        assertTrue(server.closedConnections() > closed, "SFTP proxy must close the existing native connection.");
                    }
                    assertTrue(connectionLogs.failures().stream().anyMatch(Fixture::connectionRefused),
                            "SFTP native connection must fail with an actual refused connection: " + connectionLogs.failures());
                }
                polls.pause();
                try {
                    switch (action) {
                        case "deny-access-until-published" -> {
                            server.permissions(true);
                            for (String fileName : runtime.publishedNames()) {
                                assertEquals(0, server.probeDownloadFailure(fileName),
                                        "Restored SFTP access must permit the unchanged file before native recovery.");
                                var attributes = server.stat(fileName);
                                permissionEvidence.put("restoredFile:" + fileName, "size=" + attributes.getSize()
                                        + ",mtimeSeconds=" + attributes.getMTime() + ",nativeGetStatus=0");
                            }
                            permissionEvidence.put("keysAfterAccessRestored", runtime.retainedKeys());
                            runtime.faultEvidence(permissionEvidence);
                        }
                        case "deny-password-until-published" -> {
                            server.password("snapshot");
                            server.disconnect();
                        }
                        case "refuse-until-published" -> {
                            server.listenerAvailable(true);
                            server.disconnect();
                        }
                        default -> throw new IllegalArgumentException("Unsupported SFTP recovery action.");
                    }
                } finally {
                    polls.resume();
                }
            });
        }

        private static boolean permissionDenied(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof SftpException sftp && sftp.id == 3) {
                    return true;
                }
            }
            return false;
        }

        private static boolean connectionRefused(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof ConnectException) {
                    return true;
                }
            }
            return false;
        }

        private void gracefulStop() throws Exception {
            runtime.awaitHeld();
            SftpConsumer original = nativeConsumer;
            try (var executor = Executors.newSingleThreadExecutor()) {
                CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> {
                    try {
                        stop();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }, executor);
                try {
                    SftpTriggerSnapshotRuntime.await(() -> original.isSuspendingOrSuspended()
                                    || context.getRouteController().getRouteStatus(route.getId()).isStopping(),
                            "SFTP consumer graceful stop to begin");
                    assertFalse(stopping.isDone(), "SFTP graceful stop must wait for the active invocation.");
                } finally {
                    runtime.releaseHeld();
                }
                stopping.get(20, TimeUnit.SECONDS);
                assertTrue(original.isStopped(), "SFTP consumer must stop after the active file completes.");
            }
            startRoute();
        }

        private void reload() throws Exception {
            Object originalConsumer = nativeConsumer;
            SftpEndpoint originalEndpoint = endpoint;
            IdempotentRepository originalRepository = endpoint.getIdempotentRepository();
            long closed = server.closedConnections();
            stop();
            ModelCamelContext model = (ModelCamelContext) context;
            model.removeRouteDefinition(route);
            context.removeEndpoint(endpoint);
            SftpTriggerSnapshotRuntime.await(() -> server.activeConnections() == 0, "old SFTP consumer connection closure");
            assertTrue(server.closedConnections() >= closed, "Old SFTP consumer connection must close before redeployment.");
            TriggerKey key = new TriggerKey(scheduler.getTriggerId(), scheduler.getTriggerGroup());
            assertNotNull(scheduler.getQuartzScheduler().getTrigger(key), "deleteJob=false must retain the old Quartz trigger.");
            endpoint = context.getEndpoint(route.getInput().getUri(), SftpEndpoint.class);
            assertNotSame(originalEndpoint, endpoint, "SFTP redeployment must create a fresh native endpoint.");
            endpoint.setPollStrategy(polls);
            runtime.endpoint(endpoint);
            model.addRouteDefinition(route);
            updateNativeConsumer();
            assertNotSame(originalConsumer, nativeConsumer, "SFTP redeployment must create a fresh native consumer.");
            assertNotSame(originalRepository, endpoint.getIdempotentRepository(), "SFTP redeployment must create a fresh file-key repository.");
        }

        private void stop() throws Exception {
            polls.resume();
            polls.releaseFollowingPolls();
            context.getRouteController().stopRoute(route.getId());
            SftpTriggerSnapshotRuntime.await(() -> server.activeConnections() == 0, "SFTP connection closure after route stop");
        }

        private void startRoute() throws Exception {
            context.getRouteController().startRoute(route.getId());
            updateNativeConsumer();
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            Map<String, Object> options = controls(binding.interaction(invocation.getId()));
            if (!runtime.isStopped()) {
                SftpTriggerSnapshotRuntime.await(() -> polls.completed() >= Math.max(runtime.invocationPolls(), publicationPolls) + 2,
                        "SFTP native polling before final invocation observations");
            }
            runtime.verify(binding.interaction(invocation.getId()), invocation.getId());
            if (options.get("expectedDistinctFixture") instanceof String fixtureId) {
                Fixture peer = context.getRegistry().lookupByNameAndType("snapshot-sftp-fixture:" + fixtureId, Fixture.class);
                assertNotNull(peer, "SFTP schedule isolation requires its peer fixture.");
                peer.updateNativeConsumer();
                assertNotEquals(new TriggerKey(scheduler.getTriggerId(), scheduler.getTriggerGroup()),
                        new TriggerKey(peer.scheduler.getTriggerId(), peer.scheduler.getTriggerGroup()),
                        "Independent generated SFTP deployments must use distinct native Quartz keys.");
            }
        }

        @Override
        public void verifyStartupFailure() {
            assertFalse(CronExpression.isValidExpression(exported.get("scheduler.cron")),
                    "SFTP startup failure must use the invalid-cron export.");
            runtime.assertNothingDelivered();
        }

        @Override
        public void releasePendingWork() {
            runtime.releaseHeld();
            polls.resume();
            polls.releaseFollowingPolls();
        }

        @Override
        public void awaitCompletion() throws Exception {
            if (context.getRoute(route.getId()) != null) {
                if (lastInvocation != null && Boolean.TRUE.equals(controls(binding.interaction(lastInvocation.getId())).get("verifyContextClose"))) {
                    polls.pause();
                    liveContextCloseExpected = true;
                } else {
                    stop();
                }
            }
        }

        @Override
        public void verify() {
            if (lastInvocation != null && !liveContextCloseExpected) {
                try {
                    runtime.verifyFinal(binding.interaction(lastInvocation.getId()), lastInvocation.getId());
                } catch (Exception exception) {
                    throw new AssertionError("SFTP final verification failed.", exception);
                }
            }
        }

        @Override
        public String timeoutDiagnostics() {
            return runtime == null ? "SFTP fixture is not configured." : runtime.diagnostics()
                    + ", permissionEvidence=" + permissionEvidence + '\n' + server.logs();
        }

        @Override
        public void beforeContextStop() {
            if (liveContextCloseExpected) {
                nativeConsumerLiveBeforeContextStop = nativeConsumer.isStarted();
                nativeConnectionsBeforeContextStop = server.activeConnections();
            }
            if (runtime != null) {
                runtime.releaseHeld();
            }
            if (polls != null) {
                polls.resume();
                polls.releaseFollowingPolls();
            }
        }

        @Override
        public void close() throws Exception {
            try {
                if (liveContextCloseExpected) {
                    assertTrue(nativeConsumerLiveBeforeContextStop, "Context-close verification must begin with a live SFTP consumer.");
                    assertTrue(nativeConnectionsBeforeContextStop > 0, "Context-close verification must begin with a native SSH connection.");
                    assertTrue(context.isStopped(), "SFTP context must stop before fixture cleanup.");
                    assertTrue(nativeConsumer.isStopped(), "SFTP native consumer must stop when its context closes.");
                    assertEquals(0, server.activeConnections(), "SFTP native SSH connections must close with the context.");
                    runtime.verifyFinal(binding.interaction(lastInvocation.getId()), lastInvocation.getId());
                }
                server.verify();
            } finally {
                try {
                    if (failureObserver != null) {
                        context.getManagementStrategy().removeEventNotifier(failureObserver);
                    }
                    if (schedulerControl != null) {
                        schedulerControl.close();
                    }
                } finally {
                    if (connectionLogs != null) {
                        connectionLogs.close();
                    }
                    server.close();
                }
            }
        }

        private Map<String, Object> initialControls() {
            return controls(binding.interactionsByInvocationId().values().iterator().next());
        }

        private static Map<String, Object> controls(SnapshotFixtureInteraction interaction) {
            return interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        }

        private static Map<String, String> query(String uri) {
            Map<String, String> result = new LinkedHashMap<>();
            int separator = uri.indexOf('?');
            if (separator >= 0) {
                for (String entry : uri.substring(separator + 1).split("&")) {
                    String[] pair = entry.split("=", 2);
                    result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
            }
            return result;
        }
    }
}

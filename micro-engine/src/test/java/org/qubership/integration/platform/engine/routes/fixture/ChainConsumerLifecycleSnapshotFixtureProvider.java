package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.EventNotifierSupport;
import org.qubership.integration.platform.engine.camel.components.directvm.ChainBlockingProducer;
import org.qubership.integration.platform.engine.camel.components.directvm.ChainEndpoint;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainConsumerLifecycleSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private final Map<String, Map<String, Fixture>> lifecycleGroups = new ConcurrentHashMap<>();

    @Override
    public String getId() {
        return "chain-consumer-lifecycle";
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.size() != 1) {
            throw new IllegalArgumentException("Chain consumer lifecycle requires one trigger binding.");
        }
        SnapshotFixtureBinding binding = bindings.getFirst();
        if (binding.definition().getSourceElementId() == null) {
            throw new IllegalArgumentException("Chain consumer lifecycle requires a sourceElementId.");
        }
        binding.interactionsByInvocationId().values().forEach(interaction -> {
            if (interaction.getExpectedRequest() == null || interaction.getExpectedRequest().getCount() < 0) {
                throw new IllegalArgumentException("Chain consumer lifecycle requires a nonnegative expectedRequest.count.");
            }
            Map<String, Object> controls = controls(interaction);
            if (!Set.of("consumerAction", "consumerTag", "expectOtherConsumer", "concurrentEntries",
                    "applicationClassLoaderMarker", "lifecycleGroup", "peerDeployment").containsAll(controls.keySet())
                    || controls.containsKey("consumerAction") && !Set.of("stop", "start", "restart", "start-when-caller-waits",
                            "stop-while-processing", "replace-while-processing")
                    .contains(controls.get("consumerAction"))) {
                throw new IllegalArgumentException("Unsupported chain consumer lifecycle controls.");
            }
            for (String name : List.of("consumerTag", "lifecycleGroup", "peerDeployment")) {
                if (controls.containsKey(name) && (!(controls.get(name) instanceof String value) || value.isBlank())) {
                    throw new IllegalArgumentException("Chain " + name + " must be a nonblank string.");
                }
            }
            for (String name : List.of("expectOtherConsumer", "applicationClassLoaderMarker")) {
                if (controls.containsKey(name) && !(controls.get(name) instanceof Boolean)) {
                    throw new IllegalArgumentException("Chain " + name + " must be a boolean.");
                }
            }
            if (controls.containsKey("concurrentEntries") && (!(controls.get("concurrentEntries") instanceof Number count)
                    || count.intValue() < 2 || count.doubleValue() != count.intValue())) {
                throw new IllegalArgumentException("Chain concurrentEntries must be an integer >= 2.");
            }
            if ("replace-while-processing".equals(controls.get("consumerAction"))
                    && (!controls.containsKey("peerDeployment") || !controls.containsKey("lifecycleGroup"))) {
                throw new IllegalArgumentException("Chain replacement requires a lifecycleGroup and peerDeployment.");
            }
        });
        List<String> groups = binding.interactionsByInvocationId().values().stream()
                .map(ChainConsumerLifecycleSnapshotFixtureProvider::controls).map(value -> value.get("lifecycleGroup"))
                .filter(java.util.Objects::nonNull).map(Object::toString).distinct().toList();
        if (groups.size() > 1) {
            throw new IllegalArgumentException("One Chain fixture cannot span different lifecycle groups.");
        }
        String group = groups.isEmpty() ? null : groups.getFirst();
        Map<String, Fixture> peers = group == null ? null
                : lifecycleGroups.computeIfAbsent(group, ignored -> new ConcurrentHashMap<>());
        Fixture fixture = new Fixture(deploymentId, binding, group, peers, lifecycleGroups);
        if (peers != null) {
            peers.put(deploymentId, fixture);
        }
        return fixture;
    }

    private static Map<String, Object> controls(SnapshotFixtureInteraction interaction) {
        return interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
    }

    private static final class Fixture implements SnapshotFixture {
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final List<Exchange> received = new CopyOnWriteArrayList<>();
        private final AtomicReference<Thread> caller = new AtomicReference<>();
        private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
        private CamelContext context;
        private RouteDefinition route;
        private ChainEndpoint endpoint;
        private Future<?> pendingLifecycle;
        private final String lifecycleGroup;
        private final Map<String, Fixture> peers;
        private final Map<String, Map<String, Fixture>> lifecycleGroups;
        private volatile String consumerTag;
        private volatile CyclicBarrier entryBarrier;
        private volatile CountDownLatch processingEntered;
        private volatile CountDownLatch releaseProcessing;
        private volatile boolean observingCaller;
        private EventNotifierSupport observer;

        private Fixture(String deploymentId, SnapshotFixtureBinding binding, String lifecycleGroup,
                Map<String, Fixture> peers, Map<String, Map<String, Fixture>> lifecycleGroups) {
            this.deploymentId = deploymentId;
            this.binding = binding;
            this.lifecycleGroup = lifecycleGroup;
            this.peers = peers;
            this.lifecycleGroups = lifecycleGroups;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            context = camelContext;
            String sourceUri = "cip-chain:" + binding.definition().getSourceElementId();
            route = SnapshotRouteNodes.requireSingle(routes.stream()
                    .filter(candidate -> sourceUri.equals(consumerKey(candidate.getInput().getUri()))).toList(),
                    "Chain lifecycle fixture requires its generated trigger");
            assertTrue(route.getInput().getUri().startsWith("cip-chain:"), "Chain lifecycle requires a cip-chain consumer.");
            endpoint = context.getEndpoint(route.getInput().getUri(), ChainEndpoint.class);
            if (binding.interactionsByInvocationId().values().stream().map(ChainConsumerLifecycleSnapshotFixtureProvider::controls)
                    .anyMatch(value -> Boolean.TRUE.equals(value.get("applicationClassLoaderMarker")))) {
                ClassLoader parent = context.getApplicationContextClassLoader();
                context.setApplicationContextClassLoader(new ClassLoader(
                        parent == null ? Thread.currentThread().getContextClassLoader() : parent) { });
            }
            AdviceWith.adviceWith(context, route, false, advice -> advice.weaveAddFirst().process(exchange -> {
                        Exchange copy = new DefaultExchange(context, exchange.getPattern());
                        copy.getMessage().setBody(exchange.getMessage().getBody());
                        copy.getMessage().setHeaders(new LinkedHashMap<>(exchange.getMessage().getHeaders()));
                        exchange.getAllProperties().forEach(copy::setProperty);
                        received.add(copy);
                        if (consumerTag != null) {
                            exchange.getMessage().setHeader("snapshotChainConsumer", consumerTag);
                        }
                        CyclicBarrier barrier = entryBarrier;
                        if (barrier != null) {
                            assertSame(context.getApplicationContextClassLoader(), Thread.currentThread().getContextClassLoader(),
                                    "A concurrent child must run with its own application class loader.");
                            barrier.await(5, TimeUnit.SECONDS);
                        }
                        CountDownLatch entered = processingEntered;
                        if (entered != null) {
                            entered.countDown();
                            assertTrue(releaseProcessing.await(10, TimeUnit.SECONDS), "Held Chain processing must be released.");
                        }
                    }));
            observer = new EventNotifierSupport() {
                @Override
                public boolean isEnabled(CamelEvent event) {
                    return observingCaller && event instanceof CamelEvent.ExchangeSendingEvent;
                }

                @Override
                public void notify(CamelEvent event) {
                    if (event instanceof CamelEvent.ExchangeSendingEvent sending
                            && consumerKey(sending.getEndpoint().getEndpointUri()).equals(consumerKey(endpoint.getEndpointUri()))) {
                        caller.compareAndSet(null, Thread.currentThread());
                    }
                }
            };
            context.getManagementStrategy().addEventNotifier(observer);
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            received.clear();
            caller.set(null);
            pendingLifecycle = null;
            Map<String, Object> control = controls(binding.interaction(invocation.getId()));
            consumerTag = (String) control.get("consumerTag");
            entryBarrier = control.get("concurrentEntries") instanceof Number count ? new CyclicBarrier(count.intValue()) : null;
            processingEntered = null;
            releaseProcessing = null;
            String action = (String) control.get("consumerAction");
            if ("stop".equals(action) || "restart".equals(action) || "start-when-caller-waits".equals(action)) {
                var stoppedConsumer = context.getRoute(route.getId()).getConsumer();
                context.getRouteController().stopRoute(route.getId());
                if (Boolean.TRUE.equals(control.get("expectOtherConsumer"))) {
                    assertNotNull(endpoint.getConsumer(), "The other deployment must remain registered.");
                    assertNotSame(stoppedConsumer, endpoint.getConsumer(), "Stopping a deployment must remove only its own consumer.");
                } else {
                    assertEquals(null, endpoint.getConsumer(), "Stopped chain trigger must remove its consumer.");
                }
            }
            if ("start".equals(action) || "restart".equals(action)) {
                context.getRouteController().startRoute(route.getId());
            } else if ("start-when-caller-waits".equals(action)) {
                observingCaller = true;
                pendingLifecycle = lifecycle.submit(() -> {
                    var limiter = RateLimiterBuilder.newBuilder().withRate(50, TimeUnit.SECONDS).withConstantThroughput().build();
                    Unreliables.retryUntilTrue(5, TimeUnit.SECONDS, () -> limiter.getWhenReady(() -> {
                        Thread thread = caller.get();
                        return thread != null && Arrays.stream(thread.getStackTrace())
                                .anyMatch(frame -> ChainBlockingProducer.class.getName().equals(frame.getClassName())
                                        && "awaitConsumer".equals(frame.getMethodName()));
                    }));
                    context.getRouteController().startRoute(route.getId());
                    return null;
                });
            } else if ("stop-while-processing".equals(action) || "replace-while-processing".equals(action)) {
                processingEntered = new CountDownLatch(1);
                releaseProcessing = new CountDownLatch(1);
                pendingLifecycle = lifecycle.submit(() -> {
                    assertTrue(processingEntered.await(5, TimeUnit.SECONDS), "Chain processing must start before shutdown.");
                    if ("replace-while-processing".equals(action)) {
                        Fixture peer = peers.get(control.get("peerDeployment").toString());
                        assertNotNull(peer, "The replacement Chain deployment must be loaded.");
                        peer.context.getRouteController().startRoute(peer.route.getId());
                        assertSame(peer.context, endpoint.getConsumer().getEndpoint().getCamelContext(),
                                "The replacement consumer must become active while the old request is held.");
                    }
                    AtomicReference<Thread> stopping = new AtomicReference<>();
                    try (var stopper = Executors.newVirtualThreadPerTaskExecutor()) {
                        Future<?> stopped = stopper.submit(() -> {
                            stopping.set(Thread.currentThread());
                            context.getRouteController().stopRoute(route.getId());
                            return null;
                        });
                        try {
                            awaitShutdownWait(stopping);
                            assertTrue(context.getInflightRepository().size(route.getId()) > 0,
                                    "Graceful Chain shutdown must observe the held exchange.");
                            assertFalse(stopped.isDone(), "Chain shutdown must wait for the running exchange.");
                        } finally {
                            releaseProcessing.countDown();
                        }
                        stopped.get(10, TimeUnit.SECONDS);
                    }
                    return null;
                });
            }
        }

        private void awaitShutdownWait(AtomicReference<Thread> stopping) {
            var limiter = RateLimiterBuilder.newBuilder().withRate(50, TimeUnit.SECONDS).withConstantThroughput().build();
            Unreliables.retryUntilTrue(5, TimeUnit.SECONDS, () -> limiter.getWhenReady(() -> {
                Thread thread = stopping.get();
                return thread != null && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> "org.apache.camel.impl.engine.DefaultShutdownStrategy".equals(frame.getClassName()));
            }));
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            if (pendingLifecycle != null) {
                pendingLifecycle.get(15, TimeUnit.SECONDS);
            }
            observingCaller = false;
            var expected = binding.interaction(invocation.getId()).getExpectedRequest();
            assertEquals(expected.getCount(), received.size(), "Chain trigger invocation count for " + invocation.getId());
            if (entryBarrier != null) {
                assertEquals(received.size(), received.stream().map(exchange -> exchange.getMessage().getHeader("X-Chain-Request"))
                        .distinct().count(), "Concurrent Chain requests must retain distinct request IDs.");
            }
            for (Exchange exchange : received) {
                if (expected.hasBody()) {
                    assertEquals(expected.getBody(), exchange.getMessage().getBody(), "Chain trigger received body");
                }
                SnapshotValueAssertions.assertMapValues(expected.getHeaders(), exchange.getMessage().getHeaders(),
                        "Chain trigger received header");
                SnapshotValueAssertions.assertMapValues(expected.getProperties(), exchange.getAllProperties(),
                        "Chain trigger received property");
            }
        }

        @Override
        public String timeoutDiagnostics() {
            return "Chain trigger route=" + route.getId() + ", status=" + context.getRouteController().getRouteStatus(route.getId())
                    + ", received=" + received.size() + ", caller=" + caller.get() + ", observingCaller=" + observingCaller;
        }

        @Override
        public void verify() {
            assertTrue(pendingLifecycle == null || pendingLifecycle.isDone(), "Chain consumer restart must finish.");
        }

        @Override
        public void releasePendingWork() {
            if (releaseProcessing != null) {
                releaseProcessing.countDown();
            }
        }

        @Override
        public void close() {
            observingCaller = false;
            releasePendingWork();
            lifecycle.shutdownNow();
            if (peers != null) {
                peers.remove(deploymentId, this);
                if (peers.isEmpty()) {
                    lifecycleGroups.remove(lifecycleGroup, peers);
                }
            }
            if (context != null && observer != null) {
                context.getManagementStrategy().removeEventNotifier(observer);
            }
        }

        private static String consumerKey(String uri) {
            return uri.split("\\?", 2)[0].replace("cip-chain://", "cip-chain:");
        }
    }
}

package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.quartz.QuartzComponent;
import org.apache.camel.component.quartz.QuartzEndpoint;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.EverythingMatcher;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.listeners.TriggerListenerSupport;
import org.qubership.integration.platform.engine.camel.listeners.actions.routes.started.CommitSchedulerJobsAction;
import org.qubership.integration.platform.engine.camel.listeners.actions.routes.stopped.ResetSchedulersProxyOnStopAction;
import org.qubership.integration.platform.engine.camel.processors.EmptyProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.service.BlueGreenSchedulerControllerService;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    @Override
    public String getId() {
        return "scheduler";
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new Fixture(deploymentId, bindings);
    }

    private static final class Fixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final List<Observed> observed = new ArrayList<>();
        private final List<CompletableFuture<Void>> stopTasks = new ArrayList<>();
        private CamelContext context;
        private SchedulerSnapshotDependencies dependencies;
        private QuartzSchedulerService service;
        private Scheduler scheduler;
        private List<RouteDefinition> routes;
        private EventNotifierSupport lifecycle;
        private String jobListenerName;
        private String triggerListenerName;
        private SchedulerListenerSupport schedulerListener;

        private Fixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = bindings;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) throws Exception {
            context = camelContext;
            dependencies = CDI.current().select(SchedulerSnapshotDependencies.class).get();
            boolean inactive = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(Fixture::properties).anyMatch(properties -> Boolean.TRUE.equals(properties.get("initialInactive")));
            boolean overlap = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(Fixture::properties).anyMatch(properties -> Boolean.TRUE.equals(properties.get("overlap")));
            dependencies.initialize(inactive, overlap);
            scheduler = dependencies.scheduler();
            service = CDI.current().select(QuartzSchedulerService.class).get();
            QuartzComponent component = context.getComponent("quartz", QuartzComponent.class);
            component.setScheduler(service.getSchedulerProxy());
            component.setPrefixInstanceName(false);
            component.setEnableJmx(false);
            component.setAutoStartScheduler(false);
            lifecycle = new EventNotifierSupport() {
                @Override
                public boolean isEnabled(CamelEvent event) {
                    return event instanceof CamelEvent.RouteStartedEvent || event instanceof CamelEvent.RouteStoppedEvent;
                }

                @Override
                public void notify(CamelEvent event) throws Exception {
                    if (event instanceof CamelEvent.RouteStartedEvent started && ownRoute(started.getRoute().getRouteId())) {
                        CDI.current().select(CommitSchedulerJobsAction.class, Any.Literal.INSTANCE).get().process(started);
                    } else if (event instanceof CamelEvent.RouteStoppedEvent stopped && ownRoute(stopped.getRoute().getRouteId())) {
                        CDI.current().select(ResetSchedulersProxyOnStopAction.class, Any.Literal.INSTANCE).get().process(stopped);
                    }
                }
            };
            context.getManagementStrategy().addEventNotifier(lifecycle);
        }

        private boolean ownRoute(String id) {
            return routes != null && routes.stream().anyMatch(route -> id.equals(route.getId()));
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> generatedRoutes) throws Exception {
            routes = List.copyOf(generatedRoutes);
            SnapshotFixtureRouteScope.bind(context, routes, deploymentId + ":scheduler",
                    Map.of("chainFinishProcessor", new EmptyProcessor()));
            for (SnapshotFixtureBinding binding : bindings) {
                String node = binding.definition().getNodeId();
                RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, node),
                        "Scheduler fixture requires one generated scheduler step").route();
                String uri = route.getInput().getUri();
                assertTrue(uri.startsWith("quartz://"), "The generated scheduler must retain its native quartz endpoint.");
                QuartzEndpoint endpoint = (QuartzEndpoint) context.getComponent("quartz", QuartzComponent.class).createEndpoint(uri);
                TriggerKey key = endpoint.getTriggerKey();
                assertFalse(scheduler.checkExists(new JobKey(key.getName(), key.getGroup())),
                        "Generated jobs must be deferred until the actual route-start commit.");
                SchedulerSnapshotRuntime runtime = new SchedulerSnapshotRuntime(context, scheduler, key);
                context.getRegistry().bind(SchedulerSnapshotRuntime.registryName(node), runtime);
                AdviceWith.adviceWith(context, route, false, advice -> advice.weaveById(node).before().process(runtime::entered));
                observed.add(new Observed(binding, route, endpoint, runtime, context));
            }
            jobListenerName = "snapshot-scheduler-jobs-" + UUID.randomUUID();
            scheduler.getListenerManager().addJobListener(new JobListenerSupport() {
                @Override
                public String getName() {
                    return jobListenerName;
                }

                @Override
                public void jobWasExecuted(JobExecutionContext job, JobExecutionException failure) {
                    observed.stream().filter(item -> item.endpoint().getTriggerKey().equals(job.getTrigger().getKey()))
                            .forEach(item -> item.runtime().jobFinished(job, failure));
                }
            }, EverythingMatcher.allJobs());
            triggerListenerName = "snapshot-scheduler-triggers-" + UUID.randomUUID();
            scheduler.getListenerManager().addTriggerListener(new TriggerListenerSupport() {
                @Override
                public String getName() {
                    return triggerListenerName;
                }

                @Override
                public void triggerMisfired(Trigger trigger) {
                    observed.stream().filter(item -> item.endpoint().getTriggerKey().equals(trigger.getKey()))
                            .forEach(item -> item.runtime().misfired());
                }
            }, EverythingMatcher.allTriggers());
            schedulerListener = new SchedulerListenerSupport() {
                @Override
                public void triggerPaused(TriggerKey key) {
                    observed.stream().filter(item -> item.endpoint().getTriggerKey().equals(key))
                            .forEach(item -> item.runtime().triggerPaused());
                }
            };
            scheduler.getListenerManager().addSchedulerListener(schedulerListener);
            if (bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(Fixture::properties).anyMatch(properties -> Boolean.TRUE.equals(properties.get("deferRoutesUntilInvocation")))) {
                for (RouteDefinition route : routes) {
                    ((ModelCamelContext) context).removeRouteDefinition(route);
                }
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (Observed item : observed) {
                SnapshotFixtureInteraction interaction = item.binding().interaction(invocation.getId());
                Map<String, Object> controls = properties(interaction);
                int expected = interaction.getExpectedRequest() == null ? 1 : interaction.getExpectedRequest().getCount();
                item.runtime().prepare(expected, controls);
                Object action = controls.getOrDefault("action", "observe");
                if (controls.get("blueGreenState") instanceof String state) {
                    java.util.Set<JobKey> before = scheduler.getJobKeys(GroupMatcher.anyJobGroup());
                    scheduler.resumeTrigger(item.endpoint().getTriggerKey());
                    BlueGreenState previous = dependencies.currentState();
                    BlueGreenState next = SchedulerSnapshotDependencies.state(state);
                    CDI.current().select(BlueGreenSchedulerControllerService.class).get()
                            .onStateChange(new BlueGreenStateService.BlueGreenStateChange(previous, next));
                    dependencies.currentState(next);
                    assertEquals(before, scheduler.getJobKeys(GroupMatcher.anyJobGroup()),
                            "Blue-green transitions must retain the same registered jobs.");
                    service.getSchedulerProxy().start();
                    assertEquals(!"ACTIVE".equals(state), scheduler.isInStandbyMode(),
                            "Actual blue-green controller state must determine native scheduler standby.");
                }
                if ("stop-start".equals(action)) {
                    context.getRouteController().stopRoute(item.route().getId());
                    assertTrue(context.getRouteController().getRouteStatus(item.route().getId()).isStopped());
                    assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(item.endpoint().getTriggerKey()));
                    item.runtime().assertAbsentThroughNextFire();
                    context.getRouteController().startRoute(item.route().getId());
                } else if ("suspend-resume".equals(action)) {
                    context.getRouteController().suspendRoute(item.route().getId());
                    assertTrue(context.getRouteController().getRouteStatus(item.route().getId()).isSuspended());
                    assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(item.endpoint().getTriggerKey()));
                    item.runtime().assertAbsentThroughNextFire();
                    context.getRouteController().resumeRoute(item.route().getId());
                } else if ("endpoint-stop".equals(action)) {
                    context.getRouteController().stopRoute(item.route().getId());
                    item.endpoint().stop();
                } else if ("remove".equals(action) || "reload".equals(action)) {
                    removeRoutes();
                    if ("reload".equals(action)) {
                        ((ModelCamelContext) context).addRouteDefinitions(routes);
                    }
                } else if ("graceful-stop".equals(action)) {
                    stopTasks.add(CompletableFuture.runAsync(() -> {
                        try {
                            item.runtime().awaitEntry();
                            context.getRouteController().stopRoute(item.route().getId());
                        } catch (Exception failure) {
                            item.runtime().releasePendingWork();
                            throw new IllegalStateException(failure);
                        }
                    }));
                } else if ("misfire".equals(action)) {
                    scheduler.standby();
                    scheduler.resumeTrigger(item.endpoint().getTriggerKey());
                    Date scheduled = scheduler.getTrigger(item.endpoint().getTriggerKey()).getNextFireTime();
                    long wait = Math.max(1, scheduled.getTime() + 15100 - System.currentTimeMillis());
                    assertFalse(new CountDownLatch(1).await(wait, TimeUnit.MILLISECONDS));
                    item.runtime().expectMisfireAfter(scheduled.getTime() + 15000);
                }
                if (expected > 0 && !controls.containsKey("blueGreenState")) {
                    scheduler.resumeTrigger(item.endpoint().getTriggerKey());
                    service.getSchedulerProxy().start();
                }
                item.runtime().ready();
            }
        }

        private void removeRoutes() throws Exception {
            ModelCamelContext model = (ModelCamelContext) context;
            for (RouteDefinition route : routes) {
                if (context.getRoute(route.getId()) != null) {
                    context.getRouteController().stopRoute(route.getId());
                    service.removeSchedulerJobs(context.getRoute(route.getId()));
                }
                if (model.getRouteDefinition(route.getId()) != null) {
                    model.removeRouteDefinition(route);
                }
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (Observed item : observed) {
                SnapshotFixtureInteraction interaction = item.binding().interaction(invocation.getId());
                Map<String, Object> controls = properties(interaction);
                List<org.apache.camel.Exchange> results = item.runtime().awaitResults();
                if (controls.containsKey("expectedCompletionBody")) {
                    for (org.apache.camel.Exchange exchange : results) {
                        assertEquals(controls.get("expectedCompletionBody"), exchange.getMessage().getBody(),
                                "Scheduled script completion body");
                    }
                }
                if (controls.get("expectedCron") instanceof String expected) {
                    assertEquals(expected, item.endpoint().getCron(), "Generated cron expression");
                }
                if (controls.get("expectedDeleteJob") instanceof Boolean expected) {
                    assertEquals(expected, item.endpoint().isDeleteJob(), "Generated deleteJob flag");
                }
                TriggerKey key = item.endpoint().getTriggerKey();
                Trigger trigger = scheduler.getTrigger(key);
                if (controls.get("expectedRegistered") instanceof Boolean expected) {
                    assertEquals(expected, scheduler.checkExists(new JobKey(key.getName(), key.getGroup())), "Native scheduler job registration");
                    assertEquals(expected, trigger != null, "Native scheduler trigger registration");
                }
                if (controls.get("expectedTriggerPaused") instanceof Boolean expected) {
                    assertEquals(expected, scheduler.getTriggerState(key) == Trigger.TriggerState.PAUSED, "Native trigger paused state");
                }
                if (trigger instanceof CronTrigger cron) {
                    assertEquals(CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW, cron.getMisfireInstruction());
                    assertEquals(TimeZone.getDefault(), cron.getTimeZone(), "Default native Quartz time zone");
                    CronExpression independent = new CronExpression(item.endpoint().getCron());
                    independent.setTimeZone(cron.getTimeZone());
                    Date from = new Date();
                    for (int index = 0; index < 3; index++) {
                        Date next = independent.getNextValidTimeAfter(from);
                        assertEquals(next, cron.getFireTimeAfter(from), "Generated cron next-fire calculation");
                        from = next;
                    }
                }
                if (Boolean.TRUE.equals(controls.get("expectedStartupCommitFailure"))) {
                    assertFalse(dependencies.commitFailures().isEmpty(), "Expired cron must record its real native commit failure.");
                    for (Throwable failure : dependencies.commitFailures()) {
                        assertEquals(org.quartz.SchedulerException.class, failure.getClass(), "Expired cron native commit failure type");
                        assertEquals("Based on configured schedule, the given trigger will never fire.", failure.getMessage());
                        assertNull(failure.getCause(), "Expired cron native commit failure cause");
                    }
                }
                if ("misfire".equals(controls.get("action"))) {
                    assertTrue(item.runtime().misfires() > 0, "The native trigger must report a real cron misfire.");
                    assertEquals(1, item.runtime().receivedCount(), "One recovery execution after missed ticks");
                }
                if (interaction.getExpectedRequest() != null && interaction.getExpectedRequest().getCount() == 0
                        && !Boolean.TRUE.equals(controls.get("inspectionOnly"))) {
                    item.runtime().assertAbsentThroughNextFire();
                }
            }
            for (CompletableFuture<Void> task : stopTasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        @Override
        public void verifyStartupFailure() throws Exception {
            for (Observed item : observed) {
                assertEquals(0, item.runtime().receivedCount(), "Invalid cron must never execute business work.");
                TriggerKey key = item.endpoint().getTriggerKey();
                assertFalse(scheduler.checkExists(new JobKey(key.getName(), key.getGroup())), "Invalid cron must not register a job.");
                assertNull(scheduler.getTrigger(key), "Invalid cron must not register a trigger.");
            }
        }

        @Override
        public void releasePendingWork() {
            observed.forEach(item -> item.runtime().releasePendingWork());
        }

        @Override
        public void beforeContextStop() throws Exception {
            releasePendingWork();
            for (Observed item : observed) {
                scheduler.pauseTrigger(item.endpoint().getTriggerKey());
            }
        }

        @Override
        public String timeoutDiagnostics() {
            return observed.stream().map(item -> item.runtime().diagnostics()).toList().toString();
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() throws Exception {
            releasePendingWork();
            if (context != null && lifecycle != null) {
                context.getManagementStrategy().removeEventNotifier(lifecycle);
            }
            if (scheduler != null && !scheduler.isShutdown()) {
                if (jobListenerName != null) {
                    scheduler.getListenerManager().removeJobListener(jobListenerName);
                    scheduler.getListenerManager().removeTriggerListener(triggerListenerName);
                    scheduler.getListenerManager().removeSchedulerListener(schedulerListener);
                }
                dependencies.close();
            }
        }

        private static Map<String, Object> properties(SnapshotFixtureInteraction interaction) {
            return interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        }

        private record Observed(SnapshotFixtureBinding binding, RouteDefinition route, QuartzEndpoint definition,
                                SchedulerSnapshotRuntime runtime, CamelContext context) {
            QuartzEndpoint endpoint() {
                org.apache.camel.Route actual = context.getRoute(route.getId());
                return actual == null ? definition : (QuartzEndpoint) actual.getEndpoint();
            }
        }
    }
}

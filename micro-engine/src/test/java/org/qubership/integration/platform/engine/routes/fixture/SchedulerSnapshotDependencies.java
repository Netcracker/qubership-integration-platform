package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.model.Version;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.EverythingMatcher;
import org.quartz.listeners.SchedulerListenerSupport;
import org.qubership.integration.platform.engine.configuration.quartz.RequestContextActivatingJobListener;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Singleton
public class SchedulerSnapshotDependencies {
    private final List<Throwable> commitFailures = new CopyOnWriteArrayList<>();
    private final ControlledState state = new ControlledState();
    private Scheduler scheduler;
    private Handler logHandler;

    public synchronized void initialize(boolean inactive, boolean overlap) throws Exception {
        if (scheduler != null) {
            return;
        }
        state.current = state(inactive ? "INACTIVE" : "ACTIVE");
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "snapshot-scheduler-" + UUID.randomUUID());
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
        properties.setProperty("org.quartz.threadPool.threadCount", overlap ? "2" : "1");
        properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        properties.setProperty("org.quartz.jobStore.misfireThreshold", "15000");
        scheduler = new StdSchedulerFactory(properties).getScheduler();
        scheduler.getListenerManager().addJobListener(new RequestContextActivatingJobListener(), EverythingMatcher.allJobs());
        scheduler.getListenerManager().addSchedulerListener(new SchedulerListenerSupport() {
            @Override
            public void jobScheduled(org.quartz.Trigger trigger) {
                try {
                    scheduler.pauseTrigger(trigger.getKey());
                } catch (org.quartz.SchedulerException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        });
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getThrown() != null && "Failed to commit scheduled jobs".equals(record.getMessage())) {
                    commitFailures.add(record.getThrown());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger.getLogger(QuartzSchedulerService.class.getName()).addHandler(logHandler);
    }

    @Produces
    @ApplicationScoped
    public QuartzSchedulerService schedulerService() {
        return new QuartzSchedulerService(scheduler, state);
    }

    Scheduler scheduler() {
        return scheduler;
    }

    BlueGreenState currentState() {
        return state.current;
    }

    void currentState(BlueGreenState next) {
        state.current = next;
    }

    List<Throwable> commitFailures() {
        return List.copyOf(commitFailures);
    }

    static BlueGreenState state(String value) {
        State state = "ACTIVE".equals(value) ? State.ACTIVE : State.CANDIDATE;
        return new BlueGreenState(new NamespaceVersion("snapshot", state, new Version(1)),
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    synchronized void close() throws Exception {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown(true);
            java.util.logging.Logger.getLogger(QuartzSchedulerService.class.getName()).removeHandler(logHandler);
        }
        assertTrue(scheduler == null || scheduler.isShutdown(), "The fixture-owned Quartz scheduler must terminate.");
    }

    private static final class ControlledState extends BlueGreenStateService {
        private BlueGreenState current;

        @Override
        public BlueGreenState getBlueGreenState() {
            return current;
        }
    }
}

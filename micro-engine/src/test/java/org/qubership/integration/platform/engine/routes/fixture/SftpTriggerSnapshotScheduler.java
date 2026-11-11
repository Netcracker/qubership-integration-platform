package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.component.quartz.QuartzComponent;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.qubership.integration.platform.engine.camel.scheduler.SchedulerProxy;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SftpTriggerSnapshotScheduler implements AutoCloseable {
    private static final String REGISTRY_NAME = "snapshot-sftp-native-scheduler";
    private final CamelContext context;
    private final Scheduler delegate;
    private final SchedulerProxy proxy;
    private final EventNotifierSupport observer;
    private boolean closed;

    private SftpTriggerSnapshotScheduler(CamelContext context) throws Exception {
        this.context = context;
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "snapshot-sftp-" + UUID.randomUUID());
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
        properties.setProperty("org.quartz.threadPool.threadCount", "2");
        properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        delegate = new StdSchedulerFactory(properties).getScheduler();
        proxy = new SchedulerProxy(delegate, false);
        QuartzComponent component = context.getComponent("quartz", QuartzComponent.class);
        component.setScheduler(proxy);
        component.setPrefixInstanceName(false);
        component.setEnableJmx(false);
        observer = new EventNotifierSupport() {
            @Override
            public boolean isEnabled(CamelEvent event) {
                return event instanceof CamelEvent.RouteStartedEvent || event instanceof CamelEvent.CamelContextStartedEvent;
            }

            @Override
            public void notify(CamelEvent event) throws Exception {
                proxy.commitScheduledJobs();
            }
        };
        context.getManagementStrategy().addEventNotifier(observer);
    }

    static SftpTriggerSnapshotScheduler install(CamelContext context) throws Exception {
        SftpTriggerSnapshotScheduler existing = context.getRegistry().lookupByNameAndType(REGISTRY_NAME, SftpTriggerSnapshotScheduler.class);
        if (existing != null) {
            return existing;
        }
        SftpTriggerSnapshotScheduler created = new SftpTriggerSnapshotScheduler(context);
        context.getRegistry().bind(REGISTRY_NAME, created);
        return created;
    }

    @Override
    public void close() throws Exception {
        if (!closed) {
            closed = true;
            context.getManagementStrategy().removeEventNotifier(observer);
            delegate.shutdown(true);
        }
        assertTrue(delegate.isShutdown(), "The fixture-owned SFTP Quartz scheduler must shut down after context cleanup.");
    }
}

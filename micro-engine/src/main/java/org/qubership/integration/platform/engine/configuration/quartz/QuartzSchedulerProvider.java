package org.qubership.integration.platform.engine.configuration.quartz;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.EverythingMatcher;

import java.util.Properties;

public class QuartzSchedulerProvider {
    @Produces
    @Alternative
    @Priority(1)
    public Scheduler quartzScheduler() throws SchedulerException {
        SchedulerFactory schedulerFactory = new StdSchedulerFactory(getSchedulerProperties());
        Scheduler scheduler = schedulerFactory.getScheduler();
        // Without this, beans that require an active RequestScoped context (e.g. security
        // identity lookups used by context propagation) fail when a chain is triggered by the scheduler
        scheduler.getListenerManager().addJobListener(
                new RequestContextActivatingJobListener(), EverythingMatcher.allJobs());
        return scheduler;
    }

    static Properties getSchedulerProperties() {
        Properties properties = new Properties();
        properties.put("org.quartz.scheduler.instanceId", "AUTO");
        properties.put("org.quartz.scheduler.wrapJobExecutionInUserTransaction", "false");
        properties.put("org.quartz.jobStore.misfireThreshold", "15000");
        properties.put("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.put("org.quartz.scheduler.threadsInheritContextClassLoaderOfInitializer", "true");
        properties.put("org.quartz.scheduler.batchTriggerAcquisitionFireAheadTimeWindow", "0");
        properties.put("org.quartz.scheduler.batchTriggerAcquisitionMaxCount", "1");
        properties.put("org.quartz.scheduler.classLoadHelper.class", "org.quartz.simpl.InitThreadContextClassLoadHelper");
        properties.put("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.put("org.quartz.scheduler.rmi.proxy", "false");
        properties.put("org.quartz.threadPool.threadCount", "10");
        properties.put("org.quartz.scheduler.rmi.export", "false");
        properties.put("org.quartz.scheduler.instanceName", "quartz-scheduler");
        properties.put("org.quartz.threadPool.threadPriority", "5");
        // Every pod rebuilds the jobs from its chain sources, and only the elected leader runs them.
        properties.put("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        return properties;
    }
}

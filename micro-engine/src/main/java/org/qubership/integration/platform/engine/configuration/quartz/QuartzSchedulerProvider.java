package org.qubership.integration.platform.engine.configuration.quartz;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Properties;

@IfBuildProfile("dbaas")
public class QuartzSchedulerProvider {
    @Produces
    @Alternative
    @Priority(1)
    public Scheduler quartzScheduler() throws SchedulerException {
        SchedulerFactory schedulerFactory = new StdSchedulerFactory(getSchedulerProperties());
        return schedulerFactory.getScheduler();
    }

    private static Properties getSchedulerProperties() {
        Properties properties = new Properties();
        properties.put("org.quartz.jobStore.useProperties", "true");
        properties.put("org.quartz.scheduler.instanceId", "AUTO");
        properties.put("org.quartz.scheduler.wrapJobExecutionInUserTransaction", "false");
        properties.put("org.quartz.jobStore.misfireThreshold", "15000");
        properties.put("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.put("org.quartz.scheduler.threadsInheritContextClassLoaderOfInitializer", "true");
        properties.put("org.quartz.jobStore.driverDelegateClass", "io.quarkus.quartz.runtime.jdbc.QuarkusPostgreSQLDelegate");
        properties.put("org.quartz.jobStore.tablePrefix", "engine.QRTZ_");
        properties.put("org.quartz.scheduler.batchTriggerAcquisitionFireAheadTimeWindow", "0");
        properties.put("org.quartz.scheduler.batchTriggerAcquisitionMaxCount", "1");
        properties.put("org.quartz.scheduler.classLoadHelper.class", "org.quartz.simpl.InitThreadContextClassLoadHelper");
        properties.put("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.put("org.quartz.scheduler.rmi.proxy", "false");
        properties.put("org.quartz.threadPool.threadCount", "10");
        properties.put("org.quartz.jobStore.acquireTriggersWithinLock", "true");
        properties.put("org.quartz.scheduler.rmi.export", "false");
        properties.put("org.quartz.jobStore.dataSource", "configs");
        properties.put("org.quartz.scheduler.instanceName", "quartz-scheduler");
        properties.put("org.quartz.threadPool.threadPriority", "5");
        properties.put("org.quartz.jobStore.isClustered", "true");
        properties.put("org.quartz.dataSource.configs.connectionProvider.class", QuartzSchedulerConnectionProvider.class.getName());
        properties.put("org.quartz.jobStore.clusterCheckinInterval", "15000");
        properties.put("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
        return properties;
    }
}

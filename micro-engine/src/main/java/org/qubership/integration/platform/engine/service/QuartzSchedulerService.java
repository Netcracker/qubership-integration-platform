/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Route;
import org.apache.camel.component.file.remote.SftpConsumer;
import org.apache.camel.component.quartz.QuartzEndpoint;
import org.apache.camel.pollconsumer.quartz.QuartzScheduledPollConsumerScheduler;
import org.apache.camel.spi.ScheduledPollConsumerScheduler;
import org.quartz.*;
import org.qubership.integration.platform.engine.camel.metadata.Metadata;
import org.qubership.integration.platform.engine.camel.metadata.MetadataService;
import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerFactoryProxy;
import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerProxy;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
@ApplicationScoped
public class QuartzSchedulerService {
    @Inject
    MetadataService metadataService;

    @Inject
    StdSchedulerFactoryProxy schedulerFactoryProxy;

    /**
     * Fix for removing scheduler jobs
     */
    public void removeSchedulerJobs(List<JobKey> jobs) {
        try {
            log.debug("Remove camel scheduler jobs: {}", jobs);
            if (!jobs.isEmpty()) {
                getFactory().getScheduler().deleteJobs(jobs);
            }
        } catch (SchedulerException e) {
            log.error("Failed to delete scheduler jobs", e);
        }
    }

    public void removeSchedulerJobsFromContext(CamelContext context, String deploymentId) {
        log.debug("Remove camel scheduler jobs from context");
        removeSchedulerJobs(getSchedulerJobsFromContext(context, deploymentId));
    }

    public List<JobKey> getSchedulerJobsFromContext(CamelContext context, String deploymentId) {
        return context.getRoutes().stream()
                .filter(route -> metadataService.getMetadata(route)
                        .map(Metadata::getDeploymentInfo)
                        .map(DeploymentInfo::getDeploymentId)
                        .map(deploymentId::equals)
                        .orElse(false))
                .map(this::getSchedulerJobsForRoute)
                .flatMap(Collection::stream)
                .toList();
    }

    private Collection<JobKey> getSchedulerJobsForRoute(Route route) {
        List<JobKey> jobs = new ArrayList<>();
        Endpoint endpoint = route.getEndpoint();
        if (endpoint instanceof QuartzEndpoint quartzEndpoint) {
            TriggerKey triggerKey = quartzEndpoint.getTriggerKey();
            // assumption: groupName and triggerName have been set in the Quartz component
            JobKey jobKey = JobKey.jobKey(triggerKey.getName(), triggerKey.getGroup());
            jobs.add(jobKey);
        }
        Consumer consumer = route.getConsumer();
        if (consumer instanceof SftpConsumer sftpConsumer) {
            ScheduledPollConsumerScheduler scheduler = sftpConsumer.getScheduler();

            if (scheduler instanceof QuartzScheduledPollConsumerScheduler quartzScheduler) {
                try {
                    Field f = quartzScheduler.getClass().getDeclaredField("job");
                    f.setAccessible(true);
                    JobKey jobKey = ((JobDetail) f.get(quartzScheduler)).getKey();
                    jobs.add(jobKey);
                } catch (Exception e) {
                    log.error("Failed to get field 'job' from class QuartzScheduledPollConsumerScheduler");
                }
            }
        }
        return jobs;
    }

    public synchronized void commitScheduledJobs() {
        try {
            log.debug("Commit camel scheduler jobs");
            StdSchedulerProxy scheduler = (StdSchedulerProxy) getFactory().getScheduler();
            scheduler.commitScheduledJobs();
        } catch (SchedulerException e) {
            log.error("Failed to commit scheduled jobs", e);
        }
    }

    public synchronized void resetSchedulersProxy() {
        try {
            log.debug("Reset camel scheduler proxy");
            StdSchedulerProxy scheduler = (StdSchedulerProxy) getFactory().getScheduler();
            scheduler.clearDelayedJobs();
        } catch (SchedulerException e) {
            log.error("Failed to reset scheduler proxy", e);
        }
    }

    /**
     * Suspend all schedulers on separate instance
     */
    public synchronized void suspendAllSchedulers() {
        try {
            log.info("Suspend camel quartz scheduler");
            ((StdSchedulerProxy) getFactory()).suspendScheduler();
        } catch (Exception e) {
            log.error("Failed to suspend scheduler", e);
        }
    }

    /**
     * Resume all schedulers on separate instance
     */
    public void resumeAllSchedulers() {
        try {
            log.info("Resume camel quartz scheduler");
            ((StdSchedulerProxy) getFactory()).resumeScheduler();
        } catch (SchedulerException e) {
            log.error("Failed to resume scheduler", e);
        }
    }

    public SchedulerFactory getFactory() {
        return schedulerFactoryProxy;
    }
}

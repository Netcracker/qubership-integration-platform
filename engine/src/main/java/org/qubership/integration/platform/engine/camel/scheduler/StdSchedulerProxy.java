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

package org.qubership.integration.platform.engine.camel.scheduler;

import org.apache.commons.lang3.tuple.Pair;
import org.quartz.JobDetail;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.core.QuartzScheduler;
import org.quartz.impl.StdScheduler;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class StdSchedulerProxy extends StdScheduler {
    private final Object lock = new Object();

    private final ConcurrentMap<Thread, List<Pair<JobDetail, Trigger>>> delayedScheduledJobsMap = new ConcurrentHashMap<>();

    private boolean isSuspended = false;

    public StdSchedulerProxy(QuartzScheduler sched) {
        super(sched);
    }


    @Override
    public Date scheduleJob(JobDetail jobDetail, Trigger trigger) throws SchedulerException {
        getDelayedScheduledJobs().add(Pair.of(jobDetail, trigger));
        return new Date();
    }

    @Override
    public void start() throws SchedulerException {
        synchronized (lock) {
            if (!isSuspended) {
                super.start();
            }
        }
    }

    @Override
    public void startDelayed(int seconds) throws SchedulerException {
        synchronized (lock) {
            if (!isSuspended) {
                super.startDelayed(seconds);
            }
        }
    }

    @Override
    public void shutdown(boolean waitForJobsToComplete) {
        // do nothing, disable scheduler shutdown
    }

    public void commitScheduledJobs() throws SchedulerException {
        for (Pair<JobDetail, Trigger> pair : getDelayedScheduledJobs()) {
            super.scheduleJob(pair.getLeft(), Set.of(pair.getRight()), true);
        }
        clearDelayedJobs();
    }

    public void suspendScheduler() {
        synchronized (lock) {
            if (!isSuspended) {
                isSuspended = true;
                if (!super.isInStandbyMode()) {
                    super.standby();
                }
            }
        }
    }

    public void resumeScheduler() throws SchedulerException {
        synchronized (lock) {
            if (isSuspended) {
                startAndResumeJobs();
                isSuspended = false;
            }
        }
    }

    private void startAndResumeJobs() throws SchedulerException {
        super.start();
        super.resumeAll();
    }

    public void clearDelayedJobs() {
        getDelayedScheduledJobs().clear();
    }

    private List<Pair<JobDetail, Trigger>> getDelayedScheduledJobs() {
        return delayedScheduledJobsMap.computeIfAbsent(
                Thread.currentThread(),
                thread -> new ArrayList<>(8)
        );
    }
}

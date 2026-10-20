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
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SchedulerProxy extends DelegatingScheduler {
    private final Object lock = new Object();

    private final ConcurrentMap<Thread, List<Pair<JobDetail, Trigger>>> delayedScheduledJobsMap = new ConcurrentHashMap<>();

    private boolean isSuspended = false;

    private static final long RECENT_FIRE_TIMES_MILLIS = 60_000;

    // The jobs live in memory on every pod of the domain, and only the elected leader runs them.
    private boolean isLeader = false;

    public SchedulerProxy(Scheduler scheduler) {
        super(scheduler);
    }


    @Override
    public Date scheduleJob(JobDetail jobDetail, Trigger trigger) throws SchedulerException {
        getDelayedScheduledJobs().add(Pair.of(jobDetail, trigger));
        return new Date();
    }

    @Override
    public void start() throws SchedulerException {
        synchronized (lock) {
            if (!isSuspended && isLeader) {
                super.start();
            }
        }
    }

    @Override
    public void startDelayed(int seconds) throws SchedulerException {
        synchronized (lock) {
            if (!isSuspended && isLeader) {
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

    public void suspendScheduler() throws SchedulerException {
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
                if (isLeader) {
                    moveMissedFireTimes(null, Set.of());
                    startAndResumeJobs();
                }
                isSuspended = false;
            }
        }
    }

    /**
     * Starts the scheduler on the pod that has just been elected. A trigger the previous leader also ran fires once
     * if one of its fire times fell after {@code previousLeaderSeenAt}, as a misfire on a persistent store would.
     * Every other fire time missed while this pod stood by is skipped.
     */
    public void takeLeadership(Instant previousLeaderSeenAt, Set<TriggerKey> previousLeaderTriggers)
            throws SchedulerException {
        synchronized (lock) {
            if (!isLeader) {
                if (!isSuspended) {
                    moveMissedFireTimes(previousLeaderSeenAt, previousLeaderTriggers);
                    startAndResumeJobs();
                }
                isLeader = true;
            }
        }
    }

    public void releaseLeadership() throws SchedulerException {
        synchronized (lock) {
            if (isLeader) {
                isLeader = false;
                if (!super.isInStandbyMode()) {
                    super.standby();
                }
            }
        }
    }

    private void startAndResumeJobs() throws SchedulerException {
        super.start();
        super.resumeAll();
    }

    private void moveMissedFireTimes(Instant previousLeaderSeenAt, Set<TriggerKey> previousLeaderTriggers)
            throws SchedulerException {
        Date now = new Date();
        for (TriggerKey key : super.getTriggerKeys(GroupMatcher.anyTriggerGroup())) {
            Trigger trigger = super.getTrigger(key);
            Date missed = previousLeaderTriggers.contains(key)
                    ? fireTimeMissedSince(trigger, previousLeaderSeenAt, now)
                    : null;
            if (missed != null) {
                super.rescheduleJob(key, trigger.getTriggerBuilder().startAt(missed).build());
            } else if (trigger.getNextFireTime() != null && trigger.getNextFireTime().before(now)) {
                // The previous leader or the other blue-green version ran these fire times.
                Date next = trigger.getFireTimeAfter(now);
                if (next == null) {
                    super.unscheduleJob(key);
                } else {
                    super.rescheduleJob(key, trigger.getTriggerBuilder().startAt(next).build());
                }
            }
        }
    }

    // A fire time between since and now that makes Quartz fire the trigger once. An old one is a misfire, which
    // the trigger's FireAndProceed instruction runs once; within the last minute only the latest one is.
    private static Date fireTimeMissedSince(Trigger trigger, Instant since, Date now) {
        if (since == null || !(trigger instanceof CronTrigger cronTrigger)) {
            return null;
        }
        CronExpression expression;
        try {
            expression = new CronExpression(cronTrigger.getCronExpression());
        } catch (ParseException e) {
            return null;
        }
        expression.setTimeZone(cronTrigger.getTimeZone());
        Date first = expression.getTimeAfter(Date.from(since));
        if (first == null || first.after(now)) {
            return null;
        }
        Date recentFrom = new Date(Math.max(first.getTime() - 1, now.getTime() - RECENT_FIRE_TIMES_MILLIS));
        Date latest = null;
        for (Date time = expression.getTimeAfter(recentFrom); time != null && !time.after(now);
                time = expression.getTimeAfter(time)) {
            latest = time;
        }
        return latest != null ? latest : first;
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

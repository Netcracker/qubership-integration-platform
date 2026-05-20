package org.qubership.integration.platform.engine.camel.scheduler;

import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.JobFactory;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DelegatingScheduler implements Scheduler {
    private final Scheduler delegate;

    public DelegatingScheduler(Scheduler delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getSchedulerName() throws SchedulerException {
        return delegate.getSchedulerName();
    }

    @Override
    public String getSchedulerInstanceId() throws SchedulerException {
        return delegate.getSchedulerInstanceId();
    }

    @Override
    public SchedulerContext getContext() throws SchedulerException {
        return delegate.getContext();
    }

    @Override
    public void start() throws SchedulerException {
        delegate.start();
    }

    @Override
    public void startDelayed(int seconds) throws SchedulerException {
        delegate.startDelayed(seconds);
    }

    @Override
    public boolean isStarted() throws SchedulerException {
        return delegate.isStarted();
    }

    @Override
    public void standby() throws SchedulerException {
        delegate.standby();
    }

    @Override
    public boolean isInStandbyMode() throws SchedulerException {
        return delegate.isInStandbyMode();
    }

    @Override
    public void shutdown() throws SchedulerException {
        delegate.shutdown();
    }

    @Override
    public void shutdown(boolean waitForJobsToComplete) throws SchedulerException {
        delegate.shutdown(waitForJobsToComplete);
    }

    @Override
    public boolean isShutdown() throws SchedulerException {
        return delegate.isShutdown();
    }

    @Override
    public SchedulerMetaData getMetaData() throws SchedulerException {
        return delegate.getMetaData();
    }

    @Override
    public List<JobExecutionContext> getCurrentlyExecutingJobs() throws SchedulerException {
        return delegate.getCurrentlyExecutingJobs();
    }

    @Override
    public void setJobFactory(JobFactory factory) throws SchedulerException {
        delegate.setJobFactory(factory);
    }

    @Override
    public ListenerManager getListenerManager() throws SchedulerException {
        return delegate.getListenerManager();
    }

    @Override
    public Date scheduleJob(JobDetail jobDetail, Trigger trigger) throws SchedulerException {
        return delegate.scheduleJob(jobDetail, trigger);
    }

    @Override
    public Date scheduleJob(Trigger trigger) throws SchedulerException {
        return delegate.scheduleJob(trigger);
    }

    @Override
    public void scheduleJob(JobDetail jobDetail, Set<? extends Trigger> triggersForJob, boolean replace) throws SchedulerException {
        delegate.scheduleJob(jobDetail, triggersForJob, replace);
    }

    @Override
    public void scheduleJobs(Map<JobDetail, Set<? extends Trigger>> triggersAndJobs, boolean replace) throws SchedulerException {
        delegate.scheduleJobs(triggersAndJobs, replace);
    }

    @Override
    public boolean unscheduleJob(TriggerKey triggerKey) throws SchedulerException {
        return delegate.unscheduleJob(triggerKey);
    }

    @Override
    public boolean unscheduleJobs(List<TriggerKey> triggerKeys) throws SchedulerException {
        return delegate.unscheduleJobs(triggerKeys);
    }

    @Override
    public Date rescheduleJob(TriggerKey triggerKey, Trigger newTrigger) throws SchedulerException {
        return delegate.rescheduleJob(triggerKey, newTrigger);
    }

    @Override
    public void addJob(JobDetail jobDetail, boolean replace) throws SchedulerException {
        delegate.addJob(jobDetail, replace);
    }

    @Override
    public void addJob(JobDetail jobDetail, boolean replace, boolean storeNonDurableWhileAwaitingScheduling) throws SchedulerException {
        delegate.addJob(jobDetail, replace, storeNonDurableWhileAwaitingScheduling);
    }

    @Override
    public boolean deleteJob(JobKey jobKey) throws SchedulerException {
        return delegate.deleteJob(jobKey);
    }

    @Override
    public boolean deleteJobs(List<JobKey> jobKeys) throws SchedulerException {
        return delegate.deleteJobs(jobKeys);
    }

    @Override
    public void triggerJob(JobKey jobKey) throws SchedulerException {
        delegate.triggerJob(jobKey);
    }

    @Override
    public void triggerJob(JobKey jobKey, JobDataMap data) throws SchedulerException {
        delegate.triggerJob(jobKey, data);
    }

    @Override
    public void pauseJob(JobKey jobKey) throws SchedulerException {
        delegate.pauseJob(jobKey);
    }

    @Override
    public void pauseJobs(GroupMatcher<JobKey> matcher) throws SchedulerException {
        delegate.pauseJobs(matcher);
    }

    @Override
    public void pauseTrigger(TriggerKey triggerKey) throws SchedulerException {
        delegate.pauseTrigger(triggerKey);
    }

    @Override
    public void pauseTriggers(GroupMatcher<TriggerKey> matcher) throws SchedulerException {
        delegate.pauseTriggers(matcher);
    }

    @Override
    public void resumeJob(JobKey jobKey) throws SchedulerException {
        delegate.resumeJob(jobKey);
    }

    @Override
    public void resumeJobs(GroupMatcher<JobKey> matcher) throws SchedulerException {
        delegate.resumeJobs(matcher);
    }

    @Override
    public void resumeTrigger(TriggerKey triggerKey) throws SchedulerException {
        delegate.resumeTrigger(triggerKey);
    }

    @Override
    public void resumeTriggers(GroupMatcher<TriggerKey> matcher) throws SchedulerException {
        delegate.resumeTriggers(matcher);
    }

    @Override
    public void pauseAll() throws SchedulerException {
        delegate.pauseAll();
    }

    @Override
    public void resumeAll() throws SchedulerException {
        delegate.resumeAll();
    }

    @Override
    public List<String> getJobGroupNames() throws SchedulerException {
        return delegate.getJobGroupNames();
    }

    @Override
    public Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher) throws SchedulerException {
        return delegate.getJobKeys(matcher);
    }

    @Override
    public List<? extends Trigger> getTriggersOfJob(JobKey jobKey) throws SchedulerException {
        return delegate.getTriggersOfJob(jobKey);
    }

    @Override
    public List<String> getTriggerGroupNames() throws SchedulerException {
        return delegate.getTriggerGroupNames();
    }

    @Override
    public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher) throws SchedulerException {
        return delegate.getTriggerKeys(matcher);
    }

    @Override
    public Set<String> getPausedTriggerGroups() throws SchedulerException {
        return delegate.getPausedTriggerGroups();
    }

    @Override
    public JobDetail getJobDetail(JobKey jobKey) throws SchedulerException {
        return delegate.getJobDetail(jobKey);
    }

    @Override
    public Trigger getTrigger(TriggerKey triggerKey) throws SchedulerException {
        return delegate.getTrigger(triggerKey);
    }

    @Override
    public Trigger.TriggerState getTriggerState(TriggerKey triggerKey) throws SchedulerException {
        return delegate.getTriggerState(triggerKey);
    }

    @Override
    public void resetTriggerFromErrorState(TriggerKey triggerKey) throws SchedulerException {
        delegate.resetTriggerFromErrorState(triggerKey);
    }

    @Override
    public void addCalendar(String calName, Calendar calendar, boolean replace, boolean updateTriggers) throws SchedulerException {
        delegate.addCalendar(calName, calendar, replace, updateTriggers);
    }

    @Override
    public boolean deleteCalendar(String calName) throws SchedulerException {
        return delegate.deleteCalendar(calName);
    }

    @Override
    public Calendar getCalendar(String calName) throws SchedulerException {
        return delegate.getCalendar(calName);
    }

    @Override
    public List<String> getCalendarNames() throws SchedulerException {
        return delegate.getCalendarNames();
    }

    @Override
    public boolean interrupt(JobKey jobKey) throws UnableToInterruptJobException {
        return delegate.interrupt(jobKey);
    }

    @Override
    public boolean interrupt(String fireInstanceId) throws UnableToInterruptJobException {
        return delegate.interrupt(fireInstanceId);
    }

    @Override
    public boolean checkExists(JobKey jobKey) throws SchedulerException {
        return delegate.checkExists(jobKey);
    }

    @Override
    public boolean checkExists(TriggerKey triggerKey) throws SchedulerException {
        return delegate.checkExists(triggerKey);
    }

    @Override
    public void clear() throws SchedulerException {
        delegate.clear();
    }
}

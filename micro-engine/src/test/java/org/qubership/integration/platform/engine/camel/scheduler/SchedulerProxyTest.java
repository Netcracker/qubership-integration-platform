package org.qubership.integration.platform.engine.camel.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.spi.OperableTrigger;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SchedulerProxyTest {

    private SchedulerProxy proxy;

    @Mock
    Scheduler scheduler;

    @BeforeEach
    void setUp() {
        proxy = new SchedulerProxy(scheduler);
    }

    @Test
    void shouldDelayScheduleJobAndNotDelegateWhenScheduleJobCalled() throws Exception {
        JobDetail job = job("job-1");
        Trigger trigger = trigger("tr-1");

        Date result = proxy.scheduleJob(job, trigger);

        assertNotNull(result);
        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldCommitScheduledJobsByDelegatingAndClearing() throws Exception {
        JobDetail job1 = job("job-1");
        Trigger tr1 = trigger("tr-1");
        JobDetail job2 = job("job-2");
        Trigger tr2 = trigger("tr-2");

        proxy.scheduleJob(job1, tr1);
        proxy.scheduleJob(job2, tr2);

        proxy.commitScheduledJobs();

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Set> triggersCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Set.class);

        verify(scheduler, times(2)).scheduleJob(jobCaptor.capture(), triggersCaptor.capture(), eq(true));

        List<JobDetail> jobs = jobCaptor.getAllValues();
        List<Set> triggerSets = triggersCaptor.getAllValues();

        assertEquals(List.of(job1, job2), jobs);
        assertEquals(Set.of(tr1), triggerSets.get(0));
        assertEquals(Set.of(tr2), triggerSets.get(1));

        proxy.commitScheduledJobs();
        verifyNoMoreInteractions(scheduler);
    }

    @Test
    void shouldNotStartBeforeTakingLeadership() throws Exception {
        proxy.start();
        proxy.startDelayed(5);

        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldStartAndResumeAllWhenTakingLeadership() throws Exception {
        proxy.takeLeadership(null, Set.of());
        proxy.takeLeadership(null, Set.of());

        verify(scheduler, times(1)).start();
        verify(scheduler, times(1)).resumeAll();
    }

    @Test
    void shouldMoveTriggersPastTheirFireTimeToTheNextOneWhenTakingLeadership() throws Exception {
        Date now = new Date();
        OperableTrigger missed = cronTrigger("missed", "0/3 * * * * ?", new Date(now.getTime() - 60_000));
        OperableTrigger future = cronTrigger("future", "0/3 * * * * ?", new Date(now.getTime() + 60_000));
        triggersInStore(missed, future);

        proxy.takeLeadership(null, Set.of());

        ArgumentCaptor<Trigger> moved = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).rescheduleJob(eq(missed.getKey()), moved.capture());
        assertTrue(moved.getValue().getStartTime().after(now));
        assertTrue(moved.getValue().getStartTime().getTime() <= now.getTime() + 4_000);
        verify(scheduler, never()).rescheduleJob(eq(future.getKey()), any());
        InOrder inOrder = inOrder(scheduler);
        inOrder.verify(scheduler).rescheduleJob(eq(missed.getKey()), any());
        inOrder.verify(scheduler).start();
    }

    @Test
    void shouldRemoveATriggerWithNoFireTimeLeftWhenTakingLeadership() throws Exception {
        OperableTrigger expired = cronTrigger("expired", "0 0 12 1 1 ? 2020", Date.from(Instant.parse("2019-06-01T00:00:00Z")));
        triggersInStore(expired);

        proxy.takeLeadership(null, Set.of());

        verify(scheduler).unscheduleJob(expired.getKey());
        verify(scheduler, never()).rescheduleJob(any(), any());
        verify(scheduler).start();
    }

    @Test
    void shouldFireOnceATriggerOfThePreviousLeaderWhoseRecentFireTimeFellIntoTheGap() throws Exception {
        Date now = new Date();
        // Created when this pod started, after the fire time the previous leader missed.
        OperableTrigger trigger = cronTrigger("restarted", "0 * * * * ?", now);
        triggersInStore(trigger);

        proxy.takeLeadership(now.toInstant().minusSeconds(90), Set.of(trigger.getKey()));

        ArgumentCaptor<Trigger> moved = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).rescheduleJob(eq(trigger.getKey()), moved.capture());
        Date start = moved.getValue().getStartTime();
        assertFalse(start.after(now));
        assertTrue(start.getTime() > now.getTime() - 60_000);
        assertEquals(0, start.getTime() % 60_000);
    }

    @Test
    void shouldLeaveAnOldMissedFireTimeToTheMisfireInstruction() throws Exception {
        OperableTrigger trigger = cronTrigger("once", "0 0 12 1 1 ? 2025", Date.from(Instant.parse("2024-06-01T00:00:00Z")));
        triggersInStore(trigger);

        proxy.takeLeadership(Instant.parse("2024-12-31T00:00:00Z"), Set.of(trigger.getKey()));

        ArgumentCaptor<Trigger> moved = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).rescheduleJob(eq(trigger.getKey()), moved.capture());
        assertEquals(Instant.parse("2025-01-01T12:00:00Z"), moved.getValue().getStartTime().toInstant());
    }

    @Test
    void shouldNotFireATriggerThePreviousLeaderDidNotRun() throws Exception {
        Date now = new Date();
        OperableTrigger ownTrigger = cronTrigger("own", "0 * * * * ?", now);
        triggersInStore(ownTrigger);

        proxy.takeLeadership(now.toInstant().minusSeconds(90), Set.of(TriggerKey.triggerKey("previous", "default-group")));

        verify(scheduler, never()).rescheduleJob(any(), any());
        verify(scheduler).start();
    }

    @Test
    void shouldNotFireATriggerWhenThePreviousLeaderOutlivedItsLastFireTime() throws Exception {
        OperableTrigger trigger = cronTrigger("once", "0 0 12 1 1 ? 2025", Date.from(Instant.parse("2024-06-01T00:00:00Z")));
        triggersInStore(trigger);

        proxy.takeLeadership(Instant.parse("2025-06-01T00:00:00Z"), Set.of(trigger.getKey()));

        verify(scheduler).unscheduleJob(trigger.getKey());
        verify(scheduler, never()).rescheduleJob(any(), any());
    }

    @Test
    void shouldMoveMissedTriggersWhenResumingAfterABlueGreenSuspension() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(true);
        proxy.suspendScheduler();
        proxy.takeLeadership(null, Set.of());
        OperableTrigger missed = cronTrigger("missed", "0/3 * * * * ?", new Date(System.currentTimeMillis() - 60_000));
        triggersInStore(missed);

        proxy.resumeScheduler();

        verify(scheduler).rescheduleJob(eq(missed.getKey()), any());
        verify(scheduler).start();
    }

    @Test
    void shouldDelegateStartWhenLeaderAndNotSuspended() throws Exception {
        proxy.takeLeadership(null, Set.of());
        clearInvocations(scheduler);

        proxy.start();
        proxy.startDelayed(5);

        verify(scheduler).start();
        verify(scheduler).startDelayed(5);
    }

    @Test
    void shouldPutSchedulerIntoStandbyWhenReleasingLeadership() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(false);
        proxy.takeLeadership(null, Set.of());

        proxy.releaseLeadership();
        proxy.releaseLeadership();
        proxy.start();

        verify(scheduler, times(1)).standby();
        verify(scheduler, times(1)).start();
    }

    @Test
    void shouldStartOnlyOnceBothLeadingAndResumed() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(true);
        proxy.suspendScheduler();

        proxy.takeLeadership(null, Set.of());
        verify(scheduler, never()).start();

        proxy.resumeScheduler();
        verify(scheduler).start();
        verify(scheduler).resumeAll();
    }

    @Test
    void shouldNotStartWhenResumedWithoutLeadership() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(true);
        proxy.suspendScheduler();

        proxy.resumeScheduler();

        verify(scheduler, never()).start();
    }

    @Test
    void shouldNotDelegateStartWhenSuspended() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(false);

        proxy.suspendScheduler();
        proxy.start();

        verify(scheduler, never()).start();
        verify(scheduler).standby();
    }

    @Test
    void shouldNotDelegateStartDelayedWhenSuspended() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(false);

        proxy.suspendScheduler();
        proxy.startDelayed(5);

        verify(scheduler, never()).startDelayed(anyInt());
        verify(scheduler).standby();
    }

    @Test
    void shouldDoNothingWhenShutdownCalled() {
        proxy.shutdown(true);

        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldPutSchedulerIntoStandbyWhenSuspendingAndNotAlreadyInStandbyMode() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(false);

        proxy.suspendScheduler();
        proxy.suspendScheduler();

        verify(scheduler, times(1)).isInStandbyMode();
        verify(scheduler, times(1)).standby();
        verify(scheduler, never()).start();
        verify(scheduler, never()).resumeAll();
    }

    @Test
    void shouldNotCallStandbyWhenAlreadyInStandbyMode() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(true);

        proxy.suspendScheduler();

        verify(scheduler).isInStandbyMode();
        verify(scheduler, never()).standby();
    }

    @Test
    void shouldStartAndResumeAllWhenResumingAfterSuspend() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(false);
        proxy.takeLeadership(null, Set.of());
        clearInvocations(scheduler);

        proxy.suspendScheduler();
        proxy.resumeScheduler();

        InOrder inOrder = inOrder(scheduler);
        inOrder.verify(scheduler).isInStandbyMode();
        inOrder.verify(scheduler).standby();
        inOrder.verify(scheduler).start();
        inOrder.verify(scheduler).resumeAll();
    }

    @Test
    void shouldDoNothingWhenResumingAndNotSuspended() throws Exception {
        proxy.resumeScheduler();

        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldClearDelayedJobsWhenClearDelayedJobsCalled() throws Exception {
        proxy.scheduleJob(job("job-1"), trigger("tr-1"));

        proxy.clearDelayedJobs();
        proxy.commitScheduledJobs();

        verifyNoInteractions(scheduler);
    }

    private static OperableTrigger cronTrigger(String name, String cron, Date startAt) {
        OperableTrigger trigger = (OperableTrigger) TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey.triggerKey(name, "default-group"))
                .withSchedule(CronScheduleBuilder.cronSchedule(cron).inTimeZone(TimeZone.getTimeZone("UTC")))
                .startAt(startAt)
                .build();
        trigger.computeFirstFireTime(null);
        return trigger;
    }

    private void triggersInStore(Trigger... triggers) throws Exception {
        Set<TriggerKey> keys = new HashSet<>();
        for (Trigger trigger : triggers) {
            keys.add(trigger.getKey());
            when(scheduler.getTrigger(trigger.getKey())).thenReturn(trigger);
        }
        when(scheduler.getTriggerKeys(any())).thenReturn(keys);
    }

    private JobDetail job(String name) {
        return mock(JobDetail.class, name);
    }

    private Trigger trigger(String name) {
        return mock(Trigger.class, name);
    }
}

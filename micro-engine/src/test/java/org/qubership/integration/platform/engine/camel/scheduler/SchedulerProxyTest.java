package org.qubership.integration.platform.engine.camel.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SchedulerProxyTest {

    @Mock
    Scheduler scheduler;

    private SchedulerProxy proxy;

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
    void shouldDelegateStartWhenNotSuspended() throws Exception {
        proxy.start();

        verify(scheduler).start();
    }

    @Test
    void shouldDelegateStartDelayedWhenNotSuspended() throws Exception {
        proxy.startDelayed(5);

        verify(scheduler).startDelayed(5);
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

    private JobDetail job(String name) {
        return mock(JobDetail.class, name);
    }

    private Trigger trigger(String name) {
        return mock(Trigger.class, name);
    }
}

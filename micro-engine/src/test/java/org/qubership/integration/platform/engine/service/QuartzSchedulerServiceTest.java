package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.State;
import org.apache.camel.Route;
import org.apache.camel.component.file.remote.SftpConsumer;
import org.apache.camel.component.quartz.QuartzEndpoint;
import org.apache.camel.pollconsumer.quartz.QuartzScheduledPollConsumerScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.*;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.namespaceVersion;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QuartzSchedulerServiceTest {

    private QuartzSchedulerService service;

    @Mock
    Scheduler scheduler;

    @Mock
    BlueGreenStateService blueGreenStateService;

    @BeforeEach
    void setUp() {
        service = createService(State.ACTIVE);
    }

    @Test
    void shouldDeleteJobsWhenRemoveSchedulerJobsAndListNotEmpty() throws Exception {
        List<JobKey> jobs = List.of(JobKey.jobKey("j1", "g1"));
        when(scheduler.deleteJobs(jobs)).thenReturn(true);

        service.removeSchedulerJobs(jobs);

        verify(scheduler).deleteJobs(jobs);
    }

    @Test
    void shouldNotDeleteJobsWhenRemoveSchedulerJobsAndListEmpty() {
        service.removeSchedulerJobs(List.of());

        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldSwallowExceptionWhenRemoveSchedulerJobsThrows() throws Exception {
        List<JobKey> jobs = List.of(JobKey.jobKey("j1", "g1"));
        when(scheduler.deleteJobs(jobs)).thenThrow(new SchedulerException("boom"));

        assertDoesNotThrow(() -> service.removeSchedulerJobs(jobs));

        verify(scheduler).deleteJobs(jobs);
    }

    @Test
    void shouldDeleteRouteSchedulerJobsFromQuartzEndpointAndSftpConsumer() throws Exception {
        JobKey endpointJobKey = JobKey.jobKey("endpoint-trigger", "endpoint-group");
        JobKey consumerJobKey = JobKey.jobKey("consumer-job", "consumer-group");
        QuartzEndpoint endpoint = quartzEndpointWithTrigger(endpointJobKey.getName(), endpointJobKey.getGroup());
        SftpConsumer consumer = sftpConsumerWithQuartzSchedulerJob(consumerJobKey);
        Route route = mock(Route.class);
        when(route.getEndpoint()).thenReturn(endpoint);
        when(route.getConsumer()).thenReturn(consumer);

        service.removeSchedulerJobs(route);

        verify(scheduler).deleteJobs(List.of(endpointJobKey, consumerJobKey));
    }

    @Test
    void shouldCommitScheduledJobsByDelegatingToQuartzScheduler() throws Exception {
        JobDetail job1 = job("job-1", "grp-1");
        Trigger trigger1 = mock(Trigger.class);
        JobDetail job2 = job("job-2", "grp-2");
        Trigger trigger2 = mock(Trigger.class);

        service.getSchedulerProxy().scheduleJob(job1, trigger1);
        service.getSchedulerProxy().scheduleJob(job2, trigger2);

        service.commitScheduledJobs();

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Set> triggersCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);

        verify(scheduler, times(2)).scheduleJob(jobCaptor.capture(), triggersCaptor.capture(), eq(true));

        List<JobDetail> jobs = jobCaptor.getAllValues();
        List<Set> triggerSets = triggersCaptor.getAllValues();

        assertEquals(List.of(job1, job2), jobs);
        assertEquals(Set.of(trigger1), triggerSets.get(0));
        assertEquals(Set.of(trigger2), triggerSets.get(1));

        service.commitScheduledJobs();
        verifyNoMoreInteractions(scheduler);
    }

    @Test
    void shouldClearDelayedJobsWhenResetSchedulersProxyCalled() throws SchedulerException {
        JobDetail job1 = job("job-1", "grp-1");
        Trigger trigger1 = mock(Trigger.class);

        service.getSchedulerProxy().scheduleJob(job1, trigger1);

        service.resetSchedulersProxy();
        service.commitScheduledJobs();

        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldSuspendAndResumeAllSchedulers() throws Exception {
        when(scheduler.isInStandbyMode()).thenReturn(false);

        service.suspendAllSchedulers();
        service.resumeAllSchedulers();

        InOrder inOrder = inOrder(scheduler);
        inOrder.verify(scheduler).isInStandbyMode();
        inOrder.verify(scheduler).standby();
        inOrder.verify(scheduler).start();
        inOrder.verify(scheduler).resumeAll();
    }

    @Test
    void shouldCreateSuspendedSchedulerWhenPodStartsOutsideActive() throws Exception {
        QuartzSchedulerService candidateService = createService(State.CANDIDATE);

        candidateService.getSchedulerProxy().start();

        verify(scheduler, never()).start();
    }

    @Test
    void shouldCreateRunningSchedulerWhenPodStartsActive() throws Exception {
        service.getSchedulerProxy().start();

        verify(scheduler).start();
    }

    private QuartzSchedulerService createService(State state) {
        when(blueGreenStateService.getBlueGreenState())
                .thenReturn(new BlueGreenState(namespaceVersion(state, 1), OffsetDateTime.parse("2026-06-20T09:00:00Z")));
        return new QuartzSchedulerService(scheduler, blueGreenStateService);
    }

    private QuartzEndpoint quartzEndpointWithTrigger(String triggerName, String groupName) {
        QuartzEndpoint endpoint = mock(QuartzEndpoint.class);
        when(endpoint.getTriggerKey()).thenReturn(TriggerKey.triggerKey(triggerName, groupName));
        return endpoint;
    }

    private SftpConsumer sftpConsumerWithQuartzSchedulerJob(JobKey jobKey) throws Exception {
        SftpConsumer consumer = mock(SftpConsumer.class);

        QuartzScheduledPollConsumerScheduler quartzScheduler = new QuartzScheduledPollConsumerScheduler();
        setPrivateField(quartzScheduler, job(jobKey.getName(), jobKey.getGroup()));

        when(consumer.getScheduler()).thenReturn(quartzScheduler);
        return consumer;
    }

    private JobDetail job(String name, String group) {
        return JobBuilder.newJob(DummyJob.class).withIdentity(name, group).build();
    }

    private void setPrivateField(Object target, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField("job");
        f.setAccessible(true);
        f.set(target, value);
    }

    static class DummyJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }
}

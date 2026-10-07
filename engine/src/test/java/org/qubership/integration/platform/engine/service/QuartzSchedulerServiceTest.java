package org.qubership.integration.platform.engine.service;

import org.apache.camel.spring.SpringCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.SchedulerConfigException;
import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerFactoryProxy;
import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerProxy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuartzSchedulerServiceTest {

    private static final int THREADS = 3;

    private StdSchedulerFactoryProxy factory;
    private StdSchedulerProxy scheduler;
    private QuartzSchedulerService service;

    @BeforeEach
    void setUp() {
        factory = mock(StdSchedulerFactoryProxy.class);
        scheduler = mock(StdSchedulerProxy.class);
        service = new QuartzSchedulerService(factory);
    }

    // Parallel first calls to StdSchedulerFactory.getScheduler() used to build several schedulers,
    // and the cleanup of the extra ones closed the connection pool of the one that stayed registered.
    @Test
    void neverCallsTheFactoryFromTwoThreadsAtOnce() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch allArrived = new CountDownLatch(THREADS);
        when(factory.getScheduler()).thenAnswer(invocation -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            allArrived.countDown();
            allArrived.await(1, TimeUnit.SECONDS);
            inside.decrementAndGet();
            return scheduler;
        });

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> calls = List.of(
                    executor.submit(service::commitScheduledJobs),
                    executor.submit(service::resetSchedulersProxy),
                    executor.submit(() -> service.removeSchedulerJobs(List.of(JobKey.jobKey("job", "group")))));
            for (Future<?> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, maxInside.get());
        verify(scheduler).commitScheduledJobs();
        verify(scheduler).clearDelayedJobs();
        verify(scheduler).deleteJobs(List.of(JobKey.jobKey("job", "group")));
    }

    // A broken Quartz configuration fails the call that needs the scheduler, not the engine startup,
    // and the next call tries again.
    @Test
    void retriesTheFactoryAfterAFailedCall() throws Exception {
        when(factory.getScheduler())
                .thenThrow(new SchedulerConfigException("Thread count must be > 0"))
                .thenReturn(scheduler);
        service = new QuartzSchedulerService(factory);

        service.commitScheduledJobs();
        service.commitScheduledJobs();

        verify(scheduler).commitScheduledJobs();
    }

    @Test
    void deletesTheJobsOfContextsThroughTheScheduler() throws Exception {
        when(factory.getScheduler()).thenReturn(scheduler);
        SpringCamelContext context = mock(SpringCamelContext.class);

        service.removeSchedulerJobsFromContext(context);
        service.removeSchedulerJobsFromContexts(List.of(context));

        verify(scheduler, times(2)).deleteJobs(List.of());
    }

    // Both methods used to cast the factory to StdSchedulerProxy and throw ClassCastException.
    @Test
    void suspendsAndResumesTheScheduler() throws Exception {
        when(factory.getScheduler()).thenReturn(scheduler);

        service.suspendAllSchedulers();
        service.resumeAllSchedulers();

        verify(scheduler).suspendScheduler();
        verify(scheduler).resumeScheduler();
    }
}

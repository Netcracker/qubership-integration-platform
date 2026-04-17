package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentThreadPoolConfigurationTest {

    private final DeploymentThreadPoolConfiguration configuration = new DeploymentThreadPoolConfiguration();

    @Test
    void shouldCreateScheduledThreadPoolExecutorWithConfiguredSizes() {
        Executor executor = configuration.deploymentExecutor(3, 5);

        ScheduledThreadPoolExecutor threadPoolExecutor =
                assertInstanceOf(ScheduledThreadPoolExecutor.class, executor);

        assertEquals(3, threadPoolExecutor.getCorePoolSize());
        assertEquals(5, threadPoolExecutor.getMaximumPoolSize());

        threadPoolExecutor.shutdownNow();
    }

    @Test
    void shouldCreateThreadsWithDeploymentNamePrefix() throws Exception {
        ScheduledThreadPoolExecutor executor =
                (ScheduledThreadPoolExecutor) configuration.deploymentExecutor(1, 1);

        AtomicReference<String> threadName = new AtomicReference<>();
        Future<?> future = executor.submit(() -> threadName.set(Thread.currentThread().getName()));

        future.get(5, TimeUnit.SECONDS);

        assertTrue(threadName.get().startsWith("deployment-"));

        executor.shutdownNow();
    }
}

package org.qubership.integration.platform.engine.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.consul.updates.UpdateGetterHelper;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// ChainRuntimePropertiesListener runs this read before the routes start, so a failure must not stop the engine.
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TasksSchedulerTest {

    @Mock
    private UpdateGetterHelper<Map<String, ChainRuntimeProperties>> runtimePropertiesGetter;

    @Mock
    private ChainRuntimePropertiesService runtimePropertiesService;

    private final TasksScheduler scheduler = new TasksScheduler();

    @BeforeEach
    void setUp() {
        scheduler.chainRuntimePropertiesUpdateGetter = runtimePropertiesGetter;
        scheduler.chainRuntimePropertiesService = runtimePropertiesService;
    }

    @Test
    void shouldNotThrowWhenRuntimePropertiesReadFails() {
        doThrow(new RuntimeException("Consul is unreachable")).when(runtimePropertiesGetter).checkForUpdates(any());

        assertDoesNotThrow(scheduler::checkRuntimeDeploymentProperties);
    }
}

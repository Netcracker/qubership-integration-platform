package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.namespaceVersion;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class BlueGreenSchedulerControllerServiceTest {

    private static final OffsetDateTime UPDATE_TIME = OffsetDateTime.parse("2026-06-20T09:00:00Z");

    private BlueGreenSchedulerControllerService service;

    @Mock
    private QuartzSchedulerService quartzSchedulerService;

    @BeforeEach
    void setUp() {
        service = new BlueGreenSchedulerControllerService();
        service.quartzSchedulerService = quartzSchedulerService;
    }

    @Test
    void shouldSuspendAllSchedulersWhenStateChangesFromActiveToInactive() {
        service.onStateChange(stateChange(state(State.ACTIVE), state(State.IDLE)));

        verify(quartzSchedulerService).suspendAllSchedulers();
        verifyNoMoreInteractions(quartzSchedulerService);
    }

    @Test
    void shouldResumeAllSchedulersWhenStateChangesFromInactiveToActive() {
        service.onStateChange(stateChange(state(State.IDLE), state(State.ACTIVE)));

        verify(quartzSchedulerService).resumeAllSchedulers();
        verifyNoMoreInteractions(quartzSchedulerService);
    }

    @Test
    void shouldSkipSchedulerChangesWhenPreviousStateIsNull() {
        service.onStateChange(stateChange(null, state(State.ACTIVE)));

        verifyNoInteractions(quartzSchedulerService);
    }

    @Test
    void shouldKeepSchedulersUnchangedWhenActivityDoesNotChange() {
        service.onStateChange(stateChange(state(State.ACTIVE), state(State.ACTIVE)));
        service.onStateChange(stateChange(state(State.IDLE), state(State.CANDIDATE)));

        verifyNoInteractions(quartzSchedulerService);
    }

    private static BlueGreenStateService.BlueGreenStateChange stateChange(
            BlueGreenState oldState,
            BlueGreenState newState) {
        return new BlueGreenStateService.BlueGreenStateChange(oldState, newState);
    }

    private static BlueGreenState state(State state) {
        return new BlueGreenState(namespaceVersion(state, 1), UPDATE_TIME);
    }
}

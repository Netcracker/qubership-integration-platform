package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.OffsetDateTime;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.engine.testutils.KafkaBlueGreenTestUtils.namespaceVersion;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class BlueGreenStateServiceTest {

    private static final OffsetDateTime UPDATE_TIME = OffsetDateTime.parse("2026-06-20T09:00:00Z");

    private BlueGreenStateService service;

    @Mock
    private BlueGreenStatePublisher blueGreenStatePublisher;
    @Mock
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        service = new BlueGreenStateService();
        service.blueGreenStatePublisher = blueGreenStatePublisher;
        service.eventBus = eventBus;
    }

    @Test
    void shouldSubscribeToPublisherWhenInitialized() {
        service.init();

        verify(blueGreenStatePublisher).subscribe(any());
        verifyNoInteractions(eventBus);
    }

    @Test
    void shouldPublishStateChangeWhenPublisherReportsState() {
        BlueGreenState activeState = state(State.ACTIVE, 1);
        service.init();

        stateConsumer().accept(activeState);

        ArgumentCaptor<BlueGreenStateService.BlueGreenStateChange> stateChangeCaptor =
                ArgumentCaptor.forClass(BlueGreenStateService.BlueGreenStateChange.class);
        verify(eventBus).publish(eq(BlueGreenStateService.BLUE_GREEN_STATE_EVENT_ADDRESS), stateChangeCaptor.capture());
        assertSame(activeState, stateChangeCaptor.getValue().newState());
    }

    @Test
    void shouldReturnStateDetailsFromPublisher() {
        BlueGreenState activeState = state(State.ACTIVE, 2);
        when(blueGreenStatePublisher.getBlueGreenState()).thenReturn(activeState);

        assertSame(activeState, service.getBlueGreenState());
        assertEquals(activeState.getCurrent().getVersion().value(), service.getBlueGreenVersion());
        assertEquals(State.ACTIVE, service.getBlueGreenStateValue());
    }

    @Test
    void shouldDetectActiveState() {
        assertTrue(BlueGreenStateService.isActive(state(State.ACTIVE, 1)));
        assertFalse(BlueGreenStateService.isActive(state(State.IDLE, 1)));
        assertFalse(BlueGreenStateService.isActive(null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Consumer<BlueGreenState> stateConsumer() {
        ArgumentCaptor<Consumer> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(blueGreenStatePublisher).subscribe(consumerCaptor.capture());
        return consumerCaptor.getValue();
    }

    private static BlueGreenState state(State state, int version) {
        return new BlueGreenState(namespaceVersion(state, version), UPDATE_TIME);
    }
}

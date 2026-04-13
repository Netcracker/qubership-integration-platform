package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.quarkus.runtime.Startup;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.events.BlueGreenInitialStateReceivedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

@Slf4j
@ApplicationScoped
public class BlueGreenStateService {
    @Inject
    BlueGreenStatePublisher blueGreenStatePublisher;

    @Inject
    EventBus eventBus;

    // <last_state, new_state>
    private final List<BiConsumer<BlueGreenState, BlueGreenState>> callbacks = new ArrayList<>();
    private BlueGreenState lastState = null;

    @Startup
    public void onApplicationStarted() {
        blueGreenStatePublisher.subscribe(this::updateState);
    }

    public void subscribe(BiConsumer<BlueGreenState, BlueGreenState> callback) {
        callbacks.add(callback);
    }

    public static boolean isActive(BlueGreenState state) {
        return state != null && state.getCurrent() != null && state.getCurrent().getState() == State.ACTIVE;
    }

    public BlueGreenState getBlueGreenState() {
        return blueGreenStatePublisher.getBlueGreenState();
    }

    public String getBlueGreenVersion() {
        return blueGreenStatePublisher.getBlueGreenState().getCurrent().getVersion().value();
    }

    public State getBlueGreenStateValue() {
        return blueGreenStatePublisher.getBlueGreenState().getCurrent().getState();
    }

    private synchronized void updateState(BlueGreenState newState) {
        boolean firstUpdate = lastState == null;
        log.info("BG state changed: {}, is initial: {}", newState, firstUpdate);

        final BlueGreenState lastStateFinal = this.lastState;
        this.lastState = newState;
        new Thread(() -> callbacks.forEach(subscriber -> subscriber.accept(lastStateFinal, newState))).start();

        if (firstUpdate) {
            eventBus.publish(UpdateEvent.EVENT_ADDRESS, new BlueGreenInitialStateReceivedEvent(this));
        }
    }
}

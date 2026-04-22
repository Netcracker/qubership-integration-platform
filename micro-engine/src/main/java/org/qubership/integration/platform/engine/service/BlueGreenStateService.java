package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.quarkus.arc.Unremovable;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@Unremovable
public class BlueGreenStateService {
    public static final String BLUE_GREEN_STATE_EVENT_ADDRESS = "blue-green-state-event";

    public record BlueGreenStateChange(BlueGreenState oldState, BlueGreenState newState) {}

    @Inject
    BlueGreenStatePublisher blueGreenStatePublisher;

    @Inject
    EventBus eventBus;

    private BlueGreenState lastState = null;

    @PostConstruct
    public void init() {
        blueGreenStatePublisher.subscribe(this::updateState);
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

    private synchronized void updateState(BlueGreenState newState) {
        boolean firstUpdate = lastState == null;
        log.info("BG state changed: {}, is initial: {}", newState, firstUpdate);
        this.lastState = newState;

        BlueGreenStateChange stateChange = new BlueGreenStateChange(lastState, newState);
        eventBus.publish(BLUE_GREEN_STATE_EVENT_ADDRESS, stateChange);
    }
}

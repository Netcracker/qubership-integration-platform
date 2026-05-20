package org.qubership.integration.platform.engine.service;

import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.events.BlueGreenInitialStateReceivedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

@Slf4j
@Component
public class BlueGreenStateService {

    private final BlueGreenStatePublisher blueGreenStatePublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    // <last_state, new_state>
    private final List<BiConsumer<BlueGreenState, BlueGreenState>> callbacks = new ArrayList<>();
    private BlueGreenState lastState = null;

    @Autowired
    public BlueGreenStateService(BlueGreenStatePublisher blueGreenStatePublisher,
                                 ApplicationEventPublisher applicationEventPublisher) {
        this.blueGreenStatePublisher = blueGreenStatePublisher;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Async
    @EventListener
    public void onApplicationStarted(ApplicationStartedEvent event) {
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

    /**
     * @return bg version in format 'v1'
     */
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
            applicationEventPublisher.publishEvent(new BlueGreenInitialStateReceivedEvent(this));
        }
    }
}

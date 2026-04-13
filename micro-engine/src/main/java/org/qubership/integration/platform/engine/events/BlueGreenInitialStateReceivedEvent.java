package org.qubership.integration.platform.engine.events;

public class BlueGreenInitialStateReceivedEvent extends UpdateEvent {
    public BlueGreenInitialStateReceivedEvent(Object source) {
        super(source, true);
    }
}

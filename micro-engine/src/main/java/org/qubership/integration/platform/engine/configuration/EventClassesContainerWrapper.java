package org.qubership.integration.platform.engine.configuration;

import lombok.Getter;
import org.qubership.integration.platform.engine.events.UpdateEvent;

import java.util.Collection;

@Getter
public class EventClassesContainerWrapper {
    private final Collection<Class<? extends UpdateEvent>> eventClasses;

    public EventClassesContainerWrapper(Collection<Class<? extends UpdateEvent>> eventClasses) {
        this.eventClasses = eventClasses;
    }
}

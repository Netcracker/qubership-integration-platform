package org.qubership.integration.platform.engine.camel.dsl.notification;

import io.quarkus.arc.All;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.spi.Resource;

import java.util.List;

@Unremovable
@ApplicationScoped
public class SourceProcessingNotifier {
    @Inject
    @All
    List<SourceProcessingListener> listeners;

    public void notifySourceProcessingStarted(Resource resource) {
        listeners.forEach(listener -> listener.onSourceProcessingStart(resource));
    }

    public void notifySourceLoaded(Resource resource) {
        listeners.forEach(listener -> listener.onSourceLoaded(resource));
    }

    public void notifySourceLoadFailed(Resource resource, Exception exception) {
        listeners.forEach(listener -> listener.onSourceLoadFailed(resource, exception));
    }
}

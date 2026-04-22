package org.qubership.integration.platform.engine.camel.dsl.notification;

import org.apache.camel.spi.Resource;

public interface SourceProcessingListener {
    void onSourceProcessingStart(Resource resource);

    void onSourceLoaded(Resource resource);

    void onSourceLoadFailed(Resource resource, Exception exception);
}

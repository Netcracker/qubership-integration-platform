package org.qubership.integration.platform.engine.metadata.builders;

import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;

public class RouteRegistrationInfoBuilder {
    private final RouteRegistrationInfo.RouteRegistrationInfoBuilder delegate;

    public RouteRegistrationInfoBuilder() {
        delegate = RouteRegistrationInfo.builder();
    }

    public RouteRegistrationInfoBuilder snapshotId(String snapshotId) {
        delegate.snapshotId(snapshotId);
        return this;
    }

    public RouteRegistrationInfoBuilder path(String path) {
        delegate.path(path);
        return this;
    }

    public RouteRegistrationInfoBuilder gatewayPrefix(String gatewayPrefix) {
        delegate.gatewayPrefix(gatewayPrefix);
        return this;
    }

    public RouteRegistrationInfoBuilder variableName(String variableName) {
        delegate.variableName(variableName);
        return this;
    }

    public RouteRegistrationInfoBuilder type(RouteType type) {
        delegate.type(type);
        return this;
    }

    public RouteRegistrationInfoBuilder connectTimeout(Long connectTimeout) {
        delegate.connectTimeout(connectTimeout);
        return this;
    }

    public RouteRegistrationInfo build() {
        return delegate.build();
    }
}

package org.qubership.integration.platform.engine.camel.components.grpc;

import org.apache.camel.Producer;
import org.apache.camel.component.grpc.GrpcComponent;
import org.apache.camel.component.grpc.GrpcConfiguration;
import org.apache.camel.component.grpc.GrpcEndpoint;

public class GrpcCustomEndpoint extends GrpcEndpoint {
    public GrpcCustomEndpoint(String uri, GrpcComponent component, GrpcConfiguration config) {
        super(uri, component, config);
    }

    @Override
    public Producer createProducer() {
        return new GrpcCustomProducer(this);
    }
}

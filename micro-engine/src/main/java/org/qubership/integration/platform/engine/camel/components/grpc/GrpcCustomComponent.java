package org.qubership.integration.platform.engine.camel.components.grpc;

import org.apache.camel.Endpoint;
import org.apache.camel.component.grpc.GrpcComponent;
import org.apache.camel.component.grpc.GrpcEndpoint;
import org.apache.camel.spi.annotations.Component;

import java.util.Map;

/**
 * The {@code grpc} name in {@code @Component} selects the property configurers camel-grpc generates for its endpoint.
 */
@Component("grpc")
public class GrpcCustomComponent extends GrpcComponent {
    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        try (GrpcEndpoint endpoint = (GrpcEndpoint) super.createEndpoint(uri, remaining, parameters)) {
            return new GrpcCustomEndpoint(uri, this, endpoint.getConfiguration());
        }
    }
}

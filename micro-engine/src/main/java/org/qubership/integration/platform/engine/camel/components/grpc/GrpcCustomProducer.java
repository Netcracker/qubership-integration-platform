package org.qubership.integration.platform.engine.camel.components.grpc;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProducer;
import org.apache.camel.Exchange;
import org.apache.camel.component.grpc.GrpcComponent;
import org.apache.camel.component.grpc.GrpcEndpoint;
import org.apache.camel.support.DefaultAsyncProducer;
import org.apache.camel.support.service.ServiceHelper;
import org.qubership.integration.platform.engine.util.GrpcProcessorUtils;

/**
 * Creates the stock gRPC producer on the first exchange, against a copy of the endpoint whose context resolves the
 * stub class through the chain's class resolver. The chain's routes get that resolver only after their producers
 * have started, and {@link GrpcProcessorUtils} builds the request with it.
 */
public class GrpcCustomProducer extends DefaultAsyncProducer {
    private final GrpcCustomEndpoint endpoint;
    private AsyncProducer delegate;
    private Exception startFailure;

    public GrpcCustomProducer(GrpcCustomEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        AsyncProducer producer;
        try {
            producer = getDelegate(exchange);
        } catch (Exception exception) {
            exchange.setException(exception);
            callback.done(true);
            return true;
        }
        return producer.process(exchange, callback);
    }

    private synchronized AsyncProducer getDelegate(Exchange exchange) throws Exception {
        if (startFailure != null) {
            throw startFailure;
        }
        if (delegate == null) {
            try {
                delegate = createDelegate(exchange);
            } catch (Exception exception) {
                // Not retried: the stock producer does not close the channel of a failed start.
                startFailure = exception;
                throw exception;
            }
        }
        return delegate;
    }

    private AsyncProducer createDelegate(Exchange exchange) throws Exception {
        GrpcEndpoint chainEndpoint = new GrpcEndpoint(endpoint.getEndpointUri(),
                (GrpcComponent) endpoint.getComponent(), endpoint.getConfiguration());
        chainEndpoint.setCamelContext(GrpcProcessorUtils.getContextWithChainClassResolver(exchange));
        ServiceHelper.startService(chainEndpoint);
        AsyncProducer producer = chainEndpoint.createAsyncProducer();
        ServiceHelper.startService(producer);
        return producer;
    }

    @Override
    protected synchronized void doStop() throws Exception {
        ServiceHelper.stopService(delegate);
        delegate = null;
        startFailure = null;
        super.doStop();
    }
}

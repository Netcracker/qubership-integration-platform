package org.qubership.integration.platform.engine.camel.components.grpc;

import io.grpc.ClientInterceptor;
import io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor;
import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProducer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.component.grpc.GrpcComponent;
import org.apache.camel.component.grpc.GrpcConfiguration;
import org.apache.camel.component.grpc.GrpcEndpoint;
import org.apache.camel.support.DefaultAsyncProducer;
import org.apache.camel.support.service.ServiceHelper;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.util.GrpcProcessorUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates the stock gRPC producer on the first exchange, against a copy of the endpoint whose context resolves the
 * stub class through the chain's class resolver. The chain's routes get that resolver only after their producers
 * have started, and {@link GrpcProcessorUtils} builds the request with it.
 *
 * <p>The copy has a configuration of its own, so that its channel carries the metric interceptor of the element that
 * makes the call and no other element's.
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
                (GrpcComponent) endpoint.getComponent(), createConfiguration(exchange));
        chainEndpoint.setCamelContext(GrpcProcessorUtils.getContextWithChainClassResolver(exchange));
        ServiceHelper.startService(chainEndpoint);
        AsyncProducer producer = chainEndpoint.createAsyncProducer();
        ServiceHelper.startService(producer);
        return producer;
    }

    private GrpcConfiguration createConfiguration(Exchange exchange) throws Exception {
        GrpcConfiguration configuration;
        try (Endpoint parsed = endpoint.getComponent().createEndpoint(endpoint.getEndpointUri())) {
            configuration = ((GrpcEndpoint) parsed).getConfiguration();
        }
        if (configuration.isAutoDiscoverClientInterceptors()) {
            configuration.setClientInterceptors(
                    getElementInterceptors(configuration.getClientInterceptors(), exchange));
        }
        return configuration;
    }

    // Discovery collects every ClientInterceptor in the domain's registry, the metric interceptor of each gRPC element
    // of each chain among them.
    private static List<ClientInterceptor> getElementInterceptors(
            List<ClientInterceptor> interceptors,
            Exchange exchange
    ) {
        List<ClientInterceptor> result = new ArrayList<>(interceptors.stream()
                .filter(interceptor -> !(interceptor instanceof MetricCollectingClientInterceptor))
                .toList());
        // The template runs each element inside a step whose id is the element's id,
        // and binds the element's interceptor under that id.
        String elementId = DebuggerUtils.getStepChainElementId(exchange.getProperty(Exchange.STEP_ID, String.class));
        MetricCollectingClientInterceptor metrics = exchange.getContext().getRegistry()
                .lookupByNameAndType(elementId, MetricCollectingClientInterceptor.class);
        if (metrics != null) {
            result.add(metrics);
        }
        return result;
    }

    @Override
    protected synchronized void doStop() throws Exception {
        ServiceHelper.stopService(delegate);
        delegate = null;
        startFailure = null;
        super.doStop();
    }
}

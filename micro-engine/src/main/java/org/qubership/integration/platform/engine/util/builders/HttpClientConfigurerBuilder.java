package org.qubership.integration.platform.engine.util.builders;

import io.micrometer.core.instrument.binder.httpcomponents.hc5.MicrometerHttpClientInterceptor;
import jakarta.enterprise.inject.spi.CDI;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.qubership.integration.platform.engine.service.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.testing.EndpointInfo;
import org.qubership.integration.platform.engine.service.testing.TestingService;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.net.URISyntaxException;
import java.util.Optional;

import static java.util.Objects.nonNull;

@Slf4j
public class HttpClientConfigurerBuilder {
    private String cId;
    private String cName;
    private String eId;
    private String eName;
    private String path;
    private Boolean reuseEstablishedConnection;
    private String protocol;

    public HttpClientConfigurerBuilder() {
        cId = "";
        cName = "";
        eId = "";
        eName = "";
    }

    public HttpClientConfigurerBuilder chainId(String value) {
        cId = value;
        return this;
    }

    public HttpClientConfigurerBuilder chainName(String value) {
        cName = value;
        return this;
    }

    public HttpClientConfigurerBuilder elementId(String value) {
        eId = value;
        return this;
    }

    public HttpClientConfigurerBuilder elementName(String value) {
        eName = value;
        return this;
    }

    public HttpClientConfigurerBuilder operationPath(String value) {
        path = value;
        return this;
    }

    public HttpClientConfigurerBuilder reuseEstablishedConnection(Boolean reuseEstablishedConnection) {
        this.reuseEstablishedConnection = reuseEstablishedConnection;
        return this;
    }

    public HttpClientConfigurerBuilder protocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    public HttpClientConfigurer build() {
        MetricTagsHelper metricTagsHelper = CDI.current().select(MetricTagsHelper.class).get();
        MetricsStore metricsStore = CDI.current().select(MetricsStore.class).get();
        Optional<TestingService> testingService = InjectUtil.injectOptional(CDI.current().select(TestingService.class));

        EndpointInfo endpointInfo = EndpointInfo.builder()
                .elementId(eId)
                .path(path)
                .protocol(protocol)
                .build();

        return clientBuilder -> {
            if (metricsStore.isMetricsEnabled()) {
                MicrometerHttpClientInterceptor interceptor = new MicrometerHttpClientInterceptor(
                        metricsStore.getMeterRegistry(),
                        request -> {
                            try {
                                return nonNull(path)
                                        ? path
                                        : request.getUri().toString();
                            } catch (URISyntaxException e) {
                                log.error("Failed to get URI from request");
                                return "";
                            }
                        },
                        metricTagsHelper.buildMetricTags(cId, cName, eId, eName),
                        true
                );
                clientBuilder.addRequestInterceptorFirst(interceptor.getRequestInterceptor());
                clientBuilder.addResponseInterceptorLast(interceptor.getResponseInterceptor());
            }

            testingService.ifPresent(s -> {
                if (s.canBeMocked(endpointInfo)) {
                    HttpRequestInterceptor endpointMockInterceptor =
                            s.buildEndpointMockInterceptor(cId, endpointInfo);
                    clientBuilder.addRequestInterceptorFirst(endpointMockInterceptor);
                    clientBuilder.setRoutePlanner(s.buildRoutePlanner(cId, endpointInfo));
                }
            });

            // enable or disable connection reuse, depends on element property
            if (nonNull(reuseEstablishedConnection) && !reuseEstablishedConnection) {
                clientBuilder.setConnectionReuseStrategy(
                        (HttpRequest request, HttpResponse response, HttpContext httpContext) -> false);
            }

            // disable automatic retries on error
            clientBuilder.disableAutomaticRetries();
        };
    }
}

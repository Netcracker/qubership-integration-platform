package org.qubership.integration.platform.engine.service.testing;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.net.URIAuthority;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

@Component
@ConditionalOnProperty(value = "qip.testing.enabled", havingValue = "true")
public class EndpointMockTestingService implements TestingService {

    private static final String MOCK_CALL_PATH = "/api/v1/endpoint-mocks/call";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private final HttpHost testingServiceHost;

    public EndpointMockTestingService(@Value("${qip.testing.address}") String address) {
        this.testingServiceHost = resolveHost(address);
    }

    @Override
    public boolean canBeMocked(ElementProperties properties) {
        String elementId = property(properties, ChainProperties.ELEMENT_ID);
        return elementId != null && !elementId.isBlank();
    }

    @Override
    public HttpRequestInterceptor buildEndpointMockInterceptor(String chainId, ElementProperties elementProperties) {
        // The design-time element id, not ElementProperties.getElementId(), which changes with every snapshot.
        String elementId = property(elementProperties, ChainProperties.ELEMENT_ID);
        String operationPath = property(elementProperties, ChainProperties.OPERATION_PATH);
        return (request, entity, context) -> {
            String requestTarget = request.getPath() == null ? "/" : request.getPath();
            TestingContext testingContext =
                    new TestingContext(chainId, elementId, operationPath, requestTarget);
            request.setHeader(TestingContext.HEADER_NAME, testingContext.encode());
            request.setScheme(testingServiceHost.getSchemeName());
            request.setAuthority(
                    new URIAuthority(testingServiceHost.getHostName(), testingServiceHost.getPort()));
            request.setPath(mockCallTarget(requestTarget));
        };
    }

    @Override
    public HttpRoutePlanner buildRoutePlanner(String chainId, ElementProperties elementProperties) {
        boolean secure = HTTPS_SCHEME.equals(testingServiceHost.getSchemeName());
        return (target, context) -> new HttpRoute(testingServiceHost, null, secure);
    }

    // The query is kept on the wire for readable logs only; the testing service reads it from the context header.
    private static String mockCallTarget(String requestTarget) {
        int queryStart = requestTarget.indexOf('?');
        return queryStart < 0 ? MOCK_CALL_PATH : MOCK_CALL_PATH + requestTarget.substring(queryStart);
    }

    private static String property(ElementProperties elementProperties, String name) {
        if (elementProperties == null) {
            return null;
        }
        Map<String, String> properties = elementProperties.getProperties();
        return properties == null ? null : properties.get(name);
    }

    private static HttpHost resolveHost(String address) {
        URI uri = URI.create(address.trim());
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Testing service address has no host: " + address);
        }
        String scheme = uri.getScheme() == null ? HTTP_SCHEME : uri.getScheme();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(scheme);
        return new HttpHost(scheme, uri.getHost(), port);
    }

    private static int defaultPort(String scheme) {
        return HTTPS_SCHEME.equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }
}

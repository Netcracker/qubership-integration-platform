package org.qubership.integration.platform.engine.service.testing;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.net.URIAuthority;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;

// The lookup is programmatic, so @LookupIfProperty alone is not enough: ArC removes a bean nothing injects.
@ApplicationScoped
@LookupIfProperty(name = "qip.testing.enabled", stringValue = "true")
@Unremovable
public class EndpointMockTestingService implements TestingService {

    private static final String MOCK_CALL_PATH = "/api/v1/endpoint-mocks/call";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private final HttpHost testingServiceHost;

    @Inject
    public EndpointMockTestingService(@ConfigProperty(name = "qip.testing.address") String address) {
        this.testingServiceHost = resolveHost(address);
    }

    @Override
    public boolean canBeMocked(EndpointInfo endpointInfo) {
        String elementId = endpointInfo == null ? null : endpointInfo.getElementId();
        return elementId != null && !elementId.isBlank();
    }

    @Override
    public HttpRequestInterceptor buildEndpointMockInterceptor(String chainId, EndpointInfo endpointInfo) {
        String elementId = endpointInfo.getElementId();
        // EndpointInfo.path is the operation template, not a request path, despite the name.
        String operationPath = endpointInfo.getPath();
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
    public HttpRoutePlanner buildRoutePlanner(String chainId, EndpointInfo endpointInfo) {
        boolean secure = HTTPS_SCHEME.equals(testingServiceHost.getSchemeName());
        return (target, context) -> new HttpRoute(testingServiceHost, null, secure);
    }

    // The query is kept on the wire for readable logs only; the testing service reads it from the context header.
    private static String mockCallTarget(String requestTarget) {
        int queryStart = requestTarget.indexOf('?');
        return queryStart < 0 ? MOCK_CALL_PATH : MOCK_CALL_PATH + requestTarget.substring(queryStart);
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

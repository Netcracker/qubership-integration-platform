package org.qubership.integration.platform.engine.service.testing;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.net.URIAuthority;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;

// The lookup is programmatic, so @LookupIfProperty alone is not enough: ArC removes a bean nothing injects.
@Slf4j
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
    private final String mockCallPath;

    @Inject
    public EndpointMockTestingService(@ConfigProperty(name = "qip.testing.address") String address) {
        URI uri = parseAddress(address);
        this.testingServiceHost = resolveHost(uri);
        this.mockCallPath = basePath(uri) + MOCK_CALL_PATH;
        log.info("Endpoint mocking is enabled: outbound HTTP calls of every chain go to {}{}",
                testingServiceHost, mockCallPath);
    }

    @Override
    public boolean canBeMocked(EndpointInfo endpointInfo) {
        String elementId = endpointInfo == null ? null : endpointInfo.getElementId();
        if (elementId == null || elementId.isBlank()) {
            log.warn("An element carries no design-time id, so its calls are not mocked and reach the real endpoint");
            return false;
        }
        return true;
    }

    @Override
    public HttpRequestInterceptor buildEndpointMockInterceptor(String chainId, EndpointInfo endpointInfo) {
        String elementId = endpointInfo.getElementId();
        // EndpointInfo.path is the operation template, not a request path, despite the name.
        String operationPath = endpointInfo.getPath();
        return (request, entity, context) -> {
            // hc5 runs the processor again over the same request on an authentication challenge, and a second
            // rewrite would report the mock endpoint as the live target.
            if (request.containsHeader(TestingContext.HEADER_NAME)) {
                return;
            }
            String requestTarget = request.getPath() == null || request.getPath().isEmpty()
                    ? "/" : request.getPath();
            TestingContext testingContext =
                    new TestingContext(chainId, elementId, operationPath, requestTarget);
            request.setHeader(TestingContext.HEADER_NAME, testingContext.encode());
            request.setScheme(testingServiceHost.getSchemeName());
            request.setAuthority(
                    new URIAuthority(testingServiceHost.getHostName(), testingServiceHost.getPort()));
            request.setPath(mockCallTarget(requestTarget));
            log.debug("Mocking {} of element {} in chain {}", requestTarget, elementId, chainId);
        };
    }

    @Override
    public HttpRoutePlanner buildRoutePlanner(String chainId, EndpointInfo endpointInfo) {
        boolean secure = HTTPS_SCHEME.equals(testingServiceHost.getSchemeName());
        return (target, context) -> new HttpRoute(testingServiceHost, null, secure);
    }

    // The query is kept on the wire for readable logs only; the testing service reads it from the context header.
    private String mockCallTarget(String requestTarget) {
        int queryStart = requestTarget.indexOf('?');
        return queryStart < 0 ? mockCallPath : mockCallPath + requestTarget.substring(queryStart);
    }

    private static URI parseAddress(String address) {
        URI uri = URI.create(address.trim());
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Testing service address has no host: " + address);
        }
        return uri;
    }

    private static HttpHost resolveHost(URI uri) {
        String scheme = uri.getScheme() == null ? HTTP_SCHEME : uri.getScheme();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(scheme);
        return new HttpHost(scheme, uri.getHost(), port);
    }

    // An ingress-style address carries a base path, and the mock endpoint hangs off it.
    private static String basePath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static int defaultPort(String scheme) {
        return HTTPS_SCHEME.equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }
}

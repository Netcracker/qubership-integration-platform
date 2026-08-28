package org.qubership.integration.platform.engine.service.testing;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.URIAuthority;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(value = "qip.testing.enabled", havingValue = "true")
public class EndpointMockTestingService implements TestingService {

    private static final String MOCK_CALL_PATH = "/api/v1/endpoint-mocks/call";
    private static final String REWRITE_ATTRIBUTE = EndpointMockTestingService.class.getName() + ".rewrite";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private final HttpHost testingServiceHost;
    private final String mockCallPath;

    public EndpointMockTestingService(@Value("${qip.testing.address}") String address) {
        URI uri = parseAddress(address);
        this.testingServiceHost = resolveHost(uri);
        this.mockCallPath = basePath(uri) + MOCK_CALL_PATH;
        log.info("Endpoint mocking is enabled: outbound HTTP calls of every chain go to {}{}",
                testingServiceHost, mockCallPath);
    }

    @Override
    public boolean canBeMocked(ElementProperties properties) {
        String elementId = property(properties, ChainProperties.ELEMENT_ID);
        if (elementId == null || elementId.isBlank()) {
            log.warn("Element {} carries no design-time id, so its calls are not mocked and reach the real endpoint",
                    properties == null ? null : properties.getElementId());
            return false;
        }
        return true;
    }

    @Override
    public HttpRequestInterceptor buildEndpointMockInterceptor(String chainId, ElementProperties elementProperties) {
        // The design-time element id, not ElementProperties.getElementId(), which changes with every snapshot.
        String elementId = property(elementProperties, ChainProperties.ELEMENT_ID);
        String operationPath = property(elementProperties, ChainProperties.OPERATION_PATH);
        return (request, entity, context) -> {
            // On an authentication challenge hc5 restores the headers of the untouched original request and runs
            // the processor over the same request again. Replay the first pass: the path it would read back is
            // the mock endpoint, not the live target.
            String replayed = replayedContext(request, context);
            if (replayed != null) {
                request.setHeader(TestingContext.HEADER_NAME, replayed);
                return;
            }
            String requestTarget = request.getPath() == null || request.getPath().isEmpty()
                    ? "/" : request.getPath();
            String encodedContext =
                    new TestingContext(chainId, elementId, operationPath, requestTarget).encode();
            request.setHeader(TestingContext.HEADER_NAME, encodedContext);
            request.setScheme(testingServiceHost.getSchemeName());
            request.setAuthority(
                    new URIAuthority(testingServiceHost.getHostName(), testingServiceHost.getPort()));
            request.setPath(mockCallTarget(requestTarget));
            if (context != null) {
                context.setAttribute(REWRITE_ATTRIBUTE, new Rewrite(request, encodedContext));
            }
            log.debug("Mocking {} of element {} in chain {}", requestTarget, elementId, chainId);
        };
    }

    @Override
    public HttpRoutePlanner buildRoutePlanner(String chainId, ElementProperties elementProperties) {
        boolean secure = HTTPS_SCHEME.equals(testingServiceHost.getSchemeName());
        return (target, context) -> new HttpRoute(testingServiceHost, null, secure);
    }

    private static String replayedContext(HttpRequest request, HttpContext context) {
        Object attribute = context == null ? null : context.getAttribute(REWRITE_ATTRIBUTE);
        // A record pattern would read better here, but the build's Checkstyle cannot parse one.
        return attribute instanceof Rewrite rewrite && rewrite.request() == request
                ? rewrite.encodedContext() : null;
    }

    // Kept on the exchange rather than on a header, which a caller of the chain could set. Keyed by request
    // identity: a redirect is a new request and has to be rewritten in its turn.
    private record Rewrite(HttpRequest request, String encodedContext) {
    }

    // The query is kept on the wire for readable logs only; the testing service reads it from the context header.
    private String mockCallTarget(String requestTarget) {
        int queryStart = requestTarget.indexOf('?');
        return queryStart < 0 ? mockCallPath : mockCallPath + requestTarget.substring(queryStart);
    }

    private static String property(ElementProperties elementProperties, String name) {
        if (elementProperties == null) {
            return null;
        }
        Map<String, String> properties = elementProperties.getProperties();
        return properties == null ? null : properties.get(name);
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

    // An ingress-style address carries a base path, and the mock endpoint hangs off it. The raw form goes back
    // on the wire as it was written, so a percent-escaped base path keeps its escapes.
    private static String basePath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static int defaultPort(String scheme) {
        return HTTPS_SCHEME.equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }
}

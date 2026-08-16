package org.qubership.integration.platform.engine.service.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointMockTestingServiceTest {

    private static final String ADDRESS = "http://testing-service:8080";
    private static final String ELEMENT_ID = "design-time-element-id";
    private static final String OPERATION_PATH = "/orders/{orderId}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canBeMockedWhenTheElementIdIsPresent() {
        assertTrue(service().canBeMocked(endpointInfo(OPERATION_PATH)));
    }

    @Test
    void cannotBeMockedWhenTheElementIdIsMissing() {
        assertFalse(service().canBeMocked(EndpointInfo.builder().path(OPERATION_PATH).build()));
    }

    @Test
    void cannotBeMockedWhenTheElementIdIsBlank() {
        assertFalse(service().canBeMocked(EndpointInfo.builder().elementId("  ").path(OPERATION_PATH).build()));
    }

    @Test
    void cannotBeMockedWhenThereIsNoEndpointInfoAtAll() {
        assertFalse(service().canBeMocked(null));
    }

    @Test
    void sendsTheElementId() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals(ELEMENT_ID, contextField(request, "elementId"));
    }

    @Test
    void sendsTheChainId() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals("chain-1", contextField(request, "chainId"));
    }

    @Test
    void sendsTheEndpointInfoPathAsTheOperationPathTemplate() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals(OPERATION_PATH, contextField(request, "operationPath"));
    }

    @Test
    void sendsTheRequestTargetWithItsQueryString() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42?status=NEW&limit=10"));

        assertEquals("/orders/42?status=NEW&limit=10", contextField(request, "path"));
    }

    @Test
    void passesTheLiteralNullOperationPathThrough() {
        // An http-sender has neither a context path nor an operation path, so the builder sends the string "null".
        HttpRequest request = intercept(request("http://orders:8080/orders/42"), endpointInfo("null"));

        assertEquals("null", contextField(request, "operationPath"));
    }

    @Test
    void sendsAnAbsentOperationPathAsNull() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"), endpointInfo(null));

        assertTrue(context(request).get("operationPath").isNull());
    }

    @Test
    void rewritesTheRequestTargetToTheMockEndpoint() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42?status=NEW"));

        assertEquals("/api/v1/endpoint-mocks/call?status=NEW", request.getPath());
        assertEquals("http", request.getScheme());
        assertEquals("testing-service", request.getAuthority().getHostName());
        assertEquals(8080, request.getAuthority().getPort());
    }

    @Test
    void rewritesTheRequestTargetWhenThereIsNoQueryString() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals("/api/v1/endpoint-mocks/call", request.getPath());
    }

    @Test
    void rebuildsTheContextOnEveryRequest() {
        HttpRequestInterceptor interceptor =
                service().buildEndpointMockInterceptor("chain-1", endpointInfo(OPERATION_PATH));
        HttpRequest first = request("http://orders:8080/orders/42");
        HttpRequest second = request("http://orders:8080/orders/7?status=NEW");

        process(interceptor, first);
        process(interceptor, second);

        assertEquals("/orders/42", contextField(first, "path"));
        assertEquals("/orders/7?status=NEW", contextField(second, "path"));
    }

    @Test
    void routesToTheConfiguredHost() {
        HttpRoute route = route(service());

        assertEquals("testing-service", route.getTargetHost().getHostName());
        assertEquals(8080, route.getTargetHost().getPort());
        assertEquals("http", route.getTargetHost().getSchemeName());
        assertFalse(route.isSecure());
    }

    @Test
    void routesSecurelyOverHttps() {
        HttpRoute route = route(new EndpointMockTestingService("https://testing-service"));

        assertEquals(443, route.getTargetHost().getPort());
        assertTrue(route.isSecure());
    }

    @Test
    void fallsBackToTheDefaultHttpPort() {
        HttpRoute route = route(new EndpointMockTestingService("http://testing-service"));

        assertEquals(80, route.getTargetHost().getPort());
    }

    @Test
    void rejectsAnAddressWithoutAHost() {
        assertThrows(IllegalArgumentException.class, () -> new EndpointMockTestingService("/endpoint-mocks"));
    }

    private static EndpointMockTestingService service() {
        return new EndpointMockTestingService(ADDRESS);
    }

    private static EndpointInfo endpointInfo(String operationPath) {
        return EndpointInfo.builder().elementId(ELEMENT_ID).path(operationPath).protocol("http").build();
    }

    private static HttpRequest request(String uri) {
        return new BasicHttpRequest("GET", URI.create(uri));
    }

    private static HttpRequest intercept(HttpRequest request) {
        return intercept(request, endpointInfo(OPERATION_PATH));
    }

    private static HttpRequest intercept(HttpRequest request, EndpointInfo endpointInfo) {
        process(service().buildEndpointMockInterceptor("chain-1", endpointInfo), request);
        return request;
    }

    private static void process(HttpRequestInterceptor interceptor, HttpRequest request) {
        try {
            interceptor.process(request, null, null);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpRoute route(EndpointMockTestingService service) {
        HttpRoutePlanner planner = service.buildRoutePlanner("chain-1", endpointInfo(OPERATION_PATH));
        try {
            return planner.determineRoute(new HttpHost("http", "orders", 8080), null);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode context(HttpRequest request) {
        try {
            String header = request.getFirstHeader(TestingContext.HEADER_NAME).getValue();
            return MAPPER.readTree(new String(Base64.getDecoder().decode(header), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String contextField(HttpRequest request, String name) {
        return context(request).get(name).asText();
    }
}

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
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointMockTestingServiceTest {

    private static final String ADDRESS = "http://testing-service:8080";
    private static final String DESIGN_TIME_ELEMENT_ID = "design-time-element-id";
    private static final String SNAPSHOT_ELEMENT_ID = "snapshot-element-id";
    private static final String OPERATION_PATH = "/orders/{orderId}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canBeMockedWhenTheDesignTimeElementIdIsPresent() {
        assertTrue(service().canBeMocked(elementProperties()));
    }

    @Test
    void cannotBeMockedWhenTheDesignTimeElementIdIsMissing() {
        ElementProperties properties = elementProperties();
        properties.getProperties().remove(ChainProperties.ELEMENT_ID);

        assertFalse(service().canBeMocked(properties));
    }

    @Test
    void cannotBeMockedWhenTheDesignTimeElementIdIsBlank() {
        ElementProperties properties = elementProperties();
        properties.getProperties().put(ChainProperties.ELEMENT_ID, "  ");

        assertFalse(service().canBeMocked(properties));
    }

    @Test
    void cannotBeMockedWhenThereAreNoPropertiesAtAll() {
        assertFalse(service().canBeMocked(new ElementProperties(SNAPSHOT_ELEMENT_ID, null)));
    }

    @Test
    void cannotBeMockedWhenThereIsNoElementPropertiesAtAll() {
        assertFalse(service().canBeMocked(null));
    }

    @Test
    void sendsTheDesignTimeElementIdRatherThanTheSnapshotOne() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals(DESIGN_TIME_ELEMENT_ID, contextField(request, "elementId"));
        assertNotEquals(SNAPSHOT_ELEMENT_ID, contextField(request, "elementId"));
    }

    @Test
    void sendsTheChainId() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals("chain-1", contextField(request, "chainId"));
    }

    @Test
    void sendsTheOperationPathTemplate() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42"));

        assertEquals(OPERATION_PATH, contextField(request, "operationPath"));
    }

    @Test
    void sendsTheRequestTargetWithItsQueryString() {
        HttpRequest request = intercept(request("http://orders:8080/orders/42?status=NEW&limit=10"));

        assertEquals("/orders/42?status=NEW&limit=10", contextField(request, "path"));
    }

    @Test
    void sendsAnAbsentOperationPathAsNull() {
        ElementProperties properties = elementProperties();
        properties.getProperties().remove(ChainProperties.OPERATION_PATH);
        HttpRequest request = request("http://orders:8080/orders/42");

        intercept(request, properties);

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
                service().buildEndpointMockInterceptor("chain-1", elementProperties());
        HttpRequest first = request("http://orders:8080/orders/42");
        HttpRequest second = request("http://orders:8080/orders/7?status=NEW");

        process(interceptor, first);
        process(interceptor, second);

        assertEquals("/orders/42", contextField(first, "path"));
        assertEquals("/orders/7?status=NEW", contextField(second, "path"));
    }

    @Test
    void keepsTheLiveTargetWhenTheSameRequestPassesThroughTwice() {
        // hc5 runs the processor again over the same request on an authentication challenge.
        HttpRequestInterceptor interceptor =
                service().buildEndpointMockInterceptor("chain-1", elementProperties());
        HttpRequest request = request("http://orders:8080/orders/42?status=NEW");

        process(interceptor, request);
        process(interceptor, request);

        assertEquals("/orders/42?status=NEW", contextField(request, "path"));
        assertEquals("/api/v1/endpoint-mocks/call?status=NEW", request.getPath());
    }

    @Test
    void treatsAnEmptyRequestTargetAsTheRoot() {
        HttpRequest request = intercept(new BasicHttpRequest("GET", ""));

        assertEquals("/", contextField(request, "path"));
        assertEquals("/api/v1/endpoint-mocks/call", request.getPath());
    }

    @Test
    void keepsTheBasePathOfTheConfiguredAddress() {
        EndpointMockTestingService service = new EndpointMockTestingService("http://gateway:8080/mocks/");
        HttpRequest request = request("http://orders:8080/orders/42?status=NEW");

        process(service.buildEndpointMockInterceptor("chain-1", elementProperties()), request);

        assertEquals("/mocks/api/v1/endpoint-mocks/call?status=NEW", request.getPath());
        assertEquals("/orders/42?status=NEW", contextField(request, "path"));
        assertEquals("gateway", route(service).getTargetHost().getHostName());
    }

    @Test
    void acceptsAnAddressWithSurroundingWhitespace() {
        HttpRoute route = route(new EndpointMockTestingService("  http://testing-service:8080  "));

        assertEquals("testing-service", route.getTargetHost().getHostName());
        assertEquals(8080, route.getTargetHost().getPort());
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

    private static ElementProperties elementProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put(ChainProperties.ELEMENT_ID, DESIGN_TIME_ELEMENT_ID);
        properties.put(ChainProperties.OPERATION_PATH, OPERATION_PATH);
        return new ElementProperties(SNAPSHOT_ELEMENT_ID, properties);
    }

    private static HttpRequest request(String uri) {
        return new BasicHttpRequest("GET", URI.create(uri));
    }

    private static HttpRequest intercept(HttpRequest request) {
        return intercept(request, elementProperties());
    }

    private static HttpRequest intercept(HttpRequest request, ElementProperties properties) {
        process(service().buildEndpointMockInterceptor("chain-1", properties), request);
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
        HttpRoutePlanner planner = service.buildRoutePlanner("chain-1", elementProperties());
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

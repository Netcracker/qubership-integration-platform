package org.qubership.integration.platform.engine.service.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        // On an authentication challenge hc5 restores the headers of the original request and runs the processor
        // over the same request again, leaving the path rewritten.
        HttpRequestInterceptor interceptor =
                service().buildEndpointMockInterceptor("chain-1", elementProperties());
        HttpRequest request = request("http://orders:8080/orders/42?status=NEW");
        HttpContext context = testCaseRunContext();

        process(interceptor, request, context);
        request.removeHeaders(TestingContext.HEADER_NAME);
        process(interceptor, request, context);

        assertEquals("/orders/42?status=NEW", contextField(request, "path"));
        assertEquals("/api/v1/endpoint-mocks/call?status=NEW", request.getPath());
    }

    @Test
    void ignoresAContextHeaderTheCallerSupplied() {
        // Camel copies the headers of an inbound request onto the outbound one, so the header reaches the
        // interceptor from outside the engine.
        HttpRequest request = request("http://orders:8080/orders/42");
        request.setHeader(TestingContext.HEADER_NAME, "c3Bvb2ZlZA==");

        intercept(request);

        assertEquals("/api/v1/endpoint-mocks/call", request.getPath());
        assertEquals("testing-service", request.getAuthority().getHostName());
        assertEquals(1, request.getHeaders(TestingContext.HEADER_NAME).length);
        assertEquals("/orders/42", contextField(request, "path"));
        assertEquals("chain-1", contextField(request, "chainId"));
    }

    @Test
    void ignoresAContextHeaderTheCallerSuppliedOnTheSecondPass() {
        HttpRequestInterceptor interceptor =
                service().buildEndpointMockInterceptor("chain-1", elementProperties());
        HttpRequest request = request("http://orders:8080/orders/42");
        HttpContext context = testCaseRunContext();

        process(interceptor, request, context);
        request.setHeader(TestingContext.HEADER_NAME, "c3Bvb2ZlZA==");
        process(interceptor, request, context);

        assertEquals("/orders/42", contextField(request, "path"));
    }

    @Test
    void rewritesTheFollowUpRequestOfARedirect() {
        // hc5 builds a new request for a redirect and keeps it on the context of the same exchange.
        HttpRequestInterceptor interceptor =
                service().buildEndpointMockInterceptor("chain-1", elementProperties());
        HttpContext context = testCaseRunContext();
        HttpRequest first = request("http://orders:8080/orders/42");
        HttpRequest redirect = request("http://orders:8080/orders/43");

        process(interceptor, first, context);
        process(interceptor, redirect, context);

        assertEquals("/orders/43", contextField(redirect, "path"));
        assertEquals("/api/v1/endpoint-mocks/call", redirect.getPath());
    }

    @Test
    void treatsAnEmptyRequestTargetAsTheRoot() {
        // The constructor normalizes a blank target, setPath writes it through.
        BasicHttpRequest request = new BasicHttpRequest("GET", "/orders/42");
        request.setPath("");

        intercept(request);

        assertEquals("/", contextField(request, "path"));
        assertEquals("/api/v1/endpoint-mocks/call", request.getPath());
    }

    @Test
    void treatsAnAbsentRequestTargetAsTheRoot() {
        BasicHttpRequest request = new BasicHttpRequest("GET", "/orders/42");
        request.setPath(null);

        intercept(request);

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
    void keepsThePercentEscapesOfTheConfiguredBasePath() {
        EndpointMockTestingService service = new EndpointMockTestingService("http://gateway:8080/mock%20base");
        HttpRequest request = request("http://orders:8080/orders/42");

        process(service.buildEndpointMockInterceptor("chain-1", elementProperties()), request);

        assertEquals("/mock%20base/api/v1/endpoint-mocks/call", request.getPath());
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

    @Test
    void leavesALiveCallAlone() {
        HttpRequest request = request("http://orders:8080/orders/42");

        process(service().buildEndpointMockInterceptor("chain-1", elementProperties()), request, liveRunContext());

        assertNull(request.getFirstHeader(TestingContext.HEADER_NAME));
        assertEquals("/orders/42", request.getPath());
        assertEquals("orders", request.getAuthority().getHostName());
    }

    @Test
    void routesALiveCallToItsOwnEndpoint() {
        HttpRoute route = route(service(), liveRunContext());

        assertEquals("orders", route.getTargetHost().getHostName());
        assertEquals(8080, route.getTargetHost().getPort());
    }

    @Test
    void leavesACallAloneWhenNoExchangeIsAtHand() {
        // The GraphQL producer runs the client on a context of its own and files no exchange into it, so a
        // graphql call is never mocked, whatever headers it carries.
        HttpRequest request = request("http://orders:8080/orders/42");
        request.setHeader(Headers.EXTERNAL_SESSION_CIP_ID, "testing-session-1");

        process(service().buildEndpointMockInterceptor("chain-1", elementProperties()), request,
                new BasicHttpContext());

        assertNull(request.getFirstHeader(TestingContext.HEADER_NAME));
        assertEquals("/orders/42", request.getPath());
        assertEquals("orders", request.getAuthority().getHostName());
    }

    @Test
    void ignoresASessionHeaderTheCallerSupplied() {
        // Camel copies the headers of an inbound request onto the outbound one, so a live call can carry the
        // header of a run it does not belong to. Only the exchange decides.
        HttpRequest request = request("http://orders:8080/orders/42");
        request.setHeader(Headers.EXTERNAL_SESSION_CIP_ID, "testing-session-1");

        process(service().buildEndpointMockInterceptor("chain-1", elementProperties()), request, liveRunContext());

        assertNull(request.getFirstHeader(TestingContext.HEADER_NAME));
        assertEquals("/orders/42", request.getPath());
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
        process(interceptor, request, testCaseRunContext());
    }

    private static void process(HttpRequestInterceptor interceptor, HttpRequest request, HttpContext context) {
        try {
            interceptor.process(request, null, context);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Mocking only takes a call the testing service started, and the exchange of such a run is what camel-http
    // leaves in the client context.
    private static HttpContext testCaseRunContext() {
        Exchange exchange = mock(Exchange.class);
        when(exchange.getProperty(Properties.TESTING_SESSION_ID)).thenReturn("testing-session-1");
        return contextOf(exchange);
    }

    // An unstubbed mock answers null for every property, which is what a live run looks like.
    private static HttpContext liveRunContext() {
        return contextOf(mock(Exchange.class));
    }

    private static HttpContext contextOf(Exchange exchange) {
        HttpContext context = new BasicHttpContext();
        context.setAttribute(EndpointMockTestingService.CAMEL_EXCHANGE_ATTRIBUTE, exchange);
        return context;
    }

    private static HttpRoute route(EndpointMockTestingService service) {
        return route(service, testCaseRunContext());
    }

    private static HttpRoute route(EndpointMockTestingService service, HttpContext context) {
        HttpRoutePlanner planner = service.buildRoutePlanner("chain-1", elementProperties());
        try {
            return planner.determineRoute(new HttpHost("http", "orders", 8080), context);
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

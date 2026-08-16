package org.qubership.integration.platform.engine.service.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.lookup.LookupIfProperty;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        assertTrue(context(request).get("operationPath").isTextual(), "expected the string null, not a JSON null");
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
    void keepsTheLiveTargetWhenTheSameRequestPassesThroughTwice() {
        // hc5 runs the processor again over the same request on an authentication challenge.
        HttpRequestInterceptor interceptor =
                service().buildEndpointMockInterceptor("chain-1", endpointInfo(OPERATION_PATH));
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

        process(service.buildEndpointMockInterceptor("chain-1", endpointInfo(OPERATION_PATH)), request);

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

    // The bean is looked up programmatically: without both annotations the lookup returns empty and mocking
    // never happens, which no behavioral test can catch.
    @Test
    void carriesTheAnnotationsTheProgrammaticLookupNeeds() {
        assertNotNull(EndpointMockTestingService.class.getAnnotation(Unremovable.class),
                "ArC removes a bean nothing injects, so @Unremovable is required");

        LookupIfProperty lookup = EndpointMockTestingService.class.getAnnotation(LookupIfProperty.class);

        assertNotNull(lookup, "@LookupIfProperty is what gates the lookup on the property");
        assertEquals("qip.testing.enabled", lookup.name());
        assertEquals("true", lookup.stringValue());
    }

    // The keys the annotation and the @ConfigProperty read have to be the keys the shipped application.yml writes.
    @Test
    void theShippedConfigurationKeepsMockingOff() throws IOException {
        Map<String, Object> testing = shippedTestingConfiguration();

        assertEquals("${TESTING_SERVICE_ENABLED:false}", testing.get("enabled"));
        assertEquals("${TESTING_SERVICE_ADDRESS:http://testing-service:8080}", testing.get("address"));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedTestingConfiguration() throws IOException {
        // The test classpath carries an application.yml of its own, so look for the one the module ships.
        Enumeration<URL> resources =
                EndpointMockTestingServiceTest.class.getClassLoader().getResources("application.yml");
        while (resources.hasMoreElements()) {
            try (InputStream stream = resources.nextElement().openStream()) {
                Map<String, Object> root = new Yaml().load(stream);
                if (root != null && root.get("qip") instanceof Map) {
                    Map<String, Object> testing =
                            (Map<String, Object>) ((Map<String, Object>) root.get("qip")).get("testing");
                    assertNotNull(testing, "application.yml does not declare qip.testing");
                    return testing;
                }
            }
        }
        throw new AssertionError("no application.yml on the classpath declares qip");
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

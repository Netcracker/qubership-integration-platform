package org.qubership.integration.platform.engine.camel.components.graphql;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GraphqlCustomEndpointHttpTest {
    private static final String QUERY = "query Health { health { ready } }";
    private static final String REQUEST_BODY = """
            {"query":"query Health { health { ready } }","operationName":"Health","variables":{}}
            """;
    private static final String RESPONSE_BODY = "{\"data\":{\"health\":{\"ready\":true}}}";
    private static final String CERTIFICATES = "/snapshot-fixtures/kafka/";
    private static final String STORE_PASSWORD = "graphql-test-store";

    private GraphqlCustomEndpoint endpoint;
    private Producer producer;
    private DefaultCamelContext context;
    private CloseableHttpClient httpClient;
    private WireMockServer upstream;
    private WireMockServer proxy;
    @TempDir
    private Path temporaryDirectory;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1"));
        upstream.start();
        context = new DefaultCamelContext();
        context.addComponent("graphql-custom", new GraphqlCustomComponent());
        context.start();
        createEndpoint(upstream.baseUrl());
    }

    @AfterEach
    void tearDown() {
        assertAll(
                () -> {
                    if (producer != null) {
                        producer.stop();
                    }
                },
                () -> {
                    if (context != null) {
                        context.close();
                    }
                },
                () -> {
                    if (httpClient != null) {
                        httpClient.close();
                    }
                },
                () -> {
                    if (proxy != null) {
                        proxy.stop();
                    }
                },
                () -> {
                    if (upstream != null) {
                        upstream.stop();
                    }
                });
    }

    @Test
    void shouldForwardRequestThroughProxyWhenProxyHostIsConfigured() throws Exception {
        upstream.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(RESPONSE_BODY)));
        proxy = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1"));
        proxy.start();
        proxy.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse().proxiedFrom(upstream.baseUrl())));

        endpoint.setProxyHost("127.0.0.1:" + proxy.port());
        startProducer();

        Exchange exchange = endpoint.createExchange(ExchangePattern.InOut);
        producer.process(exchange);

        assertNull(exchange.getException());
        assertEquals(RESPONSE_BODY, exchange.getMessage().getBody(String.class));
        assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        for (WireMockServer server : List.of(proxy, upstream)) {
            assertEquals(1, server.getAllServeEvents().size(), "Expected one request at " + server.baseUrl());
            server.verify(1, postRequestedFor(urlEqualTo("/graphql"))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson(REQUEST_BODY)));
        }
    }

    @Test
    void shouldAuthenticateAfterBasicChallengeWhenCredentialsAreConfigured() throws Exception {
        upstream.stubFor(post(urlEqualTo("/graphql"))
                .withHeader("Authorization", absent())
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Basic realm=\"graphql\"")));
        upstream.stubFor(post(urlEqualTo("/graphql"))
                .withBasicAuth("user", "pass")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(RESPONSE_BODY)));
        endpoint.setUsername("user");
        endpoint.setPassword("pass");
        startProducer();

        Exchange exchange = endpoint.createExchange(ExchangePattern.InOut);
        producer.process(exchange);

        assertNull(exchange.getException());
        assertEquals(RESPONSE_BODY, exchange.getMessage().getBody(String.class));
        assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        var requests = upstream.getAllServeEvents().reversed();
        assertEquals(2, requests.size());
        assertNull(requests.getFirst().getRequest().getHeader("Authorization"));
        assertEquals("Basic dXNlcjpwYXNz", requests.getLast().getRequest().getHeader("Authorization"));
        upstream.verify(2, postRequestedFor(urlEqualTo("/graphql"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(REQUEST_BODY)));
    }

    @Test
    void shouldSendRequestOverHttpsWhenServerCertificateIsTrusted() throws Exception {
        startTlsServer("valid");
        upstream.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(RESPONSE_BODY)));
        startProducer();

        Exchange exchange = endpoint.createExchange(ExchangePattern.InOut);
        producer.process(exchange);

        assertNull(exchange.getException());
        assertEquals(RESPONSE_BODY, exchange.getMessage().getBody(String.class));
        assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(1, upstream.getAllServeEvents().size());
        upstream.verify(1, postRequestedFor(urlEqualTo("/graphql"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(REQUEST_BODY)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"untrusted", "wrong-hostname", "expired"})
    void shouldRejectHttpsRequestWhenServerCertificateIsInvalid(String certificateProfile) throws Exception {
        startTlsServer(certificateProfile);
        upstream.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(RESPONSE_BODY)));
        startProducer();

        Exchange exchange = endpoint.createExchange(ExchangePattern.InOut);
        producer.process(exchange);

        assertInstanceOf(SSLException.class, exchange.getException());
        assertEquals(0, upstream.getAllServeEvents().size());
    }

    @Test
    void shouldRecoverOnNextRequestWhenPreviousResponseTimesOut() throws Exception {
        endpoint.setHttpClientConfigurer(builder -> builder.setDefaultRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(1))
                .build()));
        upstream.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withFixedDelay(4000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":{\"health\":{\"ready\":false}}}")));
        startProducer();

        Exchange timedOut = endpoint.createExchange(ExchangePattern.InOut);
        producer.process(timedOut);

        assertInstanceOf(SocketTimeoutException.class, timedOut.getException());
        assertEquals(1, upstream.getAllServeEvents().size());
        upstream.verify(1, postRequestedFor(urlEqualTo("/graphql"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(REQUEST_BODY)));

        upstream.resetMappings();
        upstream.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(RESPONSE_BODY)));

        Exchange recovered = endpoint.createExchange(ExchangePattern.InOut);
        producer.process(recovered);

        assertNull(recovered.getException());
        assertEquals(RESPONSE_BODY, recovered.getMessage().getBody(String.class));
        assertEquals(200, recovered.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(2, upstream.getAllServeEvents().size());
        upstream.verify(2, postRequestedFor(urlEqualTo("/graphql"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(REQUEST_BODY)));
    }

    private void createEndpoint(String baseUrl) {
        endpoint = context.getEndpoint("graphql-custom:" + baseUrl + "/graphql", GraphqlCustomEndpoint.class);
        endpoint.setQuery(QUERY);
        endpoint.setOperationName("Health");
    }

    private void startTlsServer(String certificateProfile) throws Exception {
        KeyStore serverStore = KeyStore.getInstance("PKCS12");
        serverStore.load(null, null);
        String caCertificate = "untrusted".equals(certificateProfile) ? "untrusted-ca-cert.pem" : "ca-cert.pem";
        serverStore.setKeyEntry("graphql-server", readPrivateKey(certificateProfile + "-key.pem"),
                STORE_PASSWORD.toCharArray(), new Certificate[] {
                        readCertificate(certificateProfile + "-cert.pem"), readCertificate(caCertificate)
                });
        Path serverStorePath = temporaryDirectory.resolve(certificateProfile + ".p12");
        try (OutputStream output = Files.newOutputStream(serverStorePath)) {
            serverStore.store(output, STORE_PASSWORD.toCharArray());
        }

        upstream.stop();
        upstream = new WireMockServer(options().httpDisabled(true).dynamicHttpsPort().bindAddress("127.0.0.1")
                .keystorePath(serverStorePath.toString())
                .keystoreType("PKCS12")
                .keystorePassword(STORE_PASSWORD)
                .keyManagerPassword(STORE_PASSWORD));
        upstream.start();
        createEndpoint("https://127.0.0.1:" + upstream.httpsPort());

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("graphql-ca", readCertificate("ca-cert.pem"));
        var sslContext = SSLContexts.custom().loadTrustMaterial(trustStore, null).build();
        endpoint.setHttpClientConfigurer(builder -> builder.setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                                .setSslContext(sslContext)
                                .buildClassic())
                        .build()));
    }

    private static Certificate readCertificate(String resource) throws Exception {
        try (InputStream input = GraphqlCustomEndpointHttpTest.class.getResourceAsStream(CERTIFICATES + resource)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey readPrivateKey(String resource) throws Exception {
        String pem;
        try (InputStream input = GraphqlCustomEndpointHttpTest.class.getResourceAsStream(CERTIFICATES + resource)) {
            pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    private void startProducer() throws Exception {
        endpoint.start();
        httpClient = (CloseableHttpClient) endpoint.getHttpClient();
        producer = endpoint.createProducer();
        producer.start();
    }
}

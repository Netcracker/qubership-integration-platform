package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.stub.PublisherStubSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PubSubOAuthServer implements AutoCloseable {
    private static final String RESOURCE_PREFIX = "/snapshot-fixtures/pubsub/";
    private static final String BASE64_PREFIX = "base64:";
    private static final String TOKEN_AUDIENCE = "https://oauth2.googleapis.com/token";
    private static final String TRUSTSTORE_PASSWORD = "snapshot-pubsub";
    private static final int SHORT_TOKEN_LIFETIME_SECONDS = 2;
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of(
            "authorization", Metadata.ASCII_STRING_MARSHALLER
    );
    private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
    private final Map<String, String> originalSslProperties = new LinkedHashMap<>();
    private final Map<String, ServiceAccount> serviceAccounts = new ConcurrentHashMap<>();
    private final Map<String, IssuedToken> issuedTokens = new ConcurrentHashMap<>();
    private final List<String> observedTokens = new CopyOnWriteArrayList<>();
    private final List<String> validationFailures = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private volatile String oauthError;
    private volatile int tokenLifetimeSeconds = 3600;
    private Set<String> expiredInvocationTokens = Set.of();
    private HttpServer server;
    private Path temporaryDirectory;

    void start() throws IOException, GeneralSecurityException {
        temporaryDirectory = Files.createTempDirectory("snapshot-pubsub-oauth-");
        installTruststore();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::issueToken);
        server.start();
    }

    NettyServerBuilder configureTls(NettyServerBuilder builder) throws IOException {
        try (InputStream certificate = resource("localhost-cert.pem");
                InputStream privateKey = resource("localhost-key.pem")) {
            return builder.useTransportSecurity(certificate, privateKey);
        }
    }

    String credentialsResource(String originalBase64) throws IOException, GeneralSecurityException {
        byte[] originalJson = Base64.getDecoder().decode(originalBase64.substring(BASE64_PREFIX.length()));
        ServiceAccountCredentials credentials;
        try (InputStream input = new ByteArrayInputStream(originalJson)) {
            credentials = ServiceAccountCredentials.fromStream(input);
        }
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) credentials.getPrivateKey();
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                privateKey.getModulus(), privateKey.getPublicExponent()
        ));
        serviceAccounts.put(credentials.getClientEmail(), new ServiceAccount(credentials.getPrivateKeyId(), publicKey));

        ObjectNode redirectedCredentials = (ObjectNode) objectMapper.readTree(originalJson);
        redirectedCredentials.put("token_uri", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        Path credentialsFile = Files.createTempFile(temporaryDirectory, "service-account-", ".json");
        objectMapper.writeValue(credentialsFile.toFile(), redirectedCredentials);
        return credentialsFile.toUri().toString();
    }

    ServerInterceptor interceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next
            ) {
                String authorization = headers.get(AUTHORIZATION);
                String tokenValue = authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring("Bearer ".length()) : null;
                IssuedToken token = tokenValue == null ? null : issuedTokens.get(tokenValue);
                if (token == null || !token.expiresAt().isAfter(Instant.now())) {
                    validationFailures.add("Pub/Sub RPC did not include a valid, unexpired token issued by the OAuth fixture.");
                    call.close(Status.UNAUTHENTICATED.withDescription("Invalid access token"), new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                observedTokens.add(tokenValue);
                return next.startCall(call, headers);
            }
        };
    }

    void beforeInvocation(
            String error,
            boolean shortLivedAccessToken,
            boolean expireAccessToken
    ) throws InterruptedException {
        if (error != null && !"invalid_grant".equals(error)) {
            throw new IllegalArgumentException("Pub/Sub OAuth fixture supports only the invalid_grant error.");
        }
        expiredInvocationTokens = expireAccessToken ? Set.copyOf(observedTokens) : Set.of();
        if (expireAccessToken) {
            assertFalse(expiredInvocationTokens.isEmpty(), "Token expiration requires a previous authenticated invocation.");
            for (String tokenValue : expiredInvocationTokens) {
                IssuedToken token = issuedTokens.get(tokenValue);
                assertEquals(SHORT_TOKEN_LIFETIME_SECONDS, token.lifetimeSeconds(),
                        "Token expiration requires a previous short-lived access token.");
                awaitExpiration(token);
            }
        }
        observedTokens.clear();
        oauthError = error;
        tokenLifetimeSeconds = shortLivedAccessToken ? SHORT_TOKEN_LIFETIME_SECONDS : 3600;
    }

    int tokenRequestCount() {
        return tokenRequests.get();
    }

    void verifyInvocation() {
        verify();
        if (!expiredInvocationTokens.isEmpty()) {
            assertFalse(observedTokens.isEmpty(), "Pub/Sub did not publish with a refreshed access token.");
            assertTrue(observedTokens.stream().noneMatch(expiredInvocationTokens::contains),
                    "Pub/Sub reused an expired access token instead of obtaining a new token.");
        }
    }

    void verify() {
        assertTrue(validationFailures.isEmpty(), () -> String.join("\n", validationFailures));
    }

    @Override
    public void close() throws IOException {
        try {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        } finally {
            originalSslProperties.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
            originalSslProperties.clear();
            if (temporaryDirectory != null) {
                try (Stream<Path> files = Files.walk(temporaryDirectory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
                temporaryDirectory = null;
            }
        }
    }

    private void installTruststore() throws IOException, GeneralSecurityException {
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        truststore.load(null, null);
        try (InputStream certificate = resource("localhost-cert.pem")) {
            truststore.setCertificateEntry("pubsub-fixture", CertificateFactory.getInstance("X.509")
                    .generateCertificate(certificate));
        }
        Path truststoreFile = temporaryDirectory.resolve("truststore.p12");
        try (OutputStream output = Files.newOutputStream(truststoreFile)) {
            truststore.store(output, TRUSTSTORE_PASSWORD.toCharArray());
        }
        setSslProperty("javax.net.ssl.trustStore", truststoreFile.toString());
        setSslProperty("javax.net.ssl.trustStoreType", "PKCS12");
        setSslProperty("javax.net.ssl.trustStorePassword", TRUSTSTORE_PASSWORD);
    }

    private void setSslProperty(String name, String value) {
        originalSslProperties.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private void awaitExpiration(IssuedToken token) throws InterruptedException {
        // The SDK's clock has no public setter; wait for the token's actual deadline without changing credentials.
        long remainingNanos = Duration.between(Instant.now(), token.expiresAt()).toNanos();
        if (remainingNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(SHORT_TOKEN_LIFETIME_SECONDS)));
        }
        assertFalse(token.expiresAt().isAfter(Instant.now()), "The previous OAuth token has not expired.");
    }

    private void issueToken(HttpExchange exchange) throws IOException {
        int requestNumber = tokenRequests.incrementAndGet();
        try (exchange) {
            try {
                assertEquals("POST", exchange.getRequestMethod(), "OAuth token requests must use POST.");
                Map<String, String> form = decodeForm(exchange.getRequestBody().readAllBytes());
                assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form.get("grant_type"));
                verifyAssertion(form.get("assertion"));
            } catch (Exception | AssertionError failure) {
                validationFailures.add("Invalid OAuth token request: " + failure.getMessage());
                respond(exchange, 400, Map.of("error", "invalid_grant"));
                return;
            }
            if (oauthError != null) {
                respond(exchange, 400, Map.of("error", oauthError));
            } else {
                String token = "snapshot-oauth-access-token-" + requestNumber;
                int lifetime = tokenLifetimeSeconds;
                issuedTokens.put(token, new IssuedToken(Instant.now().plusSeconds(lifetime), lifetime));
                respond(exchange, 200, Map.of("access_token", token, "token_type", "Bearer", "expires_in", lifetime));
            }
        }
    }

    private void verifyAssertion(String assertion) throws IOException, GeneralSecurityException {
        assertNotNull(assertion, "OAuth request must contain a signed JWT assertion.");
        String[] parts = assertion.split("\\.");
        assertEquals(3, parts.length, "OAuth assertion must have three JWT segments.");
        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("RS256", header.path("alg").asText());
        ServiceAccount account = serviceAccounts.get(claims.path("iss").asText());
        assertNotNull(account, "OAuth assertion issuer must match the exported service account.");
        assertEquals(account.privateKeyId(), header.path("kid").asText());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(account.publicKey());
        signature.update((parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])), "OAuth assertion signature is invalid.");
        assertEquals(TOKEN_AUDIENCE, claims.path("aud").asText());
        assertEquals(Set.copyOf(PublisherStubSettings.getDefaultServiceScopes()),
                Set.copyOf(Arrays.asList(claims.path("scope").asText().split(" "))));
        long now = Instant.now().getEpochSecond();
        assertTrue(claims.path("iat").asLong() <= now, "OAuth assertion issue time is in the future.");
        assertTrue(claims.path("exp").asLong() > now, "OAuth assertion has expired.");
    }

    private Map<String, String> decodeForm(byte[] body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String entry : new String(body, StandardCharsets.UTF_8).split("&")) {
            String[] pair = entry.split("=", 2);
            form.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return form;
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] response = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }

    private InputStream resource(String name) {
        InputStream input = PubSubOAuthServer.class.getResourceAsStream(RESOURCE_PREFIX + name);
        assertNotNull(input, "Pub/Sub TLS fixture resource is missing: " + name);
        return input;
    }

    private record ServiceAccount(String privateKeyId, PublicKey publicKey) {
    }

    private record IssuedToken(Instant expiresAt, int lifetimeSeconds) {
    }
}

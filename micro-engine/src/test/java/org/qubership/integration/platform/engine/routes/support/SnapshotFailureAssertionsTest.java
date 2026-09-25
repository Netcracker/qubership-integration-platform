package org.qubership.integration.platform.engine.routes.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.CamelExchangeException;
import org.apache.camel.Exchange;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFailureExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SnapshotFailureAssertionsTest {
    private static final String CERTIFICATE_EXPIRY = "NotAfter: <certificate-expiry:2019-01-01T00:00:00Z>";
    private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "UTC, NotAfter: Tue Jan 01 00:00:00 UTC 2019",
            "Europe/Moscow, NotAfter: Tue Jan 01 03:00:00 MSK 2019",
            "America/New_York, NotAfter: Mon Dec 31 19:00:00 EST 2018"
    })
    void shouldMatchCertificateExpiryWhenDefaultTimezoneChanges(String timezone, String message) throws JsonProcessingException {
        TimeZone originalTimezone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timezone));
            SnapshotFailureExpectation expected = expectation(failure(CertificateExpiredException.class, CERTIFICATE_EXPIRY, null));

            assertMatch(expected, new CertificateExpiredException(message));
        } finally {
            TimeZone.setDefault(originalTimezone);
        }
    }

    @Test
    void shouldKeepCertificateFailureDetailsExactWhenExpiryUsesPlaceholder() throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(CertificateExpiredException.class, CERTIFICATE_EXPIRY, null));
        String expiryMessage = "NotAfter: " + Date.from(Instant.parse("2019-01-01T00:00:00Z"));

        assertMismatch(expected, new CertificateExpiredException(
                "NotAfter: " + Date.from(Instant.parse("2020-01-01T00:00:00Z"))
        ));
        assertMismatch(expected, new CertificateExpiredException("Certificate validation failed"));
        assertMismatch(expected, new CertificateExpiredException(expiryMessage + " Extra details"));
        CertificateExpiredException withCause = new CertificateExpiredException(expiryMessage);
        withCause.initCause(new IllegalStateException("Additional cause"));
        assertMismatch(expected, withCause);

        SnapshotFailureExpectation otherType = expectation(failure(CertificateNotYetValidException.class, CERTIFICATE_EXPIRY, null));
        assertMismatch(otherType, new CertificateNotYetValidException(expiryMessage));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<certificate-expiry:2019-01-01T00:00:00Z>",
            "NotBefore: <certificate-expiry:2019-01-01T00:00:00Z>",
            "NotAfter: <certificate-expiry:2019-01-01T00:00:00Z> Extra details"
    })
    void shouldKeepCertificateMessageExactWhenPlaceholderIsNotTheCompleteMessage(String message) throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(CertificateExpiredException.class, message, null));

        assertMismatch(expected, new CertificateExpiredException("NotAfter: " + Date.from(Instant.parse("2019-01-01T00:00:00Z"))));
    }

    @Test
    void shouldRequireExactCertificateExpiryWhenPlaceholderIsAbsent() throws JsonProcessingException {
        String message = "NotAfter: Tue Jan 01 00:00:00 UTC 2019";
        SnapshotFailureExpectation expected = expectation(failure(CertificateExpiredException.class, message, null));

        assertMatch(expected, new CertificateExpiredException(message));
        assertMismatch(expected, new CertificateExpiredException("NotAfter: Tue Jan 01 03:00:00 MSK 2019"));
    }

    @ParameterizedTest
    @ValueSource(ints = {3000, 3001, 6123})
    void shouldMatchElapsedTimeWhenKafkaBatchTimeoutUsesPlaceholder(int elapsedMillis) throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                TimeoutException.class,
                "Expiring 1 record(s) for test-topic-1:<elapsed-ms> ms has passed since batch creation", null
        ));

        assertMatch(expected, new TimeoutException(
                "Expiring 1 record(s) for test-topic-1:" + elapsedMillis + " ms has passed since batch creation"
        ));
    }

    @Test
    void shouldKeepKafkaTimeoutDetailsExactWhenElapsedTimeUsesPlaceholder() throws JsonProcessingException {
        String expectedMessage = "Expiring 1 record(s) for test-topic-1:<elapsed-ms> ms has passed since batch creation";
        SnapshotFailureExpectation expected = expectation(failure(TimeoutException.class, expectedMessage, null));

        assertMismatch(expected, new TimeoutException(
                "Expiring 2 record(s) for test-topic-1:3001 ms has passed since batch creation"
        ));
        assertMismatch(expected, new TimeoutException(
                "Expiring 1 record(s) for test-topic-2:3001 ms has passed since batch creation"
        ));
        assertMismatch(expected, new TimeoutException("Topic test-topic not present in metadata after 3000 ms."));
        assertMismatch(expected, new TimeoutException(
                "Expiring 1 record(s) for test-topic-1:3001 ms has passed since batch creation", new RuntimeException("cause")
        ));
        SnapshotFailureExpectation otherType = expectation(failure(IllegalStateException.class, expectedMessage, null));
        assertMismatch(otherType, new IllegalStateException(
                "Expiring 1 record(s) for test-topic-1:3001 ms has passed since batch creation"
        ));
    }

    @Test
    void shouldRequireExactKafkaElapsedTimeWhenPlaceholderIsAbsent() throws JsonProcessingException {
        String message = "Expiring 1 record(s) for test-topic-1:3000 ms has passed since batch creation";
        SnapshotFailureExpectation expected = expectation(failure(TimeoutException.class, message, null));

        assertMatch(expected, new TimeoutException(message));
        assertMismatch(expected, new TimeoutException(message.replace(":3000 ", ":3001 ")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1", "https://127.0.0.1", "http://localhost", "https://localhost"})
    void shouldMatchEphemeralPortsWhenExpectedMessageContainsPlaceholder(String address) throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class, "POST " + address + ":<loopback-port>/token\ninvalid_grant", null
        ));

        for (int port : new int[] {31001, 49152}) {
            assertMatch(expected, new IllegalStateException("POST " + address + ':' + port + "/token\ninvalid_grant"));
        }
    }

    @Test
    void shouldRequireExactPortWhenPlaceholderIsAbsent() throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class, "POST http://localhost:31001/token", null
        ));

        assertMatch(expected, new IllegalStateException("POST http://localhost:31001/token"));
        assertMismatch(expected, new IllegalStateException("POST http://localhost:49152/token"));
    }

    @Test
    void shouldNormalizeOnlyExplicitlyMarkedPortsWhenMessageContainsMultipleUrls() throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class,
                "POST http://localhost:<loopback-port>/token; proxy http://localhost:8080/proxy",
                null
        ));

        assertMatch(expected, new IllegalStateException("POST http://localhost:31001/token; proxy http://localhost:8080/proxy"));
        assertMismatch(expected, new IllegalStateException("POST http://localhost:31001/token; proxy http://localhost:9090/proxy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://localhost.example.com", "http://127.0.0.2"})
    void shouldKeepRemotePortsExactWhenExpectedMessageContainsPlaceholder(String address) throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class, "POST " + address + ":<loopback-port>/token", null
        ));

        assertMismatch(expected, new IllegalStateException("POST " + address + ":31001/token"));
    }

    @Test
    void shouldRejectDifferentErrorTextWhenExpectedMessageContainsPlaceholder() throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class, "POST http://localhost:<loopback-port>/token\ninvalid_grant", null
        ));

        assertMismatch(expected, new IllegalStateException("POST http://localhost:31001/token\ninvalid_client"));
    }

    @Test
    void shouldCheckNestedTypesAndCausesWhenExpectedMessageContainsPlaceholder() throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class, "Publication failed", failure(
                        IllegalArgumentException.class, "POST http://127.0.0.1:<loopback-port>/token", null
                )
        ));
        IllegalArgumentException cause = new IllegalArgumentException("POST http://127.0.0.1:31001/token");

        assertMatch(expected, new IllegalStateException("Publication failed", cause));
        assertMismatch(expected, new IllegalStateException("Publication failed"));
        assertMismatch(expected, new IllegalStateException(
                "Publication failed", new IllegalStateException(cause.getMessage())
        ));
        assertMismatch(expected, new IllegalStateException(
                "Publication failed", new IllegalArgumentException(cause.getMessage(), new RuntimeException("Extra cause"))
        ));
    }

    @Test
    void shouldRequireEachCauseToOptInWhenParentContainsPlaceholder() throws JsonProcessingException {
        SnapshotFailureExpectation expected = expectation(failure(
                IllegalStateException.class, "POST http://localhost:<loopback-port>/token", failure(
                        IllegalArgumentException.class, "POST http://localhost:31001/token", null
                )
        ));

        assertMismatch(expected, new IllegalStateException(
                "POST http://localhost:49152/token", new IllegalArgumentException("POST http://localhost:49152/token")
        ));
    }

    @Test
    void shouldPreserveExchangeIdNormalizationWhenExpectedMessageContainsPlaceholder() throws JsonProcessingException {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setExchangeId("snapshot-exchange-id");
        CamelExchangeException actual = new CamelExchangeException("POST http://localhost:31001/token", exchange);
        SnapshotFailureExpectation expected = expectation(failure(
                CamelExchangeException.class,
                "POST http://localhost:<loopback-port>/token. Exchange[<exchange-id>]",
                null
        ));

        assertMatch(expected, actual);
    }

    private SnapshotFailureExpectation expectation(ObjectNode failure) throws JsonProcessingException {
        ObjectNode invocation = objectMapper.createObjectNode();
        invocation.put("id", "failure-assertion");
        invocation.set("expectedFailure", failure);
        return objectMapper.treeToValue(invocation, SnapshotScenarioInvocation.class).getExpectedFailure();
    }

    private ObjectNode failure(Class<? extends Throwable> type, String message, ObjectNode cause) {
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("type", type.getName());
        failure.put("message", message);
        failure.set("cause", cause);
        return failure;
    }

    private void assertMatch(SnapshotFailureExpectation expected, Throwable actual) {
        assertTrue(SnapshotFailureAssertions.matches(expected, actual));
        assertDoesNotThrow(() -> SnapshotFailureAssertions.assertMatches(expected, actual, "Failure assertion"));
    }

    private void assertMismatch(SnapshotFailureExpectation expected, Throwable actual) {
        assertFalse(SnapshotFailureAssertions.matches(expected, actual));
        assertThrows(AssertionError.class, () -> SnapshotFailureAssertions.assertMatches(expected, actual, "Failure assertion"));
    }
}

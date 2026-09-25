package org.qubership.integration.platform.engine.routes.support;

import org.apache.camel.CamelExchangeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFailureExpectation;

import java.security.cert.CertificateExpiredException;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class SnapshotFailureAssertions {
    private static final String LOOPBACK_PORT = "<loopback-port>";
    private static final Pattern LOOPBACK_URL_PORT = Pattern.compile(
            "https?://(?:localhost|127\\.0\\.0\\.1):" + Pattern.quote(LOOPBACK_PORT) + "(?=[/?#\\s]|$)"
    );
    private static final String ELAPSED_MILLIS = "<elapsed-ms>";
    private static final Pattern KAFKA_BATCH_EXPIRATION = Pattern.compile(
            "^(Expiring [0-9]+ record\\(s\\) for .+:)[0-9]+( ms has passed since batch creation)$"
    );
    private static final Pattern CERTIFICATE_EXPIRY = Pattern.compile("NotAfter: <certificate-expiry:([^>]+)>");

    private SnapshotFailureAssertions() {
    }

    public static boolean matches(SnapshotFailureExpectation expected, Throwable actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return expected.getType().equals(actual.getClass().getName())
                && Objects.equals(expected.getMessage(), normalizedFailureMessage(actual, expected.getMessage()))
                && matches(expected.getCause(), actual.getCause());
    }

    public static void assertMatches(
            SnapshotFailureExpectation expected,
            Throwable actual,
            String description
    ) {
        assertMatches(expected, actual, description, "failure");
    }

    private static void assertMatches(
            SnapshotFailureExpectation expected,
            Throwable actual,
            String description,
            String failurePath
    ) {
        if (expected == null) {
            assertNull(
                    actual,
                    () -> description + " failed with an unexpected exception at '" + failurePath + "': " + actual
            );
            return;
        }
        assertNotNull(
                actual,
                () -> description + " did not provide the expected exception at '" + failurePath + "'."
        );
        assertEquals(
                expected.getType(),
                actual.getClass().getName(),
                () -> description + " failed with an unexpected exception type at '" + failurePath + "'."
        );
        assertEquals(
                expected.getMessage(),
                normalizedFailureMessage(actual, expected.getMessage()),
                () -> description + " failed with an unexpected exception message at '" + failurePath + "'."
        );
        assertMatches(expected.getCause(), actual.getCause(), description, failurePath + ".cause");
    }

    private static String normalizedFailureMessage(Throwable failure, String expectedMessage) {
        String message = failure.getMessage();
        if (failure instanceof CamelExchangeException camelFailure && camelFailure.getExchange() != null) {
            message = message.replace(
                    "Exchange[" + camelFailure.getExchange().getExchangeId() + "]",
                    "Exchange[<exchange-id>]"
            );
        }
        if (message != null && expectedMessage != null && expectedMessage.contains(LOOPBACK_PORT)) {
            message = normalizeLoopbackPorts(message, expectedMessage);
        }
        if (failure instanceof TimeoutException && message != null && expectedMessage != null
                && expectedMessage.contains(ELAPSED_MILLIS)) {
            message = KAFKA_BATCH_EXPIRATION.matcher(message).replaceFirst("$1" + ELAPSED_MILLIS + "$2");
        }
        if (failure instanceof CertificateExpiredException && expectedMessage != null) {
            Matcher expiry = CERTIFICATE_EXPIRY.matcher(expectedMessage);
            if (expiry.matches()) {
                String expiryMessage = "NotAfter: " + Date.from(Instant.parse(expiry.group(1)));
                if (expiryMessage.equals(message)) {
                    return expectedMessage;
                }
            }
        }
        return message;
    }

    private static String normalizeLoopbackPorts(String message, String expectedMessage) {
        Matcher placeholders = LOOPBACK_URL_PORT.matcher(expectedMessage);
        StringBuilder pattern = new StringBuilder();
        int position = 0;
        while (placeholders.find()) {
            int portStart = placeholders.end() - LOOPBACK_PORT.length();
            pattern.append(Pattern.quote(expectedMessage.substring(position, portStart))).append("[0-9]+");
            position = placeholders.end();
        }
        pattern.append(Pattern.quote(expectedMessage.substring(position)));
        return Pattern.matches(pattern.toString(), message) ? expectedMessage : message;
    }
}

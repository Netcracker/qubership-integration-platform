package org.qubership.integration.platform.engine.routes.support;

import org.apache.camel.CamelExchangeException;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFailureExpectation;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class SnapshotFailureAssertions {
    private SnapshotFailureAssertions() {
    }

    public static boolean matches(SnapshotFailureExpectation expected, Throwable actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return expected.getType().equals(actual.getClass().getName())
                && Objects.equals(expected.getMessage(), normalizedFailureMessage(actual))
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
                normalizedFailureMessage(actual),
                () -> description + " failed with an unexpected exception message at '" + failurePath + "'."
        );
        assertMatches(expected.getCause(), actual.getCause(), description, failurePath + ".cause");
    }

    private static String normalizedFailureMessage(Throwable failure) {
        if (failure instanceof CamelExchangeException camelFailure && camelFailure.getExchange() != null) {
            return failure.getMessage().replace(
                    "Exchange[" + camelFailure.getExchange().getExchangeId() + "]",
                    "Exchange[<exchange-id>]"
            );
        }
        return failure.getMessage();
    }
}

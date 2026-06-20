package org.qubership.integration.platform.engine.testutils;

import org.qubership.integration.platform.engine.rest.v1.dto.ExceptionDTO;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class ErrorHandlingTestUtils {

    public static final String NO_STACKTRACE_AVAILABLE_MESSAGE = "No Stacktrace Available";

    private ErrorHandlingTestUtils() {
    }

    public static ExceptionDTO assertExceptionDto(Object entity) {
        return assertInstanceOf(ExceptionDTO.class, entity);
    }

    public static void assertValidTimestamp(String value) {
        assertNotNull(value);
        assertDoesNotThrow(() -> Timestamp.valueOf(value));
    }
}

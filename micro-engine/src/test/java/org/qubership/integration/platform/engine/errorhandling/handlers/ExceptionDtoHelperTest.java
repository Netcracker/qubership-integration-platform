package org.qubership.integration.platform.engine.errorhandling.handlers;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.EngineRuntimeException;
import org.qubership.integration.platform.engine.rest.v1.dto.ExceptionDTO;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.testutils.ErrorHandlingTestUtils.NO_STACKTRACE_AVAILABLE_MESSAGE;
import static org.qubership.integration.platform.engine.testutils.ErrorHandlingTestUtils.assertValidTimestamp;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ExceptionDtoHelperTest {

    @Test
    void shouldBuildExceptionDtoWithStacktraceByDefault() {
        RuntimeException exception = new RuntimeException("General failure");

        ExceptionDTO result = ExceptionDtoHelper.getExceptionDTO(exception);

        assertEquals("General failure", result.getErrorMessage());
        assertTrue(result.getStacktrace().contains(RuntimeException.class.getName()));
        assertTrue(result.getStacktrace().contains("General failure"));
        assertValidTimestamp(result.getErrorDate());
    }

    @Test
    void shouldBuildExceptionDtoWithoutStacktraceWhenStacktraceDisabled() {
        RuntimeException exception = new RuntimeException("General failure");

        ExceptionDTO result = ExceptionDtoHelper.getExceptionDTO(exception, false);

        assertEquals("General failure", result.getErrorMessage());
        assertEquals(NO_STACKTRACE_AVAILABLE_MESSAGE, result.getStacktrace());
        assertValidTimestamp(result.getErrorDate());
    }

    @Test
    void shouldUseOriginalExceptionStacktraceWhenEngineRuntimeExceptionHasOriginalException() {
        EngineRuntimeException exception = mock(EngineRuntimeException.class);
        RuntimeException originalException = new RuntimeException("Original failure");

        when(exception.getMessage()).thenReturn("Wrapped failure");
        when(exception.getOriginalException()).thenReturn(originalException);

        ExceptionDTO result = ExceptionDtoHelper.getExceptionDTO(exception);

        assertEquals("Wrapped failure", result.getErrorMessage());
        assertTrue(result.getStacktrace().contains(RuntimeException.class.getName()));
        assertTrue(result.getStacktrace().contains("Original failure"));
        assertValidTimestamp(result.getErrorDate());
    }

    @Test
    void shouldKeepNoStacktraceAvailableWhenEngineRuntimeExceptionHasNoOriginalException() {
        EngineRuntimeException exception = mock(EngineRuntimeException.class);

        when(exception.getMessage()).thenReturn("Wrapped failure");
        when(exception.getOriginalException()).thenReturn(null);

        ExceptionDTO result = ExceptionDtoHelper.getExceptionDTO(exception);

        assertEquals("Wrapped failure", result.getErrorMessage());
        assertEquals(NO_STACKTRACE_AVAILABLE_MESSAGE, result.getStacktrace());
        assertValidTimestamp(result.getErrorDate());
    }

    @Test
    void shouldUseExceptionMessageAsErrorMessage() {
        Exception exception = new Exception("Validation failed");

        ExceptionDTO result = ExceptionDtoHelper.getExceptionDTO(exception, false);

        assertEquals("Validation failed", result.getErrorMessage());
    }

    @Test
    void shouldAllowNullExceptionMessage() {
        Exception exception = new Exception();

        ExceptionDTO result = ExceptionDtoHelper.getExceptionDTO(exception, false);

        assertEquals(null, result.getErrorMessage());
        assertEquals(NO_STACKTRACE_AVAILABLE_MESSAGE, result.getStacktrace());
        assertValidTimestamp(result.getErrorDate());
    }
}

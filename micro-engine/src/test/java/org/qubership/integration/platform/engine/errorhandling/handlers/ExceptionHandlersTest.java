package org.qubership.integration.platform.engine.errorhandling.handlers;

import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.errorhandling.LoggingMaskingException;
import org.qubership.integration.platform.engine.rest.v1.dto.ExceptionDTO;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.testutils.ErrorHandlingTestUtils.NO_STACKTRACE_AVAILABLE_MESSAGE;
import static org.qubership.integration.platform.engine.testutils.ErrorHandlingTestUtils.assertExceptionDto;
import static org.qubership.integration.platform.engine.testutils.ErrorHandlingTestUtils.assertValidTimestamp;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ExceptionHandlersTest {

    @Test
    void shouldMapEntityNotFoundExceptionToNotFoundResponseWithoutStacktrace() {
        EntityNotFoundException exception = new EntityNotFoundException("Entity was not found");
        EntityNotFoundExceptionHandler handler = new EntityNotFoundExceptionHandler();

        Response response = handler.toResponse(exception);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());

        ExceptionDTO entity = assertExceptionDto(response.getEntity());
        assertEquals("Entity was not found", entity.getErrorMessage());
        assertEquals(NO_STACKTRACE_AVAILABLE_MESSAGE, entity.getStacktrace());
        assertValidTimestamp(entity.getErrorDate());
    }

    @Test
    void shouldMapGeneralExceptionToInternalServerErrorResponseWithStacktrace() {
        Exception exception = new Exception("Unexpected failure");
        GeneralExceptionHandler handler = new GeneralExceptionHandler();

        Response response = handler.toResponse(exception);

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());

        ExceptionDTO entity = assertExceptionDto(response.getEntity());
        assertEquals("Unexpected failure", entity.getErrorMessage());
        assertTrue(entity.getStacktrace().contains(Exception.class.getName()));
        assertTrue(entity.getStacktrace().contains("Unexpected failure"));
        assertValidTimestamp(entity.getErrorDate());
    }

    @Test
    void shouldMapKubeApiExceptionToBadRequestResponseWithOriginalExceptionStacktrace() {
        KubeApiException exception = mock(KubeApiException.class);
        RuntimeException originalException = new RuntimeException("Original Kubernetes API failure");
        KubeApiExceptionHandler handler = new KubeApiExceptionHandler();

        when(exception.getMessage()).thenReturn("Kubernetes API failed");
        when(exception.getOriginalException()).thenReturn(originalException);

        Response response = handler.toResponse(exception);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        ExceptionDTO entity = assertExceptionDto(response.getEntity());
        assertEquals("Kubernetes API failed", entity.getErrorMessage());
        assertTrue(entity.getStacktrace().contains(RuntimeException.class.getName()));
        assertTrue(entity.getStacktrace().contains("Original Kubernetes API failure"));
        assertValidTimestamp(entity.getErrorDate());
    }

    @Test
    void shouldMapLoggingMaskingExceptionToBadRequestResponseWithOriginalExceptionStacktrace() {
        LoggingMaskingException exception = mock(LoggingMaskingException.class);
        RuntimeException originalException = new RuntimeException("Original logging masking failure");
        LoggingMaskingExceptionHandler handler = new LoggingMaskingExceptionHandler();

        when(exception.getMessage()).thenReturn("Logging masking failed");
        when(exception.getOriginalException()).thenReturn(originalException);

        Response response = handler.toResponse(exception);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        ExceptionDTO entity = assertExceptionDto(response.getEntity());
        assertEquals("Logging masking failed", entity.getErrorMessage());
        assertTrue(entity.getStacktrace().contains(RuntimeException.class.getName()));
        assertTrue(entity.getStacktrace().contains("Original logging masking failure"));
        assertValidTimestamp(entity.getErrorDate());
    }
}

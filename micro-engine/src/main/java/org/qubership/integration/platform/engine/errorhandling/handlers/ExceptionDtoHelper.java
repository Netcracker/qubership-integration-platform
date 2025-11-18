package org.qubership.integration.platform.engine.errorhandling.handlers;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.qubership.integration.platform.engine.errorhandling.EngineRuntimeException;
import org.qubership.integration.platform.engine.rest.v1.dto.ExceptionDTO;

import java.sql.Timestamp;

public class ExceptionDtoHelper {
    private static final String NO_STACKTRACE_AVAILABLE_MESSAGE = "No Stacktrace Available";

    public static ExceptionDTO getExceptionDTO(Exception exception) {
        return getExceptionDTO(exception, true);
    }

    public static ExceptionDTO getExceptionDTO(Exception exception, boolean addStacktrace) {
        String message = exception.getMessage();
        String stacktrace = NO_STACKTRACE_AVAILABLE_MESSAGE;
        if (addStacktrace) {
            if (exception instanceof EngineRuntimeException systemCatalogRuntimeException) {
                if (systemCatalogRuntimeException.getOriginalException() != null) {
                    stacktrace = ExceptionUtils.getStackTrace(
                            systemCatalogRuntimeException.getOriginalException());
                }
            } else {
                stacktrace = ExceptionUtils.getStackTrace(exception);
            }
        }

        return ExceptionDTO
                .builder()
                .errorMessage(message)
                .stacktrace(stacktrace)
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
    }
}

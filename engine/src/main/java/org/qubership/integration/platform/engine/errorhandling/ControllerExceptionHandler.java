/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.errorhandling;

import jakarta.persistence.EntityNotFoundException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.qubership.integration.platform.engine.rest.v1.dto.ExceptionDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.Timestamp;

@ControllerAdvice
public class ControllerExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String NO_STACKTRACE_AVAILABLE_MESSAGE = "No Stacktrace Available";

    @ExceptionHandler
    public ResponseEntity<ExceptionDTO> handleGeneralException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ExceptionDTO> handleEntityNotFoundException(EntityNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(getExceptionDTO(exception, false));
    }

    @ExceptionHandler(value = KubeApiException.class)
    public final ResponseEntity<ExceptionDTO> handleKubeApiException(
        EngineRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = LoggingMaskingException.class)
    public final ResponseEntity<ExceptionDTO> handleLoggingMaskingException(
        EngineRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

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

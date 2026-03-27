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

package org.qubership.integration.platform.sessions.exception;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.qubership.integration.platform.sessions.dto.ExceptionDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.sql.Timestamp;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final String NO_STACKTRACE_AVAILABLE_MESSAGE = "No Stacktrace Available";

    @ExceptionHandler
    public ResponseEntity<ExceptionDTO> handleGeneralException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(SessionsNotFoundException.class)
    public ResponseEntity<ExceptionDTO> sessionsNotFoundExceptionHandler(SessionsNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ExceptionDTO> searchExceptionHandler(SearchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ImportException.class)
    public ResponseEntity<ExceptionDTO> importExceptionHandler(ImportException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ImportConflictException.class)
    public ResponseEntity<ExceptionDTO> importConflictExceptionHandler(ImportConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(getExceptionDTO(exception));
    }

    private ExceptionDTO getExceptionDTO(Exception exception) {
        String message = exception.getMessage();
        String stacktrace = NO_STACKTRACE_AVAILABLE_MESSAGE;
        if (exception instanceof SessionsRuntimeException) {
            SessionsRuntimeException sessionsRuntimeException = (SessionsRuntimeException) exception;
            if (sessionsRuntimeException.getOriginalException() != null) {
                stacktrace = ExceptionUtils.getStackTrace(sessionsRuntimeException.getOriginalException());
            }
        } else {
            stacktrace = ExceptionUtils.getStackTrace(exception);
        }

        return ExceptionDTO
                .builder()
                .errorMessage(message)
                .stacktrace(stacktrace)
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
    }
}

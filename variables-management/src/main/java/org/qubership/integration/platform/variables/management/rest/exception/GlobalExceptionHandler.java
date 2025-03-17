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

package org.qubership.integration.platform.variables.management.rest.exception;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.qubership.integration.platform.variables.management.consul.ConsulException;
import org.qubership.integration.platform.variables.management.consul.TxnConflictException;
import org.qubership.integration.platform.variables.management.kubernetes.KubeApiException;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.Timestamp;
import java.util.stream.Collectors;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final String NO_STACKTRACE_AVAILABLE_MESSAGE = "No Stacktrace Available, check the logs for more details";

    @ExceptionHandler
    public ResponseEntity<ExceptionDTO> handleGeneralException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ExceptionDTO> handleEntityNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(EntityExistsException.class)
    public ResponseEntity<ExceptionDTO> handleEntityExistsException(EntityExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(getExceptionDTOWithoutStacktrace(exception));
    }

    @ExceptionHandler(KubeApiException.class)
    public ResponseEntity<ExceptionDTO> handleApiException(VariablesManagementRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(EmptyVariableFieldException.class)
    public ResponseEntity<ExceptionDTO> handleEmptyVariableFieldException(EmptyVariableFieldException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(MalformedVariableNameException.class)
    public ResponseEntity<ExceptionDTO> handleMalformedVariableNameException(MalformedVariableNameException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(SecuredVariablesException.class)
    public ResponseEntity<ExceptionDTO> handleSecuredVariablesException(SecuredVariablesException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ConsulException.class)
    public ResponseEntity<ExceptionDTO> handleConsulException(ConsulException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTOWithoutStacktrace(exception));
    }

    @ExceptionHandler(TxnConflictException.class)
    public ResponseEntity<ExceptionDTO> handleTxnConflictException(TxnConflictException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTOWithoutStacktrace(exception));
    }

    @ExceptionHandler(SecuredVariablesNotFoundException.class)
    public ResponseEntity<ExceptionDTO> handleSecuredVariablesNotFoundException(SecuredVariablesNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ExceptionDTO> handleConstraintViolationException(ConstraintViolationException exception) {
        String errorMessage = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath().toString() + " " + violation.getMessage())
                .collect(Collectors.joining(", ", "Invalid request content: [", "]"));
        ExceptionDTO exceptionDTO = ExceptionDTO.builder()
                .errorMessage(errorMessage)
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exceptionDTO);
    }

    @Override
    public ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        String errorMessage = exception.getBindingResult()
                .getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", ", "Invalid request content: [", "]"));

        ExceptionDTO exceptionDTO = ExceptionDTO.builder()
                .errorMessage(errorMessage)
                .stacktrace(ExceptionUtils.getStackTrace(exception))
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exceptionDTO);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (body instanceof ProblemDetail problemDetail) {
            ExceptionDTO exceptionDTO = ExceptionDTO.builder()
                    .errorMessage(problemDetail.getDetail())
                    .stacktrace(NO_STACKTRACE_AVAILABLE_MESSAGE)
                    .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                    .build();
            return new ResponseEntity<>(exceptionDTO, headers, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ExceptionDTO getExceptionDTO(Exception exception) {
        String message = exception.getMessage();
        String stacktrace = NO_STACKTRACE_AVAILABLE_MESSAGE;
        if (exception instanceof VariablesManagementRuntimeException variablesManagementRuntimeException) {
            if (variablesManagementRuntimeException.getOriginalException() != null) {
                stacktrace = ExceptionUtils.getStackTrace(variablesManagementRuntimeException.getOriginalException());
            }
        } else {
            stacktrace = ExceptionUtils.getStackTrace(exception);
        }
        log.error("An error occurred: {}. Stacktrace: {}", message, stacktrace);
        return ExceptionDTO
                .builder()
                .errorMessage(message)
                .stacktrace(NO_STACKTRACE_AVAILABLE_MESSAGE)
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
    }

    private ExceptionDTO getExceptionDTOWithoutStacktrace(Exception exception) {
        String message = exception.getMessage();

        return ExceptionDTO
                .builder()
                .errorMessage(message)
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
    }
}

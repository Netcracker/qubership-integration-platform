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

package org.qubership.integration.platform.runtime.catalog.exception;

import com.github.jknack.handlebars.internal.lang3.StringUtils;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.qubership.integration.platform.runtime.catalog.consul.exception.ConsulException;
import org.qubership.integration.platform.runtime.catalog.consul.exception.TxnConflictException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.*;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.CatalogRuntimeException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ChainDifferenceClientException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ChainDifferenceException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ComparisonEntityNotFoundException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ImportInstructionsExternalException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ImportInstructionsInternalException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportWarningException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.DetailedDesignInternalException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateDataBuilderException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateDataEscapingException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ddsgenerator.TemplateProcessingException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.ValidationAlreadyInProgressUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    public ResponseEntity<ExceptionDTO> handleGeneralException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ExceptionDTO> handleEntityNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ChainExportException.class)
    public ResponseEntity<ExceptionDTO> handleChainExportException(ChainExportException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ApiSpecificationExportException.class)
    public ResponseEntity<ExceptionDTO> handleApiSpecificationExportException(ApiSpecificationExportException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(EntityExistsException.class)
    public ResponseEntity<ExceptionDTO> handleEntityExistsException(EntityExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(SnapshotCreationException.class)
    public ResponseEntity<ExceptionDTO> handleSnapshotCreationException(SnapshotCreationException exception) {
        Map<String, Object> details = StringUtils.isBlank(exception.getElementId())
                ? null
                : Map.of(
                "chainId", exception.getChainId(),
                "elementId", exception.getElementId(),
                "elementName", exception.getElementName()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception, details));
    }

    @ExceptionHandler(DeploymentProcessingException.class)
    public ResponseEntity<ExceptionDTO> handleDeploymentDeletionException(DeploymentProcessingException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = {SpecificationImportException.class, IOException.class})
    public ResponseEntity<ExceptionDTO> handleImportException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = {SpecificationImportWarningException.class})
    public ResponseEntity<ExceptionDTO> handleImportWarningException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.OK).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = EnvironmentSetUpException.class)
    public ResponseEntity<ExceptionDTO> handleEnvironmentSetUpException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = ValidationAlreadyInProgressUnexpectedException.class)
    public final ResponseEntity<ExceptionDTO> handleDatabaseSystemException(ValidationAlreadyInProgressUnexpectedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = DiagnosticValidationUnexpectedException.class)
    public final ResponseEntity<ExceptionDTO> handleDatabaseSystemException(DiagnosticValidationUnexpectedException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ComparisonEntityNotFoundException.class)
    public ResponseEntity<ExceptionDTO> handleComparisonEntityNotFoundException(ComparisonEntityNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ChainDifferenceClientException.class)
    public ResponseEntity<ExceptionDTO> handleChainDifferenceClientException(ChainDifferenceClientException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ChainDifferenceException.class)
    public ResponseEntity<ExceptionDTO> handleChainDifferenceException(ChainDifferenceException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ImportInstructionsInternalException.class)
    public ResponseEntity<ExceptionDTO> handleImportInstructionsInternalException(ImportInstructionsInternalException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ImportInstructionsExternalException.class)
    public ResponseEntity<ExceptionDTO> handleImportInstructionsExternalException(ImportInstructionsExternalException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
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

    @ExceptionHandler(value = SpecificationNotFoundException.class)
    public ResponseEntity<ExceptionDTO> handleSpecificationNotFoundException() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(value = SpecificationProtocolDifferentException.class)
    public ResponseEntity<ExceptionDTO> handleProtocolException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = BadRequestException.class)
    public ResponseEntity<ExceptionDTO> handleBadRequestException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(value = MicroserviceConnectivityException.class)
    public ResponseEntity<ExceptionDTO> handleMicroserviceConnectivityException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(KubeApiException.class)
    public ResponseEntity<ExceptionDTO> handleApiException(CatalogRuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ChainMigrationException.class)
    public ResponseEntity<ExceptionDTO> handleChainMigrationException(ChainMigrationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ChainModificationException.class)
    public ResponseEntity<ExceptionDTO> handleChainModificationException(ChainModificationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(FolderMoveException.class)
    public ResponseEntity<ExceptionDTO> handleFolderMoveException(FolderMoveException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ExceptionDTO> handleFolderMoveException(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(TxnConflictException.class)
    public ResponseEntity<ExceptionDTO> handleTxnConflictException(TxnConflictException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(ConsulException.class)
    public ResponseEntity<ExceptionDTO> handleConsulException(ConsulException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(DetailedDesignInternalException.class)
    public ResponseEntity<ExceptionDTO> handleConsulException(DetailedDesignInternalException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(TemplateDataBuilderException.class)
    public ResponseEntity<ExceptionDTO> handleConsulException(TemplateDataBuilderException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(TemplateDataEscapingException.class)
    public ResponseEntity<ExceptionDTO> handleConsulException(TemplateDataEscapingException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(TemplateProcessingException.class)
    public ResponseEntity<ExceptionDTO> handleConsulException(TemplateProcessingException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ExceptionDTO> handleDataIntegrityViolationException(DataIntegrityViolationException exception) {
        if (exception.getCause() instanceof org.hibernate.exception.ConstraintViolationException constraintException) {
            if (ImportInstructionsService.UNIQUE_OVERRIDE_DB_CONSTRAINT_NAME.equals(constraintException.getConstraintName())) {
                return handleImportInstructionsExternalException(new ImportInstructionsValidationException(
                        extractConstraintMessage(constraintException)
                ));
            }
            if (ImportInstructionsService.OVERRIDE_ACTION_DB_CONSTRAINT_NAME.equals(constraintException.getConstraintName())) {
                return handleImportInstructionsExternalException(new ImportInstructionsValidationException(
                        "Overridden By must not be specified for instruction with non OVERRIDE action"
                ));
            }
        }
        return handleGeneralException(exception);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String errorMessage = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fieldError) {
                        return fieldError.getField() + " " + fieldError.getDefaultMessage();
                    }
                    return error.getDefaultMessage();
                })
                .collect(Collectors.joining(", ", "Invalid request content: [", "]"));
        ExceptionDTO exceptionDTO = ExceptionDTO.builder()
                .errorMessage(errorMessage)
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exceptionDTO);
    }

    private ExceptionDTO getExceptionDTO(Exception exception) {
        return getExceptionDTO(exception, null);
    }

    private ExceptionDTO getExceptionDTO(Exception exception, Map<String, Object> details) {
        return ExceptionDTO
                .builder()
                .errorMessage(exception.getMessage())
                .errorDate(new Timestamp(System.currentTimeMillis()).toString())
                .details(details)
                .build();
    }

    private String extractConstraintMessage(org.hibernate.exception.ConstraintViolationException exception) {
        Optional<ServerErrorMessage> serverErrorMessage = Optional.empty();
        if (exception.getSQLException() instanceof PSQLException psqlException) {
            serverErrorMessage = Optional.ofNullable(psqlException.getServerErrorMessage());
        } else if (exception.getSQLException() != null && exception.getSQLException().getNextException() instanceof PSQLException psqlException) {
            serverErrorMessage = Optional.ofNullable(psqlException.getServerErrorMessage());
        }
        return serverErrorMessage
                .map(errorMessage -> errorMessage.getDetail() + " already overrides or overridden by another chain")
                .orElse("Instruction for the chain already exist");
    }
}

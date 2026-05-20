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

package org.qubership.integration.platform.runtime.catalog.rest.v1.exception;

import com.github.jknack.handlebars.internal.lang3.StringUtils;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.qubership.integration.platform.catalog.exception.*;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ApiSpecificationExportException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ChainExportException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.EnvironmentSetUpException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.ValidationAlreadyInProgressUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(KubeApiException.class)
    public ResponseEntity<ExceptionDTO> handleApiException(KubeApiException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(getExceptionDTO(exception));
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ExceptionDTO> handleDataIntegrityViolationException(DataIntegrityViolationException exception) {
        if (exception.getCause() instanceof org.hibernate.exception.ConstraintViolationException constraintException) {
            if (ImportInstructionsService.UNIQUE_OVERRIDE_DB_CONSTRAINT_NAME.equals(constraintException.getConstraintName())) {
                return handleImportInstructionsExternalException(new ImportInstructionsExternalException(
                        extractConstraintMessage(constraintException)
                ));
            }
        }
        return handleGeneralException(exception);
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

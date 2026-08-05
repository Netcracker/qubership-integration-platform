package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

/**
 * A service that cannot be exported. Message only: unlike {@code ServiceImportException}, whose id and name build a
 * per-service error row, the export path has no such consumer — {@code GlobalExceptionHandler} renders the message.
 */
public class ServiceExportException extends RuntimeException {

    public ServiceExportException(String message) {
        super(message);
    }
}

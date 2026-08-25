package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

/**
 * A service that cannot be exported. Message only: unlike {@code ServiceImportException}, whose id and name build a
 * per-service error row, the export has no result rows — it drops the service, logs this message, and produces the
 * rest of the archive.
 */
public class ServiceExportException extends RuntimeException {

    public ServiceExportException(String message) {
        super(message);
    }
}

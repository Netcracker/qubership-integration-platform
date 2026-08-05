package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

/** A service that cannot be exported, named so the caller can find the row that blocks the archive. */
public class ServiceExportException extends RuntimeException {
    private final String serviceId;
    private final String serviceName;

    public ServiceExportException(String serviceId, String serviceName, String message) {
        super(message);
        this.serviceId = serviceId;
        this.serviceName = serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getServiceName() {
        return serviceName;
    }
}

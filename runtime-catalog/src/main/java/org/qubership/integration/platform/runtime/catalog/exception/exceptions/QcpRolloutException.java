package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp.QcpClientError;

@Getter
public class QcpRolloutException extends RuntimeException {

    private final QcpClientError error;

    public QcpRolloutException(QcpClientError error, String message) {
        super(message);
        this.error = error;
    }
}

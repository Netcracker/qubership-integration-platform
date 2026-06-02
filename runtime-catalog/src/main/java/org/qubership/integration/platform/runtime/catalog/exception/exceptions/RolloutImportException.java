package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportClientError;

@Getter
public class RolloutImportException extends RuntimeException {

    private final RolloutImportClientError error;

    public RolloutImportException(RolloutImportClientError error, String message) {
        super(message);
        this.error = error;
    }
}

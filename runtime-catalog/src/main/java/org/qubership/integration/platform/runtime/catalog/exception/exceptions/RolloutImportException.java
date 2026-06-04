package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.exception.ErrorCode;
import org.qubership.integration.platform.runtime.catalog.model.ErrorCodePayload;

import java.util.List;

@Getter
public class RolloutImportException extends RuntimeException {

    private final List<ErrorCodePayload> errors;

    public RolloutImportException(ErrorCode errorCode) {
        this(errorCode.toPayload());
    }

    public RolloutImportException(ErrorCodePayload error) {
        this(List.of(error));
    }

    public RolloutImportException(List<ErrorCodePayload> errors) {
        super(resolveExceptionMessage(errors));
        this.errors = List.copyOf(errors);
    }

    private static String resolveExceptionMessage(List<ErrorCodePayload> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Rollout import failed";
        }
        ErrorCodePayload first = errors.getFirst();
        if (first.getMessage() != null && !first.getMessage().isBlank()) {
            return first.getMessage();
        }
        if (first.getReason() != null && !first.getReason().isBlank()) {
            return first.getReason();
        }
        return "Rollout import failed";
    }
}

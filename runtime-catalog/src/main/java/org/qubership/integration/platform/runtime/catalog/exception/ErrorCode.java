package org.qubership.integration.platform.runtime.catalog.exception;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.model.ErrorCodePayload;

import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.ROLLOUT_ERROR_CODE_PREFIX;


public enum ErrorCode {


    UNEXPECTED_ROLLOUT_IMPORT_ERROR(ROLLOUT_ERROR_CODE_PREFIX, "-0000", "Unexpected rollout import error", ""),
    IMPORT_FAILED_ERROR(ROLLOUT_ERROR_CODE_PREFIX, "-0001", "Import failed", "The import operation could not be completed."),
    INVALID_ROLLOUT_SNAPSHOT_ERROR(ROLLOUT_ERROR_CODE_PREFIX, "-0002", "Invalid configuration package", "The configuration package is empty or invalid.");


    @Getter
    private final ErrorCodePayload payload;


    ErrorCode(String codePrefix, String code, String message, String reason) {
        payload = new ErrorCodePayload(codePrefix + code, message, reason);
    }

    public ErrorCodePayload toPayload() {
        return new ErrorCodePayload(payload.getCode(), payload.getReason(), payload.getMessage());
    }

    public ErrorCodePayload toPayload(String detailMessage) {
        ErrorCodePayload copy = toPayload();
        if (detailMessage != null && !detailMessage.isBlank()) {
            copy.setMessage(detailMessage);
        }
        return copy;
    }
}


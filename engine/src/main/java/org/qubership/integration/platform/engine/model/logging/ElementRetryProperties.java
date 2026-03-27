package org.qubership.integration.platform.engine.model.logging;

import org.apache.commons.lang3.StringUtils;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.SERVICE_CALL_DEFAULT_RETRY_DELAY;

public record ElementRetryProperties(Integer retryCount, Integer retryDelay) {

    public ElementRetryProperties(String retryCountString, String retryDelayString) {
        this(
                StringUtils.isNumeric(retryCountString) ? Integer.parseInt(retryCountString) : 0,
                StringUtils.isNumeric(retryDelayString) ? Integer.parseInt(retryDelayString) : SERVICE_CALL_DEFAULT_RETRY_DELAY
        );
    }
}

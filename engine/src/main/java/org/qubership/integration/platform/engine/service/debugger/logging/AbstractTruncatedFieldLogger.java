package org.qubership.integration.platform.engine.service.debugger.logging;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

public abstract class AbstractTruncatedFieldLogger {

    @Value("${qip.logging.fields-max-size:-1}")
    protected int fieldValueMaxSize = -1;

    protected String truncateValue(String value) {
        if (fieldValueMaxSize >= 0 && value != null) {
            return StringUtils.abbreviate(value, fieldValueMaxSize + 3);
        }
        return value;
    }
}

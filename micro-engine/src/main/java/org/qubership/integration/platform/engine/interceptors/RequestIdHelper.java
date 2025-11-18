package org.qubership.integration.platform.engine.interceptors;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.logging.constants.ContextHeaders;
import org.slf4j.MDC;

import java.util.UUID;

@Slf4j
public class RequestIdHelper {
    public static void setRequestId(String requestId) {
        try {
            if (requestId == null) {
                requestId = UUID.randomUUID().toString();
            }

            MDC.put(ContextHeaders.REQUEST_ID, requestId);
        } catch (Exception e) {
            log.warn("Failed to process logging properties", e);
        }
    }
}

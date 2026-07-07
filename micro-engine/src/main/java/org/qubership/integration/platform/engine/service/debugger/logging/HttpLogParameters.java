package org.qubership.integration.platform.engine.service.debugger.logging;

import lombok.Getter;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.constants.CamelNames;

@Getter
public class HttpLogParameters {
    private static final String NO_VALUE = "-";

    private final String targetUrl;
    private final String responseCode;
    private final String responseTime;
    private final String direction;

    public static HttpLogParameters createErrorResponse(HttpOperationFailedException exception,
            long responseTime) {
        return new HttpLogParameters(exception.getUri(), exception.getStatusCode(), responseTime, CamelNames.RESPONSE);
    }

    public static HttpLogParameters createResponse(String targetUrl, Integer responseCode, Long responseTime) {
        return new HttpLogParameters(targetUrl, responseCode, responseTime, CamelNames.RESPONSE);
    }

    public static HttpLogParameters createResponse(Long responseTime) {
        return new HttpLogParameters(null, null, responseTime, CamelNames.RESPONSE);
    }

    public static HttpLogParameters createRequest(String targetUrl) {
        return new HttpLogParameters(targetUrl, null, null, CamelNames.REQUEST);
    }

    public HttpLogParameters(String targetUrl, Integer responseCode, Long responseTime, String direction) {
        this.responseCode = responseCode != null ? responseCode.toString() : NO_VALUE;
        this.responseTime = responseTime != null ? responseTime.toString() : NO_VALUE;
        this.targetUrl = targetUrl != null ? targetUrl : "";
        this.direction = direction;
    }

    @Override
    public String toString() {
        if (StringUtils.isBlank(targetUrl)) {
            return String.format("[responseTime=%-4s] [direction=%-8s]", responseTime, direction);
        } else {
            return String.format("[url=%-36s] [responseCode=%-3s] [responseTime=%-4s] [direction=%-8s]",
                    targetUrl, responseCode, responseTime, direction);
        }
    }
}

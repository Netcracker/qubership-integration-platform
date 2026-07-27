package org.qubership.integration.platform.engine.service.debugger.logging;

import net.logstash.logback.marker.LogstashMarker;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static net.logstash.logback.marker.Markers.append;

@Component
@ConditionalOnProperty(name = "qip.logging.format", havingValue = "json", matchIfMissing = true)
public class JsonChainLogger extends AbstractChainLogger {
    public JsonChainLogger(
            @Lazy TracingService tracingService,
            Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider) {
        super(tracingService, originatingBusinessIdProvider);
    }

    @Override
    public void logExchange(String message, String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging, exchangePropertiesForLogging);
        chainLogger.info(markers, message);
    }

    @Override
    public void logError(String message, Exception exception, String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
        enrichWithErrorCode(markers, exception);
        chainLogger.error(markers, "{} {}", message, exception != null ? exception.getMessage() : "");
    }

    @Override
    public void logErrorWithHttpParams(String message, ErrorCode errorCode, HttpLogParameters params,
            String bodyForLogging, Object headersForLogging, Object exchangePropertiesForLogging) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
        enrichWithHttpMarkers(markers, params);
        enrichWithErrorCode(markers, errorCode);

        chainLogger.error(markers, message);
    }

    @Override
    public void logHttpParams(String message, HttpLogParameters params, String bodyForLogging,
            Object headersForLogging, Object exchangePropertiesForLogging) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
        enrichWithHttpMarkers(markers, params);

        chainLogger.info(markers, message);
    }

    @Override
    public void logFailedHttpOperation(String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging, HttpOperationFailedException httpException, long duration) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
        enrichWithHttpMarkers(markers, HttpLogParameters.createErrorResponse(httpException, duration));
        enrichWithErrorCode(markers, httpException);

        chainLogger.error(markers, "HTTP request failed.");
    }

    @Override
    public void logFailedOperation(String bodyForLogging, Object headersForLogging, Object exchangePropertiesForLogging,
            Exception exception, long duration) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
        enrichWithHttpMarkers(markers, HttpLogParameters.createResponse(duration));
        enrichWithErrorCode(markers, exception);

        chainLogger.error(markers, "HTTP request failed.");
    }

    @Override
    public void logExternalServiceParams(String message, HttpLogParameters params, String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging,
            String externalServiceEnvName,
            String externalServiceAddress) {
        LogstashMarker markers = buildExchangeMarkers(bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
        enrichWithExternalServiceMarkers(markers, externalServiceEnvName, externalServiceAddress);
        if (params != null) {
            enrichWithHttpMarkers(markers, params);
        }
        chainLogger.info(markers, message);
    }

    private void enrichWithHttpMarkers(LogstashMarker markers, HttpLogParameters params) {
        markers.and(append("url", params.getTargetUrl()))
                .and(append("response_code", params.getResponseCode()))
                .and(append("response_time", params.getResponseTime()))
                .and(append("direction", params.getDirection()));
    }

    private void enrichWithErrorCode(LogstashMarker markers, Exception exception) {
        getErrorCode(exception).ifPresent(code -> markers.and(append("error_code", code.getFormattedCode())));
    }

    private void enrichWithErrorCode(LogstashMarker markers, ErrorCode errorCode) {
        markers.and(append("error_code", errorCode.getFormattedCode()));
    }

    private void enrichWithExternalServiceMarkers(LogstashMarker markers, String externalServiceEnvName,
            String externalServiceAddress) {
        markers.and(append("external_service_env_name", externalServiceEnvName))
                .and(append("external_service_address", externalServiceAddress));
    }

    private LogstashMarker buildExchangeMarkers(String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging) {
        return append("exchange_body", truncateValue(bodyForLogging))
                .and(append("exchange_headers", truncateValue(headersForLogging.toString())))
                .and(append("exchange_properties", truncateValue(exchangePropertiesForLogging.toString())));
    }

    private Optional<ErrorCode> getErrorCode(Exception exception) {
        return exception == null ? Optional.empty() : Optional.of(ErrorCode.match(exception));
    }
}

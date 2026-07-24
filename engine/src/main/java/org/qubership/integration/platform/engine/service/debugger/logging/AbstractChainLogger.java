package org.qubership.integration.platform.engine.service.debugger.logging;

import lombok.RequiredArgsConstructor;
import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.support.http.HttpUtil;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.ElementRetryProperties;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

@RequiredArgsConstructor
public abstract class AbstractChainLogger {
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SNAP_ID = "span_id";

    @Value("${qip.logging.field-value-max-size}")
    Integer fieldValueMaxSize;

    @SuppressWarnings("checkstyle:ConstantName")
    protected final ExtendedErrorLogger chainLogger = ExtendedErrorLoggerFactory.getLogger(this.getClass());
    private final TracingService tracingService;
    private final Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider;

    public static void updateMDCProperty(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    public void debug(String format, Object... arguments) {
        chainLogger.debug(format, arguments);
    }

    public void info(String format, Object... arguments) {
        chainLogger.info(format, arguments);
    }

    public void warn(String format, Object... arguments) {
        chainLogger.warn(format, arguments);
    }

    public void error(String format, Object... arguments) {
        chainLogger.error(format, arguments);
    }

    public void logExchangeFinished(
            CamelDebuggerProperties dbgProperties,
            String bodyForLogging,
            String headersForLogging,
            String exchangePropertiesForLogging,
            ExecutionStatus executionStatus,
            long duration) {
        if (dbgProperties.containsElementProperty(ChainProperties.EXECUTION_STATUS)) {
            executionStatus = ExecutionStatus.computeHigherPriorityStatus(
                    ExecutionStatus.valueOf(
                            dbgProperties.getElementProperty(ChainProperties.EXECUTION_STATUS)
                                    .get(ChainProperties.EXECUTION_STATUS)),
                    executionStatus);
        }

        logExchange(String.format("Session %s. Duration %dms.", ExecutionStatus.formatToLogStatus(executionStatus),
                duration), bodyForLogging, headersForLogging, exchangePropertiesForLogging);
    }

    public void logHTTPExchangeFinished(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        String bodyForLogging,
        String headersForLogging,
        String exchangePropertiesForLogging,
        String nodeId,
        long timeTaken,
        Exception exception) {

        String requestUrl = (String) exchange.getProperty(Properties.SERVLET_REQUEST_URL);

        if (nodeId != null) {
            Map<String, String> elementProperties = dbgProperties.getElementProperty(nodeId);
            if (elementProperties != null) {
                String elementName = elementProperties.get(CamelConstants.ChainProperties.ELEMENT_NAME);
                String elementId = elementProperties.get(CamelConstants.ChainProperties.ELEMENT_ID);
                updateMDCProperty(CamelConstants.ChainProperties.ELEMENT_ID, elementId);
                updateMDCProperty(CamelConstants.ChainProperties.ELEMENT_NAME, elementName);
            }
        }

        int responseCode = PayloadExtractor.getServletResponseCode(exchange, exception);
        if (exception != null || !HttpUtil.isStatusCodeOk(responseCode, "100-399")) {
            ErrorCode errorCode = exception != null
                    ? ErrorCode.match(exception)
                    : (ErrorCode) exchange.getProperty(Properties.HTTP_TRIGGER_EXTERNAL_ERROR_CODE);

            logErrorWithHttpParams("HTTP request failed.",
                    errorCode, HttpLogParameters.createResponse(requestUrl, responseCode, timeTaken), bodyForLogging,
                    headersForLogging, exchangePropertiesForLogging);
        } else {
            logHttpParams("HTTP request completed.",
                    HttpLogParameters.createResponse(requestUrl, responseCode, timeTaken), bodyForLogging,
                    headersForLogging, exchangePropertiesForLogging);
        }
    }

    public void setLoggerContext(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            @Nullable String nodeId,
            boolean tracingEnabled) {
        String chainId = dbgProperties.getDeploymentInfo().getChainId();
        String chainName = dbgProperties.getDeploymentInfo().getChainName();
        String sessionId = exchange.getProperty(Properties.SESSION_ID).toString();
        String elementName = null;
        String elementId = null;

        if (nodeId != null) {
            nodeId = DebuggerUtils.getNodeIdFormatted(nodeId);
            Map<String, String> elementProperties = dbgProperties.getElementProperty(nodeId);
            if (elementProperties != null) {
                elementName = elementProperties.get(ChainProperties.ELEMENT_NAME);
                elementId = elementProperties.get(ChainProperties.ELEMENT_ID);
            }
        }

        updateMDCProperty(ChainProperties.CHAIN_ID, chainId);
        updateMDCProperty(ChainProperties.CHAIN_NAME, chainName);
        updateMDCProperty(Properties.SESSION_ID, sessionId);
        updateMDCProperty(ChainProperties.ELEMENT_ID, elementId);
        updateMDCProperty(ChainProperties.ELEMENT_NAME, elementName);

        updateMDCProperty(CamelConstants.LOG_TYPE_KEY, CamelConstants.LOG_TYPE_VALUE);

        originatingBusinessIdProvider.ifPresent(
                businessIdProvider -> updateMDCProperty(Headers.ORIGINATING_BUSINESS_ID,
                        businessIdProvider.getOriginatingBusinessId()));

        String traceId = null;
        String spanId = null;
        SpanAdapter span = ActiveSpanManager.getSpan(exchange);
        if (tracingEnabled && span != null) {
            traceId = span.traceId();
            spanId = span.spanId();
        }

        updateMDCProperty(MDC_TRACE_ID, traceId);
        updateMDCProperty(MDC_SNAP_ID, spanId);
    }

    public void logBeforeProcess(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging,
            String nodeId) {
        bodyForLogging = DebuggerUtils.chooseLogPayload(exchange, bodyForLogging, dbgProperties);
        if (dbgProperties.getRuntimeProperties(exchange).getLogLoggingLevel().isInfoLevel()) {
            ChainElementType type = ChainElementType.fromString(
                    dbgProperties.getElementProperty(nodeId).get(
                            ChainProperties.ELEMENT_TYPE));

            DeploymentRuntimeProperties runtimeProperties = dbgProperties.getRuntimeProperties(exchange);
            if (runtimeProperties.getLogPayload() != null) {
                Set<LogPayload> logPayloadSettings = runtimeProperties.getLogPayload();
                headersForLogging = logPayloadSettings.contains(LogPayload.HEADERS) ? headersForLogging
                        : "<headers not logged>";
                exchangePropertiesForLogging = logPayloadSettings.contains(LogPayload.PROPERTIES)
                        ? exchangePropertiesForLogging
                        : "<properties not logged>";
                bodyForLogging = logPayloadSettings.contains(LogPayload.BODY) ? bodyForLogging : "<body not logged>";
            }

            switch (type) {
                case SCHEDULER, QUARTZ_SCHEDULER -> chainLogger.info("Scheduled chain trigger started");
                case SDS_TRIGGER -> chainLogger.info("Scheduled SDS trigger started");
                case CHAIN_CALL -> logExchange("Executing a linked chain.", bodyForLogging, headersForLogging,
                        exchangePropertiesForLogging);
                case JMS_TRIGGER, SFTP_TRIGGER, SFTP_TRIGGER_2, HTTP_TRIGGER, KAFKA_TRIGGER,
                        KAFKA_TRIGGER_2, RABBITMQ_TRIGGER, RABBITMQ_TRIGGER_2, ASYNCAPI_TRIGGER,
                        PUBSUB_TRIGGER ->
                    logExchange("Get request from trigger.", bodyForLogging, headersForLogging,
                            exchangePropertiesForLogging);
                case HTTP_SENDER -> logRequest(exchange, bodyForLogging, headersForLogging,
                        exchangePropertiesForLogging, null, null);
                case GRAPHQL_SENDER, JMS_SENDER, MAIL_SENDER, KAFKA_SENDER, KAFKA_SENDER_2, RABBITMQ_SENDER,
                        RABBITMQ_SENDER_2, PUBSUB_SENDER ->
                    logExchange("Send request to queue.", bodyForLogging, headersForLogging,
                            exchangePropertiesForLogging);
                case SERVICE_CALL, UNKNOWN -> {
                    // SERVICE_CALL moved to logBuildStepStartedByType to start from "Request" step
                    // (after "Prepare request")
                }
                default -> {
                    // ignore other cases
                }
            }
        }
    }

    public void logAfterProcess(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging,
            String nodeId,
            long timeTaken) {
        boolean failedOperation = DebuggerUtils.isFailedOperation(exchange);
        bodyForLogging = DebuggerUtils.chooseLogPayload(exchange, bodyForLogging, dbgProperties);

        if (dbgProperties.getRuntimeProperties(exchange).getLogLoggingLevel().isInfoLevel()
                || failedOperation) {
            ChainElementType type = ChainElementType.fromString(
                    dbgProperties.getElementProperty(nodeId).get(
                            ChainProperties.ELEMENT_TYPE));

            DeploymentRuntimeProperties runtimeProperties = dbgProperties.getRuntimeProperties(exchange);
            if (runtimeProperties.getLogPayload() != null) {
                Set<LogPayload> logPayloadSettings = runtimeProperties.getLogPayload();
                headersForLogging = logPayloadSettings.contains(LogPayload.HEADERS) ? headersForLogging
                        : "<headers not logged>";
                exchangePropertiesForLogging = logPayloadSettings.contains(LogPayload.PROPERTIES)
                        ? exchangePropertiesForLogging
                        : "<properties not logged>";
                bodyForLogging = logPayloadSettings.contains(LogPayload.BODY) ? bodyForLogging : "<body not logged>";
            }

            switch (type) {
                case HTTP_SENDER, SERVICE_CALL:
                    Map<String, Object> headers = exchange.getMessage().getHeaders();

                    if (failedOperation) {
                        setLoggerContext(exchange, dbgProperties, nodeId,
                                tracingService.isTracingEnabled());
                        if (exchange.getException() instanceof CamelException) {
                            CamelException exception = exchange.getException(CamelException.class);
                            if (exception instanceof HttpOperationFailedException httpException) {
                                logFailedHttpOperation(bodyForLogging, headersForLogging, exchangePropertiesForLogging,
                                        httpException, timeTaken);
                            } else {
                                Throwable[] suppressed = exception.getSuppressed();
                                if (suppressed.length > 0) {
                                    for (Throwable ex : suppressed) {
                                        if (ex instanceof HttpOperationFailedException httpException) {
                                            logFailedHttpOperation(bodyForLogging, headersForLogging,
                                                    exchangePropertiesForLogging, httpException,
                                                    timeTaken);
                                        }
                                    }
                                }
                            }
                        } else {
                            logFailedOperation(bodyForLogging, headersForLogging,
                                    exchangePropertiesForLogging,
                                    exchange.getException(),
                                    timeTaken);
                        }
                    } else {
                        if (headers.containsKey(Headers.CAMEL_HTTP_RESPONSE_CODE)) {
                            Integer code = PayloadExtractor.getResponseCode(headers);
                            String httpUriHeader = exchange.getMessage()
                                    .getHeader(Headers.HTTP_URI, String.class);
                            logHttpParams("HTTP request completed.",
                                    new HttpLogParameters(httpUriHeader, code, timeTaken,
                                            CamelNames.RESPONSE),
                                    bodyForLogging, headersForLogging, exchangePropertiesForLogging);
                        }
                    }
                    break;
                case KAFKA_SENDER, KAFKA_SENDER_2, RABBITMQ_SENDER, RABBITMQ_SENDER_2, PUBSUB_SENDER:
                    if (failedOperation) {
                        setLoggerContext(exchange, dbgProperties, nodeId,
                                tracingService.isTracingEnabled());
                        logError("Sending message to queue failed.", exchange.getException(), bodyForLogging,
                                headersForLogging, exchangePropertiesForLogging);
                    } else {
                        logExchange("Sending message to queue completed.", bodyForLogging, headersForLogging,
                                exchangePropertiesForLogging);
                    }
                    break;
                case CHECKPOINT:
                    // detect checkpoint context saver
                    if (Boolean.FALSE.equals(exchange.getProperty(Properties.CHECKPOINT_IS_TRIGGER_STEP, Boolean.FALSE,
                            Boolean.class))) {
                        chainLogger.info("Session checkpoint passed");
                    }
                    break;
                case UNKNOWN:
                default:
                    if (failedOperation) {
                        setLoggerContext(exchange, dbgProperties, nodeId,
                                tracingService.isTracingEnabled());
                        logError("Failed message:", exchange.getException(), bodyForLogging,
                                headersForLogging, exchangePropertiesForLogging);
                    }
            }
        }
    }

    public void logRequest(
        Exchange exchange,
        String bodyForLogging,
        Object headersForLogging,
        Object exchangePropertiesForLogging,
        String externalServiceAddress,
        String externalServiceEnvName
    ) {
        String httpUriHeader = exchange.getMessage().getHeader(Headers.HTTP_URI, String.class);
        if (StringUtils.isBlank(externalServiceAddress)) {
            if (httpUriHeader != null) {
                logHttpParams("Send HTTP request.",
                        new HttpLogParameters(httpUriHeader, null, null, CamelNames.REQUEST), bodyForLogging,
                        headersForLogging, exchangePropertiesForLogging);
            } else {
                logExchange("Send request.", bodyForLogging, headersForLogging, exchangePropertiesForLogging);
            }
        } else {
            if (httpUriHeader != null) {
                logExternalServiceParams("Send HTTP request.", HttpLogParameters.createRequest(httpUriHeader), bodyForLogging, headersForLogging,
                        exchangePropertiesForLogging, externalServiceEnvName, externalServiceAddress);
            } else {
                logExternalServiceParams("Send request.", null, bodyForLogging, headersForLogging,
                        exchangePropertiesForLogging, externalServiceEnvName, externalServiceAddress);
            }
        }
    }

    public void logRequestAttempt(
            Exchange exchange,
            ElementRetryProperties elementRetryProperties,
            String elementId) {
        RetryParameters retryParameters = getRetryParameters(exchange, elementRetryProperties, elementId);
        chainLogger.info("Request attempt: {} (max {}).", retryParameters.iteration + 1, retryParameters.count + 1);
    }

    public void logRetryRequestAttempt(
            Exchange exchange,
            ElementRetryProperties elementRetryProperties,
            String elementId) {
        RetryParameters retryParameters = getRetryParameters(exchange, elementRetryProperties, elementId);
        if (retryParameters.enable() && retryParameters.iteration() > 0 && retryParameters.count() > 0) {
            Throwable exception = exchange.getProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, Throwable.class);
            chainLogger.warn("Request failed and will be retried after {}ms delay (retries left: {}): {}",
                    retryParameters.interval(), retryParameters.count() - retryParameters.iteration(),
                    exception != null ? exception.getMessage() : "");
        }
    }

    private static record RetryParameters(int count, int interval, int iteration, boolean enable) {
    }

    private RetryParameters getRetryParameters(
            Exchange exchange,
            ElementRetryProperties elementRetryProperties,
            String elementId) {
        try {
            String iteratorPropertyName = IdentifierUtils.getServiceCallRetryIteratorPropertyName(elementId);
            int iteration = Integer
                    .parseInt(String.valueOf(exchange.getProperties().getOrDefault(iteratorPropertyName, 0)));
            String enableProperty = IdentifierUtils.getServiceCallRetryPropertyName(elementId);
            boolean enable = Boolean
                    .parseBoolean(String.valueOf(exchange.getProperties().getOrDefault(enableProperty, "false")));
            return new RetryParameters(elementRetryProperties.retryCount(), elementRetryProperties.retryDelay(),
                    iteration, enable);
        } catch (NumberFormatException ex) {
            chainLogger.error("Failed to get retry parameters.", ex);
            return new RetryParameters(0, 0, 0, false);
        }
    }

    protected String truncateValue(String value) {
        if (fieldValueMaxSize != null && fieldValueMaxSize >= 0 && value != null) {
            return StringUtils.abbreviate(value, fieldValueMaxSize + 3);
        }

        return value;
    }

    public abstract void logExchange(String message, String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging);

    public abstract void logError(String message, Exception exception, String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging);

    public abstract void logErrorWithHttpParams(String message, ErrorCode errorCode, HttpLogParameters params,
            String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging);

    public abstract void logFailedHttpOperation(String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging, HttpOperationFailedException httpException, long duration);

    public abstract void logFailedOperation(String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging, Exception exception, long duration);

    public abstract void logHttpParams(String message, HttpLogParameters params, String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging);

    public abstract void logExternalServiceParams(String message, HttpLogParameters params, String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging,
            String externalServiceEnvName,
            String externalServiceAddress);
}

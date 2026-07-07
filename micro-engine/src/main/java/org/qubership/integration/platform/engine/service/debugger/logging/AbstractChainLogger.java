package org.qubership.integration.platform.engine.service.debugger.logging;

import jakarta.enterprise.inject.Instance;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.support.http.HttpUtil;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.logging.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.logging.ExtendedErrorLoggerFactory;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.ServiceCallInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.model.logging.LoggedPayloadValues;
import org.qubership.integration.platform.engine.model.logging.Payload;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.ExchangeUtil;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.SERVICE_CALL_DEFAULT_RETRY_DELAY;

@Slf4j
public abstract class AbstractChainLogger {
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SNAP_ID = "span_id";

    @SuppressWarnings("checkstyle:ConstantName")
    protected final ExtendedErrorLogger chainLogger = ExtendedErrorLoggerFactory.getLogger(this.getClass());
    private final TracingService tracingService;
    private final Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider;
    private final PayloadExtractor payloadExtractor;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;
    private final VariablesService variablesService;

    protected AbstractChainLogger(
            TracingService tracingService,
            Instance<OriginatingBusinessIdProvider> originatingBusinessIdProvider,
            PayloadExtractor payloadExtractor,
            ChainRuntimePropertiesService chainRuntimePropertiesService,
            VariablesService variablesService
    ) {
        this.tracingService = tracingService;
        this.originatingBusinessIdProvider = originatingBusinessIdProvider == null ? null
                : InjectUtil.injectOptional(originatingBusinessIdProvider);
        this.payloadExtractor = payloadExtractor;
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
        this.variablesService = variablesService;
    }

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

    public void logBeforeProcess(
            Exchange exchange,
            ChainRuntimeProperties runtimeProperties,
            String nodeId,
            Payload payload) {

        LogLoggingLevel logLoggingLevel = runtimeProperties.getLogLoggingLevel();
        if (!logLoggingLevel.isInfoLevel()) {
            return;
        }

        ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, nodeId, ElementInfo.class);
        ChainElementType type = ChainElementType.fromString(elementInfo.getType());
        LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, runtimeProperties);

        switch (type) {
            case SCHEDULER, QUARTZ_SCHEDULER -> chainLogger.info("Scheduled chain trigger started");
            case SDS_TRIGGER -> chainLogger.info("Scheduled SDS trigger started");
            case CHAIN_CALL -> logExchange("Executing a linked chain.", loggedPayloadValues);
            case JMS_TRIGGER, SFTP_TRIGGER, SFTP_TRIGGER_2, HTTP_TRIGGER, KAFKA_TRIGGER,
                    KAFKA_TRIGGER_2, RABBITMQ_TRIGGER, RABBITMQ_TRIGGER_2, ASYNCAPI_TRIGGER,
                    PUBSUB_TRIGGER ->
                logExchange("Get request from trigger.", loggedPayloadValues);
            case HTTP_SENDER -> logRequest(exchange, loggedPayloadValues, null, null);
            case GRAPHQL_SENDER, JMS_SENDER, MAIL_SENDER, KAFKA_SENDER, KAFKA_SENDER_2, RABBITMQ_SENDER,
                    RABBITMQ_SENDER_2, PUBSUB_SENDER ->
                logExchange("Send request to queue.", loggedPayloadValues);
            case SERVICE_CALL, UNKNOWN -> {
                // SERVICE_CALL moved to logBuildStepStartedByType to start from "Request" step
                // (after "Prepare request")
            }
            default -> {
                // ignore other cases
            }
        }
    }

    public void logAfterProcess(
            Exchange exchange,
            ChainRuntimeProperties runtimeProperties,
            Payload payload,
            String nodeId,
            long timeTaken) {
        boolean failedOperation = DebuggerUtils.isFailedOperation(exchange);
        if (runtimeProperties.getLogLoggingLevel().isInfoLevel() || failedOperation) {
            ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, nodeId, ElementInfo.class);
            ChainElementType type = ChainElementType.fromString(elementInfo.getType());
            LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, runtimeProperties);

            switch (type) {
                case HTTP_SENDER, SERVICE_CALL:
                    Map<String, Object> headers = exchange.getMessage().getHeaders();

                    if (failedOperation) {
                        setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
                        if (exchange.getException() instanceof CamelException) {
                            CamelException exception = exchange.getException(CamelException.class);
                            if (exception instanceof HttpOperationFailedException httpException) {
                                logFailedHttpOperation(loggedPayloadValues, httpException, timeTaken);
                            } else {
                                Throwable[] suppressed = exception.getSuppressed();
                                if (suppressed.length > 0) {
                                    for (Throwable ex : suppressed) {
                                        if (ex instanceof HttpOperationFailedException httpException) {
                                            logFailedHttpOperation(loggedPayloadValues, httpException, timeTaken);
                                        }
                                    }
                                }
                            }
                        } else {
                            logFailedOperation(loggedPayloadValues, exchange.getException(), timeTaken);
                        }
                    } else {
                        if (headers.containsKey(Headers.CAMEL_HTTP_RESPONSE_CODE)) {
                            Integer code = PayloadExtractor.getResponseCode(headers);
                            String httpUriHeader = exchange.getMessage()
                                    .getHeader(Headers.HTTP_URI, String.class);
                            logHttpParams("HTTP request completed.",
                                    new HttpLogParameters(httpUriHeader, code, timeTaken,
                                            CamelNames.RESPONSE),
                                    loggedPayloadValues);
                        }
                    }
                    break;
                case KAFKA_SENDER, KAFKA_SENDER_2, RABBITMQ_SENDER, RABBITMQ_SENDER_2, PUBSUB_SENDER:
                    if (failedOperation) {
                        setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
                        logError("Sending message to queue failed.", exchange.getException(), loggedPayloadValues);
                    } else {
                        logExchange("Sending message to queue completed.", loggedPayloadValues);
                    }
                    break;
                case CHECKPOINT:
                    // detect checkpoint context saver
                    if (!exchange.getProperty(Properties.CHECKPOINT_IS_TRIGGER_STEP, false,
                            boolean.class)) {
                        chainLogger.info("Session checkpoint passed");
                    }
                    break;
                case UNKNOWN:
                default:
                    if (failedOperation) {
                        setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
                        logError("Failed message:", exchange.getException(), loggedPayloadValues);
                    }
            }
        }
    }

    public void logExchangeFinished(
            Exchange exchange,
            ExecutionStatus executionStatus,
            long duration) {
        executionStatus = ExchangeUtil.getEffectiveExecutionStatus(exchange, executionStatus);

        ChainRuntimeProperties chainRuntimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        Payload payload = payloadExtractor.extractPayload(exchange);
        LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, chainRuntimeProperties);

        logExchange(String.format("Session %s. Duration %dms.", ExecutionStatus.formatToLogStatus(executionStatus),
                duration), loggedPayloadValues);
    }

    public void logHTTPExchangeFinished(
        Exchange exchange,
        String nodeId,
        long timeTaken,
        Exception exception) {
        ChainRuntimeProperties runtimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        Payload payload = payloadExtractor.extractPayload(exchange);
        LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, runtimeProperties);

        String requestUrl = (String) exchange.getProperty(Properties.SERVLET_REQUEST_URL);

        Optional.ofNullable(nodeId)
                .map(id -> MetadataUtil.getBeanForElement(exchange, id, ElementInfo.class))
                .ifPresent(info -> {
                    updateMDCProperty(CamelConstants.ChainProperties.ELEMENT_ID, info.getId());
                    updateMDCProperty(CamelConstants.ChainProperties.ELEMENT_NAME, info.getName());
                });

        int responseCode = PayloadExtractor.getServletResponseCode(exchange, exception);
        if (exception != null || !HttpUtil.isStatusCodeOk(responseCode, "100-399")) {
            ErrorCode errorCode = exception != null
                    ? ErrorCode.match(exception)
                    : (ErrorCode) exchange.getProperty(Properties.HTTP_TRIGGER_EXTERNAL_ERROR_CODE);

            logErrorWithHttpParams("HTTP request failed.",
                    errorCode, HttpLogParameters.createResponse(requestUrl, responseCode, timeTaken), loggedPayloadValues);
        } else {
            logHttpParams("HTTP request completed.",
                    HttpLogParameters.createResponse(requestUrl, responseCode, timeTaken), loggedPayloadValues);
        }
    }

    public void setLoggerContext(
            Exchange exchange,
            @Nullable String nodeId,
            boolean tracingEnabled) {
        ChainInfo chainInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class).getChain();
        String chainId = chainInfo.getId();
        String chainName = chainInfo.getName();
        String sessionId = ExchangeUtil.getSessionId(exchange);
        String elementName = null;
        String elementId = null;

        if (nodeId != null) {
            nodeId = DebuggerUtils.getNodeIdFormatted(nodeId);
            Optional<ElementInfo> elementInfo = MetadataUtil.lookupBeanForElement(exchange, nodeId, ElementInfo.class);
            elementId = elementInfo.map(ElementInfo::getId).orElse(null);
            elementName = elementInfo.map(ElementInfo::getName).orElse(null);
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

    public void logRequest(
            Exchange exchange,
            String externalServiceName,
            String externalServiceEnvName
    ) {
        ChainRuntimeProperties runtimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        LogLoggingLevel logLoggingLevel = runtimeProperties.getLogLoggingLevel();
        if (!logLoggingLevel.isInfoLevel()) {
            return;
        }

        Payload payload = payloadExtractor.extractPayload(exchange);
        LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, runtimeProperties);
        logRequest(exchange, loggedPayloadValues, externalServiceName, externalServiceEnvName);
    }

    private void logRequest(
        Exchange exchange,
        LoggedPayloadValues loggedPayloadValues,
        String externalServiceAddress,
        String externalServiceEnvName
    ) {
        String httpUriHeader = exchange.getMessage().getHeader(Headers.HTTP_URI, String.class);
        if (StringUtils.isBlank(externalServiceAddress)) {
            if (httpUriHeader != null) {
                logHttpParams("Send HTTP request.",
                        new HttpLogParameters(httpUriHeader, null, null, CamelNames.REQUEST), loggedPayloadValues);
            } else {
                logExchange("Send request.", loggedPayloadValues);
            }
        } else {
            if (httpUriHeader != null) {
                logExternalServiceParams("Send HTTP request.", HttpLogParameters.createRequest(httpUriHeader),
                        loggedPayloadValues, externalServiceEnvName, externalServiceAddress);
            } else {
                logExternalServiceParams("Send request.", null, loggedPayloadValues, externalServiceEnvName,
                        externalServiceAddress);
            }
        }
    }

    public void logRequestAttempt(
            Exchange exchange,
            String elementId) {
        ChainRuntimeProperties runtimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        LogLoggingLevel logLoggingLevel = runtimeProperties.getLogLoggingLevel();
        if (!logLoggingLevel.isInfoLevel()) {
            return;
        }
        RetryParameters retryParameters = getRetryParameters(exchange, elementId);
        chainLogger.info("Request attempt: {} (max {}).", retryParameters.iteration + 1, retryParameters.count + 1);
    }

    public void logRetryRequestAttempt(
            Exchange exchange,
            String elementId) {
        ChainRuntimeProperties runtimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        LogLoggingLevel logLoggingLevel = runtimeProperties.getLogLoggingLevel();
        if (!logLoggingLevel.isWarnLevel()) {
            return;
        }

        RetryParameters retryParameters = getRetryParameters(exchange, elementId);
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
            String elementId
    ) {
        try {
            String iteratorPropertyName = IdentifierUtils.getServiceCallRetryIteratorPropertyName(elementId);
            int iteration = Integer.parseInt(String.valueOf(exchange.getProperties().getOrDefault(iteratorPropertyName, 0)));
            String enableProperty = IdentifierUtils.getServiceCallRetryPropertyName(elementId);
            boolean enable = Boolean.parseBoolean(String.valueOf(exchange.getProperties().getOrDefault(enableProperty, "false")));
            ServiceCallInfo serviceCallInfo = MetadataUtil.getBeanForElement(exchange, elementId, ServiceCallInfo.class);
            int retryCount = Optional.ofNullable(serviceCallInfo.getRetryCount())
                    .map(variablesService::injectVariables)
                    .map(Integer::parseInt)
                    .orElse(0);
            int retryDelay = Optional.ofNullable(serviceCallInfo.getRetryDelay())
                    .map(variablesService::injectVariables)
                    .map(Integer::parseInt)
                    .orElse(SERVICE_CALL_DEFAULT_RETRY_DELAY);
            return new RetryParameters(retryCount, retryDelay, iteration, enable);
        } catch (NumberFormatException ex) {
            chainLogger.error("Failed to get retry parameters.", ex);
            return new RetryParameters(0, 0, 0, false);
        }
    }

    public abstract void logExchange(String message, LoggedPayloadValues loggedPayloadValues);

    public abstract void logError(String message, Exception exception, LoggedPayloadValues loggedPayloadValues);

    public abstract void logErrorWithHttpParams(String message, ErrorCode errorCode, HttpLogParameters params,
            LoggedPayloadValues loggedPayloadValues);

    public abstract void logFailedHttpOperation(LoggedPayloadValues loggedPayloadValues,
            HttpOperationFailedException httpException, long duration);

    public abstract void logFailedOperation(LoggedPayloadValues loggedPayloadValues, Exception exception,
            long duration);

    public abstract void logHttpParams(String message, HttpLogParameters params,
            LoggedPayloadValues loggedPayloadValues);

    public abstract void logExternalServiceParams(String message, HttpLogParameters params,
            LoggedPayloadValues loggedPayloadValues,
            String externalServiceEnvName,
            String externalServiceAddress);

    public static LoggedPayloadValues getLoggedPayloadValues(
            Payload payload,
            ChainRuntimeProperties chainRuntimeProperties
    ) {
        Set<LogPayload> loggedParts = Optional.ofNullable(chainRuntimeProperties.getLogPayload())
                .orElse(
                        chainRuntimeProperties.isLogPayloadEnabled()
                                ? Collections.singleton(LogPayload.BODY)
                                : Collections.emptySet()
                );
        String body = loggedParts.contains(LogPayload.BODY)
                ? payload.getBody()
                : "<body not logged>";
        String headers = loggedParts.contains(LogPayload.HEADERS)
                ? payload.getHeaders().toString()
                : "<headers not logged>";
        String properties = loggedParts.contains(LogPayload.PROPERTIES)
                ? payload.getProperties().toString()
                : "<properties not logged>";
        return LoggedPayloadValues.builder()
                .body(body)
                .headers(headers)
                .properties(properties)
                .build();
    }
}

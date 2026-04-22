/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.debugger.logging;

import com.networknt.schema.utils.StringUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.support.http.HttpUtil;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
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
import org.qubership.integration.platform.engine.model.logging.*;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
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

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.*;

@Slf4j
@ApplicationScoped
public class ChainLogger {
    @SuppressWarnings("checkstyle:ConstantName")
    private static final ExtendedErrorLogger chainLogger = ExtendedErrorLoggerFactory.getLogger(ChainLogger.class);

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SNAP_ID = "span_id";

    private final TracingService tracingService;
    private final Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider;
    private final PayloadExtractor payloadExtractor;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;
    private final VariablesService variablesService;

    @Inject
    public ChainLogger(
            TracingService tracingService,
            Instance<OriginatingBusinessIdProvider> originatingBusinessIdProvider,
            PayloadExtractor payloadExtractor,
            ChainRuntimePropertiesService chainRuntimePropertiesService,
            VariablesService variablesService
    ) {
        this.tracingService = tracingService;
        this.originatingBusinessIdProvider = InjectUtil.injectOptional(originatingBusinessIdProvider);
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
        Payload payload
    ) {
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
            case CHAIN_CALL -> chainLogger.info("Executing a linked chain. Headers: {}, body: {}, exchange properties: {}",
                    loggedPayloadValues.getHeaders(),
                    loggedPayloadValues.getBody(),
                    loggedPayloadValues.getProperties());
            case JMS_TRIGGER, SFTP_TRIGGER, SFTP_TRIGGER_2, HTTP_TRIGGER, KAFKA_TRIGGER,
                KAFKA_TRIGGER_2, RABBITMQ_TRIGGER, RABBITMQ_TRIGGER_2, ASYNCAPI_TRIGGER,
                 PUBSUB_TRIGGER ->
                chainLogger.info(
                    "Get request from trigger. Headers: {}, body: {}, exchange properties: {}",
                    loggedPayloadValues.getHeaders(),
                    loggedPayloadValues.getBody(),
                    loggedPayloadValues.getProperties());
            case HTTP_SENDER -> logRequest(exchange, loggedPayloadValues, null, null);
            case GRAPHQL_SENDER, JMS_SENDER, MAIL_SENDER, KAFKA_SENDER, KAFKA_SENDER_2, RABBITMQ_SENDER,
                RABBITMQ_SENDER_2, PUBSUB_SENDER -> chainLogger.info(
                "Send request to queue. Headers: {}, body: {}, exchange properties: {}",
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties()
            );
            // SERVICE_CALL moved to logBuildStepStartedByType to start from "Request" step (after "Prepare request")
            case SERVICE_CALL, UNKNOWN -> {
            }
            default -> {
            }
        }
    }

    public void logAfterProcess(
        Exchange exchange,
        ChainRuntimeProperties runtimeProperties,
        Payload payload,
        String nodeId,
        long timeTaken
    ) {
        boolean failedOperation = DebuggerUtils.isFailedOperation(exchange);
        if (runtimeProperties.getLogLoggingLevel().isInfoLevel() || failedOperation) {
            ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, nodeId, ElementInfo.class);
            ChainElementType type = ChainElementType.fromString(elementInfo.getType());
            LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, runtimeProperties);

            switch (type) {
                case HTTP_SENDER:
                case SERVICE_CALL:
                    Map<String, Object> headers = exchange.getMessage().getHeaders();

                    if (failedOperation) {
                        setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
                        if (exchange.getException() instanceof CamelException) {
                            CamelException exception = exchange.getException(CamelException.class);
                            if (exception instanceof HttpOperationFailedException) {
                                logFailedHttpOperation(
                                        loggedPayloadValues,
                                        (HttpOperationFailedException) exception,
                                        timeTaken
                                );
                            } else {
                                Throwable[] suppressed = exception.getSuppressed();
                                if (suppressed.length > 0) {
                                    for (Throwable ex : suppressed) {
                                        if (ex instanceof HttpOperationFailedException) {
                                            logFailedHttpOperation(
                                                    loggedPayloadValues,
                                                    (HttpOperationFailedException) ex,
                                                    timeTaken
                                            );
                                        }
                                    }
                                }
                            }
                        } else {
                            logFailedOperation(
                                    loggedPayloadValues,
                                    exchange.getException(),
                                    timeTaken
                            );
                        }
                    } else {
                        if (headers.containsKey(Headers.CAMEL_HTTP_RESPONSE_CODE)) {
                            Integer code = PayloadExtractor.getResponseCode(headers);
                            String httpUriHeader = exchange.getMessage()
                                .getHeader(Headers.HTTP_URI, String.class);
                            chainLogger.info(
                                "{} HTTP request completed. Headers: {}, body: {}, exchange properties: {}",
                                constructExtendedHTTPLogMessage(httpUriHeader, code, timeTaken,
                                    CamelNames.RESPONSE),
                                loggedPayloadValues.getHeaders(),
                                loggedPayloadValues.getBody(),
                                loggedPayloadValues.getProperties());
                        }
                    }
                    break;
                case KAFKA_SENDER:
                case KAFKA_SENDER_2:
                case RABBITMQ_SENDER:
                case RABBITMQ_SENDER_2:
                case PUBSUB_SENDER:
                    if (failedOperation) {
                        setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
                        chainLogger.error(ErrorCode.match(exchange.getException()),
                            "Sending message to queue failed. {} Headers: {}, body: {}, exchange properties: {}",
                            exchange.getException().getMessage(),
                            loggedPayloadValues.getHeaders(),
                            loggedPayloadValues.getBody(),
                            loggedPayloadValues.getProperties());
                    } else {
                        chainLogger.info(
                            "Sending message to queue completed. Headers: {}, body: {}, exchange properties: {}",
                            loggedPayloadValues.getHeaders(),
                            loggedPayloadValues.getBody(),
                            loggedPayloadValues.getProperties());
                    }
                    break;
                case CHECKPOINT:
                    // detect checkpoint context saver
                    if (!exchange.getProperty(Properties.CHECKPOINT_IS_TRIGGER_STEP, false,
                        Boolean.class)) {
                        chainLogger.info("Session checkpoint passed");
                    }
                    break;
                case UNKNOWN:
                default:
                    if (failedOperation) {
                        setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
                        chainLogger.error(ErrorCode.match(exchange.getException()),
                            "Failed message: {} Headers: {}, body: {}, exchange properties: {}",
                            exchange.getException().getMessage(),
                            loggedPayloadValues.getHeaders(),
                            loggedPayloadValues.getBody(),
                            loggedPayloadValues.getProperties());
                    }
            }
        }
    }

    public void logExchangeFinished(
            Exchange exchange,
            ExecutionStatus executionStatus,
            long duration
    ) {
        executionStatus = ExchangeUtil.getEffectiveExecutionStatus(exchange, executionStatus);

        ChainRuntimeProperties chainRuntimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        Payload payload = payloadExtractor.extractPayload(exchange);
        LoggedPayloadValues loggedPayloadValues = getLoggedPayloadValues(payload, chainRuntimeProperties);

        chainLogger.info(
                "Session {}. Duration {}ms. Headers: {}, body: {}, exchange properties: {}",
                ExecutionStatus.formatToLogStatus(executionStatus),
                duration,
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties()
        );
    }

    public void logHTTPExchangeFinished(
        Exchange exchange,
        String nodeId,
        long timeTaken,
        Exception exception
    ) {
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
            chainLogger.error(errorCode,
                    "{} HTTP request {}. Headers: {}, body: {}, exchange properties: {}",
                    constructExtendedHTTPLogMessage(requestUrl,
                            responseCode,
                            timeTaken,
                            CamelNames.RESPONSE),
                    "failed",
                    loggedPayloadValues.getHeaders(),
                    loggedPayloadValues.getBody(),
                    loggedPayloadValues.getProperties()
            );
        } else {
            chainLogger.info(
                    "{} HTTP request {}. Headers: {}, body: {}, exchange properties: {}",
                    constructExtendedHTTPLogMessage(requestUrl,
                            responseCode,
                            timeTaken,
                            CamelNames.RESPONSE),
                    "completed",
                    loggedPayloadValues.getHeaders(),
                    loggedPayloadValues.getBody(),
                    loggedPayloadValues.getProperties()
            );
        }
    }

    public void setLoggerContext(
        Exchange exchange,
        @Nullable String nodeId,
        boolean tracingEnabled
    ) {
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
                chainLogger.info("{} Send HTTP request. Headers: {}, body: {}, exchange properties: {}",
                        constructExtendedHTTPLogMessage(httpUriHeader, null, null, CamelNames.REQUEST),
                        loggedPayloadValues.getHeaders(),
                        loggedPayloadValues.getBody(),
                        loggedPayloadValues.getProperties());
            } else {
                chainLogger.info("Send request. Headers: {}, body: {}, exchange properties: {}",
                        loggedPayloadValues.getHeaders(),
                        loggedPayloadValues.getBody(),
                        loggedPayloadValues.getProperties());
            }
        } else {
            if (httpUriHeader != null) {
                chainLogger.info("{} Send HTTP request. Headers: {}, body: {}, exchange properties: {}"
                        + ", external service environment name: {}, external service address: {}",
                        constructExtendedHTTPLogMessage(httpUriHeader, null, null, CamelNames.REQUEST),
                        loggedPayloadValues.getHeaders(),
                        loggedPayloadValues.getBody(),
                        loggedPayloadValues.getProperties(),
                        externalServiceEnvName,
                        externalServiceAddress);
            } else {
                chainLogger.info("Send request. Headers: {}, body: {}, exchange properties: {},"
                        + ", external service environment name: {}, external service address: {}",
                        loggedPayloadValues.getHeaders(),
                        loggedPayloadValues.getBody(),
                        loggedPayloadValues.getProperties(),
                        externalServiceEnvName,
                        externalServiceAddress);
            }
        }
    }

    public void logRequestAttempt(
            Exchange exchange,
            String elementId
    ) {
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
            String elementId
    ) {
        ChainRuntimeProperties runtimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        LogLoggingLevel logLoggingLevel = runtimeProperties.getLogLoggingLevel();
        if (!logLoggingLevel.isWarnLevel()) {
            return;
        }
        RetryParameters retryParameters = getRetryParameters(exchange, elementId);
        if (retryParameters.enable && retryParameters.iteration > 0 && retryParameters.count > 0) {
            Throwable exception = exchange.getProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, Throwable.class);
            chainLogger.warn("Request failed and will be retried after {}ms delay (retries left: {}): {}",
                    retryParameters.interval, retryParameters.count - retryParameters.iteration,
                    Optional.ofNullable(exception).map(Throwable::getMessage).orElse(""));
        }
    }

    private static record RetryParameters(int count, int interval, int iteration, boolean enable) {}

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

    private void logFailedHttpOperation(
        LoggedPayloadValues loggedPayloadValues,
        HttpOperationFailedException httpException,
        long duration
    ) {
        int code = httpException.getStatusCode();
        String uri = httpException.getUri();
        chainLogger.error(ErrorCode.match(httpException),
            "{} HTTP request failed. Headers: {}, body: {}, exchange properties: {}",
            constructExtendedHTTPLogMessage(uri, code, duration, CamelNames.RESPONSE),
            loggedPayloadValues.getHeaders(),
            loggedPayloadValues.getBody(),
            loggedPayloadValues.getProperties());
    }

    private void logFailedOperation(
        LoggedPayloadValues loggedPayloadValues,
        Exception exception,
        long duration
    ) {
        chainLogger.error(ErrorCode.match(exception),
            "{} HTTP request failed. {} Headers: {}, body: {}, exchange properties: {}",
            constructExtendedLogMessage(duration, CamelNames.RESPONSE),
            exception.getMessage(),
            loggedPayloadValues.getHeaders(),
            loggedPayloadValues.getBody(),
            loggedPayloadValues.getProperties());
    }

    private String constructExtendedHTTPLogMessage(String targetUrl, Integer responseCode,
        Long responseTime,
        String direction) {
        String noValue = "-";
        String responseCodeStr = responseCode != null ? responseCode.toString() : noValue;
        String responseTimeStr = responseTime != null ? responseTime.toString() : noValue;
        targetUrl = targetUrl != null ? targetUrl : "";

        return String.format("[url=%-36s] [responseCode=%-3s] [responseTime=%-4s] [direction=%-8s]",
            targetUrl, responseCodeStr, responseTimeStr, direction);
    }

    private String constructExtendedLogMessage(Long responseTime, String direction) {
        String noValue = "-";
        String responseTimeStr = responseTime != null ? responseTime.toString() : noValue;

        return String.format("[responseTime=%-4s] [direction=%-8s]", responseTimeStr, direction);
    }

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

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

import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;
import com.networknt.schema.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.support.http.HttpUtil;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class ChainLogger {

    private static final ExtendedErrorLogger chainLogger = ExtendedErrorLoggerFactory.getLogger(ChainLogger.class);
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SNAP_ID = "span_id";

    private final TracingService tracingService;
    private final Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider;

    @Autowired
    public ChainLogger(@Lazy TracingService tracingService,
        Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider) {
        this.tracingService = tracingService;
        this.originatingBusinessIdProvider = originatingBusinessIdProvider;
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
        CamelDebuggerProperties dbgProperties,
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, SessionElementProperty> exchangePropertiesForLogging,
        String nodeId
    ) {
        bodyForLogging = DebuggerUtils.chooseLogPayload(exchange, bodyForLogging, dbgProperties);
        if (dbgProperties.getRuntimeProperties(exchange).getLogLoggingLevel().isInfoLevel()) {
            ChainElementType type = ChainElementType.fromString(
                dbgProperties.getElementProperty(nodeId).get(
                    ChainProperties.ELEMENT_TYPE));

            switch (type) {
                case SCHEDULER, QUARTZ_SCHEDULER -> chainLogger.info("Scheduled chain trigger started");
                case SDS_TRIGGER -> chainLogger.info("Scheduled SDS trigger started");
                case CHAIN_CALL -> chainLogger.info("Executing a linked chain. Headers: {}, body: {}, exchange properties: {}",
                        headersForLogging,
                        bodyForLogging,
                        exchangePropertiesForLogging);
                case JMS_TRIGGER, SFTP_TRIGGER, SFTP_TRIGGER_2, HTTP_TRIGGER, KAFKA_TRIGGER,
                    KAFKA_TRIGGER_2, RABBITMQ_TRIGGER, RABBITMQ_TRIGGER_2, ASYNCAPI_TRIGGER,
                     PUBSUB_TRIGGER ->
                    chainLogger.info(
                        "Get request from trigger. Headers: {}, body: {}, exchange properties: {}",
                        headersForLogging,
                        bodyForLogging,
                        exchangePropertiesForLogging);
                case HTTP_SENDER -> logRequest(exchange, bodyForLogging, headersForLogging,
                    exchangePropertiesForLogging, null, null);
                case GRAPHQL_SENDER, JMS_SENDER, MAIL_SENDER, KAFKA_SENDER, KAFKA_SENDER_2, RABBITMQ_SENDER,
                    RABBITMQ_SENDER_2, PUBSUB_SENDER -> chainLogger.info(
                    "Send request to queue. Headers: {}, body: {}, exchange properties: {}",
                    headersForLogging,
                    bodyForLogging,
                    exchangePropertiesForLogging);
                // SERVICE_CALL moved to logBuildStepStartedByType to start from "Request" step (after "Prepare request")
                case SERVICE_CALL, UNKNOWN -> {
                }
                default -> {
                }
            }
        }
    }

    public void logAfterProcess(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, SessionElementProperty> exchangePropertiesForLogging,
        String nodeId,
        long timeTaken
    ) {
        boolean failedOperation = DebuggerUtils.isFailedOperation(exchange);
        bodyForLogging = DebuggerUtils.chooseLogPayload(exchange, bodyForLogging, dbgProperties);

        if (dbgProperties.getRuntimeProperties(exchange).getLogLoggingLevel().isInfoLevel()
            || failedOperation) {
            ChainElementType type = ChainElementType.fromString(
                dbgProperties.getElementProperty(nodeId).get(
                    ChainProperties.ELEMENT_TYPE));

            switch (type) {
                case HTTP_SENDER:
                case SERVICE_CALL:
                    Map<String, Object> headers = exchange.getMessage().getHeaders();

                    if (failedOperation) {
                        setLoggerContext(exchange, dbgProperties, nodeId,
                            tracingService.isTracingEnabled());
                        if (exchange.getException() instanceof CamelException) {
                            CamelException exception = exchange.getException(CamelException.class);
                            if (exception instanceof HttpOperationFailedException) {
                                logFailedHttpOperation(bodyForLogging, headersForLogging,
                                    exchangePropertiesForLogging,
                                    (HttpOperationFailedException) exception,
                                    timeTaken);
                            } else {
                                Throwable[] suppressed = exception.getSuppressed();
                                if (suppressed.length > 0) {
                                    for (Throwable ex : suppressed) {
                                        if (ex instanceof HttpOperationFailedException) {
                                            logFailedHttpOperation(bodyForLogging,
                                                headersForLogging,
                                                exchangePropertiesForLogging,
                                                (HttpOperationFailedException) ex,
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
                            chainLogger.info(
                                "{} HTTP request completed. Headers: {}, body: {}, exchange properties: {}",
                                constructExtendedHTTPLogMessage(httpUriHeader, code, timeTaken,
                                    CamelNames.RESPONSE),
                                headersForLogging,
                                bodyForLogging,
                                exchangePropertiesForLogging);
                        }
                    }
                    break;
                case KAFKA_SENDER:
                case KAFKA_SENDER_2:
                case RABBITMQ_SENDER:
                case RABBITMQ_SENDER_2:
                case PUBSUB_SENDER:
                    if (failedOperation) {
                        setLoggerContext(exchange, dbgProperties, nodeId,
                            tracingService.isTracingEnabled());
                        chainLogger.error(ErrorCode.match(exchange.getException()),
                            "Sending message to queue failed. {} Headers: {}, body: {}, exchange properties: {}",
                            exchange.getException().getMessage(),
                            headersForLogging,
                            bodyForLogging,
                            exchangePropertiesForLogging);
                    } else {
                        chainLogger.info(
                            "Sending message to queue completed. Headers: {}, body: {}, exchange properties: {}",
                            headersForLogging,
                            bodyForLogging,
                            exchangePropertiesForLogging);
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
                        setLoggerContext(exchange, dbgProperties, nodeId,
                            tracingService.isTracingEnabled());
                        chainLogger.error(ErrorCode.match(exchange.getException()),
                            "Failed message: {} Headers: {}, body: {}, exchange properties: {}",
                            exchange.getException().getMessage(),
                            headersForLogging,
                            bodyForLogging,
                            exchangePropertiesForLogging);
                    }
            }
        }
    }

    public void logExchangeFinished (
            CamelDebuggerProperties dbgProperties,
            String bodyForLogging,
            String headersForLogging,
            String exchangePropertiesForLogging,
            ExecutionStatus executionStatus,
            long duration
    ) {
        if (dbgProperties.containsElementProperty(ChainProperties.EXECUTION_STATUS)) {
            executionStatus = ExecutionStatus.computeHigherPriorityStatus(
                    ExecutionStatus.valueOf(
                            dbgProperties.getElementProperty(ChainProperties.EXECUTION_STATUS)
                                    .get(
                                            ChainProperties.EXECUTION_STATUS)),
                    executionStatus);
        }

        chainLogger.info(
                "Session {}. Duration {}ms. Headers: {}, body: {}, exchange properties: {}",
                ExecutionStatus.formatToLogStatus(executionStatus),
                duration,
                headersForLogging,
                bodyForLogging,
                exchangePropertiesForLogging);
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
            ErrorCode errorCode = exception != null ?
                ErrorCode.match(exception) :
                (ErrorCode) exchange.getProperty(Properties.HTTP_TRIGGER_EXTERNAL_ERROR_CODE);
            chainLogger.error(errorCode,
                    "{} HTTP request {}. Headers: {}, body: {}, exchange properties: {}",
                    constructExtendedHTTPLogMessage(requestUrl,
                            responseCode,
                            timeTaken,
                            CamelNames.RESPONSE),
                    "failed",
                    headersForLogging,
                    bodyForLogging,
                    exchangePropertiesForLogging
            );
        } else {
            chainLogger.info(
                    "{} HTTP request {}. Headers: {}, body: {}, exchange properties: {}",
                    constructExtendedHTTPLogMessage(requestUrl,
                            responseCode,
                            timeTaken,
                            CamelNames.RESPONSE),
                    "completed",
                    headersForLogging,
                    bodyForLogging,
                    exchangePropertiesForLogging
            );
        }
    }

    public void setLoggerContext(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        @Nullable String nodeId,
        boolean tracingEnabled
    ) {
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

    public void logRequest(
        Exchange exchange,
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, SessionElementProperty> exchangePropertiesForLogging,
        String externalServiceName,
        String externalServiceEnvName
    ) {
        String httpUriHeader = exchange.getMessage().getHeader(Headers.HTTP_URI, String.class);
        if (StringUtils.isBlank(externalServiceName)) {
            if (httpUriHeader != null) {
                chainLogger.info("{} Send HTTP request. Headers: {}, body: {}, exchange properties: {}",
                        constructExtendedHTTPLogMessage(httpUriHeader, null, null, CamelNames.REQUEST),
                        headersForLogging,
                        bodyForLogging,
                        exchangePropertiesForLogging);
            } else {
                chainLogger.info("Send request. Headers: {}, body: {}, exchange properties: {}",
                        headersForLogging,
                        bodyForLogging,
                        exchangePropertiesForLogging);
            }
        } else {
            if (httpUriHeader != null) {
                chainLogger.info("{} Send HTTP request. Headers: {}, body: {}, exchange properties: {}"
                        + ", external service name: {}, external service environment name: {}",
                        constructExtendedHTTPLogMessage(httpUriHeader, null, null, CamelNames.REQUEST),
                        headersForLogging,
                        bodyForLogging,
                        exchangePropertiesForLogging,
                        externalServiceName,
                        externalServiceEnvName);
            } else {
                chainLogger.info("Send request. Headers: {}, body: {}, exchange properties: {},"
                        + " external service name: {}, external service environment name: {}",
                        headersForLogging,
                        bodyForLogging,
                        exchangePropertiesForLogging,
                        externalServiceName,
                        externalServiceEnvName);
            }
        }
    }

    public void logRequestAttempt(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String elementId
    ) {
        RetryParameters retryParameters = getRetryParameters(exchange, dbgProperties, elementId);
        chainLogger.info("Request attempt: {} (max {}).", retryParameters.iteration + 1, retryParameters.count + 1);
    }

    public void logRetryRequestAttempt(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String elementId
    ) {
        RetryParameters retryParameters = getRetryParameters(exchange, dbgProperties, elementId);
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
            CamelDebuggerProperties dbgProperties,
            String elementId
    ) {
        try {
            Map<String, String> elementProperties = Optional.ofNullable(dbgProperties.getElementProperty(elementId)).orElse(Collections.emptyMap());
            int retryCount = Integer.parseInt(elementProperties.getOrDefault(
                Properties.SERVICE_CALL_RETRY_COUNT, "0"));
            int retryDelay = Integer.valueOf(elementProperties.getOrDefault(
                Properties.SERVICE_CALL_RETRY_DELAY, String.valueOf(
                    Properties.SERVICE_CALL_DEFAULT_RETRY_DELAY)));
            String iteratorPropertyName = IdentifierUtils.getServiceCallRetryIteratorPropertyName(elementId);
            int iteration = Integer.parseInt(String.valueOf(exchange.getProperties().getOrDefault(iteratorPropertyName, 0)));
            String enableProperty = IdentifierUtils.getServiceCallRetryPropertyName(elementId);
            boolean enable = Boolean.parseBoolean(String.valueOf(exchange.getProperties().getOrDefault(enableProperty, "false")));
            return new RetryParameters(retryCount, retryDelay, iteration, enable);
        } catch (NumberFormatException ex) {
            chainLogger.error("Failed to get retry parameters.", ex);
            return new RetryParameters(0, 0, 0, false);
        }
    }

    private void logFailedHttpOperation(
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, SessionElementProperty> exchangePropertiesForLogging,
        HttpOperationFailedException httpException,
        long duration
    ) {
        int code = httpException.getStatusCode();
        String uri = httpException.getUri();
        chainLogger.error(ErrorCode.match(httpException),
            "{} HTTP request failed. Headers: {}, body: {}, exchange properties: {}",
            constructExtendedHTTPLogMessage(uri, code, duration, CamelNames.RESPONSE),
            headersForLogging,
            bodyForLogging,
            exchangePropertiesForLogging);
    }

    private void logFailedOperation(
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, SessionElementProperty> exchangePropertiesForLogging,
        Exception exception,
        long duration
    ) {
        chainLogger.error(ErrorCode.match(exception),
            "{} HTTP request failed. {} Headers: {}, body: {}, exchange properties: {}",
            constructExtendedLogMessage(duration, CamelNames.RESPONSE),
            exception.getMessage(),
            headersForLogging,
            bodyForLogging,
            exchangePropertiesForLogging);
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
}

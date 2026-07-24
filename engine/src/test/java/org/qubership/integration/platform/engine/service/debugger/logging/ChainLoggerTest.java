package org.qubership.integration.platform.engine.service.debugger.logging;

import net.logstash.logback.marker.LogstashMarker;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.DefaultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCodePrefix;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChainLoggerTest {

    ChainLogger chainLogger;

    @Mock
    TracingService tracingService;

    @Mock
    Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider;

    ExtendedErrorLogger extendedErrorLogger;
    MockedStatic<ExtendedErrorLoggerFactory> factoryMock;
    MockedStatic<ErrorCodePrefix> errorCodePrefixMock;

    @BeforeEach
    void setUp() {
        extendedErrorLogger = mock(ExtendedErrorLogger.class);
        factoryMock = mockStatic(ExtendedErrorLoggerFactory.class);
        factoryMock.when(() -> ExtendedErrorLoggerFactory.getLogger(any(Class.class)))
                .thenReturn(extendedErrorLogger);
        chainLogger = new ChainLogger(tracingService, originatingBusinessIdProvider);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        factoryMock.close();
        if (errorCodePrefixMock != null) {
            errorCodePrefixMock.close();
        }
        MDC.clear();
    }

    // ========== Tests for ChainLogger's own methods (implementing abstract methods) ==========

    @Test
    void testLogExchange() {
        String message = "Test exchange message";
        String body = "test-body";
        String headers = "test-headers";
        String properties = "test-properties";

        chainLogger.logExchange(message, body, headers, properties);

        verify(extendedErrorLogger).info(
                eq(message + " Headers: {}, body: {}, exchange properties: {}"),
                eq(headers), eq(body), eq(properties)
        );
    }

    @Test
    void testLogError() {
        Exception exception = new RuntimeException("Test error");
        String message = "Error occurred";
        String body = "error-body";
        String headers = "error-headers";
        String properties = "error-properties";

        errorCodePrefixMock = mockStatic(ErrorCodePrefix.class);
        errorCodePrefixMock.when(ErrorCodePrefix::getCodePrefix).thenReturn("qip");

        chainLogger.logError(message, exception, body, headers, properties);

        ArgumentCaptor<ErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(ErrorCode.class);
        verify(extendedErrorLogger).error(
                errorCodeCaptor.capture(),
                eq(message + " " + exception.getMessage() + " Headers: {}, body: {}, exchange properties: {}"),
                eq(headers), eq(body), eq(properties)
        );
        assertEquals(ErrorCode.UNEXPECTED_BUSINESS_ERROR, errorCodeCaptor.getValue());
    }

    @Test
    void testLogErrorWithHttpParams() {
        ErrorCode errorCode = ErrorCode.REQUEST_VALIDATION_ERROR;
        HttpLogParameters params = new HttpLogParameters("http://example.com", 400, 150L, "REQUEST");
        String message = "Validation error";
        String body = "error-body";
        String headers = "error-headers";
        String properties = "error-properties";

        chainLogger.logErrorWithHttpParams(message, errorCode, params, body, headers, properties);

        verify(extendedErrorLogger).error(
                eq(errorCode),
                eq(params.toString() + " " + message + " Headers: {}, body: {}, exchange properties: {}"),
                eq(headers), eq(body), eq(properties)
        );
    }

    @Test
    void testLogHttpParams() {
        HttpLogParameters params = new HttpLogParameters("http://api.example.com", 200, 50L, "RESPONSE");
        String message = "HTTP operation completed";
        String body = "test-body";
        String headers = "test-headers";
        String properties = "test-properties";

        chainLogger.logHttpParams(message, params, body, headers, properties);

        verify(extendedErrorLogger).info(
                eq(params.toString() + " " + message + " Headers: {}, body: {}, exchange properties: {}"),
                eq(headers), eq(body), eq(properties)
        );
    }

    @Test
    void testLogFailedHttpOperation() {
        HttpOperationFailedException httpException = mock(HttpOperationFailedException.class);
        when(httpException.getMessage()).thenReturn("Connection timeout");
        when(httpException.getStatusCode()).thenReturn(504);

        String body = "test-body";
        String headers = "test-headers";
        String properties = "test-properties";
        long duration = 30000L;

        errorCodePrefixMock = mockStatic(ErrorCodePrefix.class);
        errorCodePrefixMock.when(ErrorCodePrefix::getCodePrefix).thenReturn("qip");

        chainLogger.logFailedHttpOperation(body, headers, properties, httpException, duration);

        ArgumentCaptor<ErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(ErrorCode.class);
        verify(extendedErrorLogger).error(
                errorCodeCaptor.capture(),
                contains("HTTP request failed. Headers: {}, body: {}, exchange properties: {}"),
                eq(headers), eq(body), eq(properties)
        );
        assertEquals(ErrorCode.SERVICE_RETURNED_ERROR, errorCodeCaptor.getValue());
    }

    @Test
    void testLogFailedOperation() {
        Exception exception = new RuntimeException("Processing failed");
        String body = "error-body";
        String headers = "error-headers";
        String properties = "error-properties";
        long duration = 100L;

        errorCodePrefixMock = mockStatic(ErrorCodePrefix.class);
        errorCodePrefixMock.when(ErrorCodePrefix::getCodePrefix).thenReturn("qip");

        chainLogger.logFailedOperation(body, headers, properties, exception, duration);

        ArgumentCaptor<ErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(ErrorCode.class);
        verify(extendedErrorLogger).error(
                errorCodeCaptor.capture(),
                contains("HTTP request failed. Headers: {}, body: {}, exchange properties: {}"),
                eq(headers), eq(body), eq(properties)
        );
        assertEquals(ErrorCode.UNEXPECTED_BUSINESS_ERROR, errorCodeCaptor.getValue());
    }

    @Test
    void testLogExternalServiceParams() {
        HttpLogParameters params = new HttpLogParameters("http://external-service.com/api", 200, 200L, "OUTBOUND");
        String message = "External service call";
        String body = "test-body";
        String headers = "test-headers";
        String properties = "test-properties";
        String envName = "PROD_ENV";
        String address = "http://external-service.com/api";

        chainLogger.logExternalServiceParams(message, params, body, headers, properties, envName, address);

        verify(extendedErrorLogger).info(
                eq(params.toString() + " " + message + " Headers: {}, body: {}, exchange properties: {}, external service environment name: {}, external service address: {}"),
                eq(headers), eq(body), eq(properties), eq(envName), eq(address)
        );
    }

    @Test
    void testLogExternalServiceParamsWithNullParams() {
        String message = "External service call";
        String body = "test-body";
        String headers = "test-headers";
        String properties = "test-properties";
        String envName = "PROD_ENV";
        String address = "http://external-service.com/api";

        chainLogger.logExternalServiceParams(message, null, body, headers, properties, envName, address);

        verify(extendedErrorLogger).info(
                eq(message + " Headers: {}, body: {}, exchange properties: {}, external service environment name: {}, external service address: {}"),
                eq(headers), eq(body), eq(properties), eq(envName), eq(address)
        );
    }

    // ========== Tests for AbstractChainLogger's concrete methods ==========

    @Test
    void testDebugInfoWarnError() {
        chainLogger.debug("Debug message");
        chainLogger.info("Info message");
        chainLogger.warn("Warn message");
        chainLogger.error("Error message");

        verify(extendedErrorLogger).debug("Debug message");
        verify(extendedErrorLogger).info("Info message");
        verify(extendedErrorLogger).warn("Warn message");
        verify(extendedErrorLogger).error("Error message");
    }

    @Test
    void testLogExchangeFinished() {
        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);

        chainLogger.logExchangeFinished(dbgProperties, "body", "headers", "properties", ExecutionStatus.SUCCESS, 100L);

        verify(extendedErrorLogger).info(
                eq("Session SUCCESS. Duration 100ms. Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
    }

    @Test
    void testLogHTTPExchangeFinished_Success() {
        Exchange exchange = new DefaultExchange();
        exchange.setProperty(org.apache.camel.Constants.SERVLET_REQUEST_URL, "http://test.com");
        Message message = new DefaultMessage();
        message.setHeader(org.apache.camel.Constants.HTTP_RESPONSE_CODE, 200);
        exchange.setMessage(message);

        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.INFO);

        chainLogger.logHTTPExchangeFinished(exchange, dbgProperties, "body", "headers", "properties", null, 150L, null);

        verify(extendedErrorLogger).info(
                contains("HTTP request completed. http://test.com 200 150ms RESPONSE Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
    }

    @Test
    void testLogHTTPExchangeFinished_Error() {
        Exchange exchange = new DefaultExchange();
        exchange.setProperty(org.apache.camel.Constants.SERVLET_REQUEST_URL, "http://test.com");
        Message message = new DefaultMessage();
        message.setHeader(org.apache.camel.Constants.HTTP_RESPONSE_CODE, 500);
        exchange.setMessage(message);

        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);

        errorCodePrefixMock = mockStatic(ErrorCodePrefix.class);
        errorCodePrefixMock.when(ErrorCodePrefix::getCodePrefix).thenReturn("qip");

        chainLogger.logHTTPExchangeFinished(exchange, dbgProperties, "body", "headers", "properties", null, 150L, null);

        ArgumentCaptor<ErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(ErrorCode.class);
        verify(extendedErrorLogger).error(
                errorCodeCaptor.capture(),
                contains("HTTP request failed. http://test.com 500 150ms RESPONSE Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
        assertEquals(ErrorCode.SERVICE_RETURNED_ERROR, errorCodeCaptor.getValue());
    }

    @Test
    void testSetLoggerContext() {
        Exchange exchange = new DefaultExchange();
        exchange.setProperty("sessionId", "session-123");

        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        Map<String, String> deploymentInfo = new HashMap<>();
        deploymentInfo.put("chainId", "chain-1");
        deploymentInfo.put("chainName", "Test Chain");
        when(dbgProperties.getDeploymentInfo()).thenReturn(deploymentInfo);
        when(dbgProperties.getElementProperty("node-1")).thenReturn(Map.of(
                "elementName", "Element 1",
                "elementId", "elem-1"
        ));

        when(tracingService.isTracingEnabled()).thenReturn(false);

        chainLogger.setLoggerContext(exchange, dbgProperties, "node-1", false);

        assertEquals("chain-1", MDC.get("chainId"));
        assertEquals("Test Chain", MDC.get("chainName"));
        assertEquals("session-123", MDC.get("sessionId"));
        assertEquals("elem-1", MDC.get("elementId"));
        assertEquals("Element 1", MDC.get("elementName"));
        assertEquals("chain", MDC.get("log_type"));
    }

    @Test
    void testLogBeforeProcess_SchedulerTrigger() {
        Exchange exchange = new DefaultExchange();
        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);
        when(runtimeProperties.getLogPayload()).thenReturn(null);
        when(dbgProperties.getElementProperty("node-1")).thenReturn(Map.of(
                "elementType", ChainElementType.SCHEDULER.name()
        ));

        chainLogger.logBeforeProcess(exchange, dbgProperties, "body", "headers", "properties", "node-1");

        verify(extendedErrorLogger).info("Scheduled chain trigger started");
    }

    @Test
    void testLogBeforeProcess_HttpTrigger() {
        Exchange exchange = new DefaultExchange();
        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);
        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY, LogPayload.HEADERS, LogPayload.PROPERTIES));
        when(dbgProperties.getElementProperty("node-1")).thenReturn(Map.of(
                "elementType", ChainElementType.HTTP_TRIGGER.name()
        ));

        chainLogger.logBeforeProcess(exchange, dbgProperties, "body", "headers", "properties", "node-1");

        verify(extendedErrorLogger).info(
                eq("Get request from trigger. Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
    }

    @Test
    void testLogBeforeProcess_HttpSender() {
        Exchange exchange = new DefaultExchange();
        exchange.getMessage().setHeader("httpUri", "http://example.com");
        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);
        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY, LogPayload.HEADERS, LogPayload.PROPERTIES));
        when(dbgProperties.getElementProperty("node-1")).thenReturn(Map.of(
                "elementType", ChainElementType.HTTP_SENDER.name()
        ));

        chainLogger.logBeforeProcess(exchange, dbgProperties, "body", "headers", "properties", "node-1");

        verify(extendedErrorLogger).info(
                eq("Send HTTP request. http://example.com null null REQUEST Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
    }

    @Test
    void testLogAfterProcess_HttpSender_Success() {
        Exchange exchange = new DefaultExchange();
        exchange.getMessage().setHeader("httpUri", "http://example.com");
        exchange.getMessage().setHeader("CamelHttpResponseCode", 200);
        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);
        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY, LogPayload.HEADERS, LogPayload.PROPERTIES));
        when(dbgProperties.getElementProperty("node-1")).thenReturn(Map.of(
                "elementType", ChainElementType.HTTP_SENDER.name()
        ));

        chainLogger.logAfterProcess(exchange, dbgProperties, "body", "headers", "properties", "node-1", 100L);

        verify(extendedErrorLogger).info(
                eq("HTTP request completed. http://example.com 200 100ms RESPONSE Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
    }

    @Test
    void testLogAfterProcess_KafkaSender_Failure() {
        Exchange exchange = new DefaultExchange();
        exchange.setException(new RuntimeException("Kafka error"));
        CamelDebuggerProperties dbgProperties = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(DeploymentRuntimeProperties.LogLoggingLevel.INFO);
        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY, LogPayload.HEADERS, LogPayload.PROPERTIES));
        when(dbgProperties.getElementProperty("node-1")).thenReturn(Map.of(
                "elementType", ChainElementType.KAFKA_SENDER.name()
        ));

        errorCodePrefixMock = mockStatic(ErrorCodePrefix.class);
        errorCodePrefixMock.when(ErrorCodePrefix::getCodePrefix).thenReturn("qip");

        chainLogger.logAfterProcess(exchange, dbgProperties, "body", "headers", "properties", "node-1", 100L);

        ArgumentCaptor<ErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(ErrorCode.class);
        verify(extendedErrorLogger).error(
                errorCodeCaptor.capture(),
                eq("Sending message to queue failed. Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
        assertEquals(ErrorCode.UNEXPECTED_BUSINESS_ERROR, errorCodeCaptor.getValue());
    }

    @Test
    void testLogRequest() {
        Exchange exchange = new DefaultExchange();
        exchange.getMessage().setHeader("httpUri", "http://example.com");

        chainLogger.logRequest(exchange, "body", "headers", "properties", null, null);

        verify(extendedErrorLogger).info(
                eq("Send HTTP request. http://example.com null null REQUEST Headers: {}, body: {}, exchange properties: {}"),
                eq("headers"), eq("body"), eq("properties")
        );
    }

    @Test
    void testLogRequestAttempt() {
        Exchange exchange = new DefaultExchange();
        ElementRetryProperties retryProps = new ElementRetryProperties(3, 1000);
        String elementId = "elem-1";

        // Set the retry properties in exchange
        exchange.setProperty("serviceCallRetry.elem-1", "1");
        exchange.setProperty("serviceCallRetryEnabled.elem-1", "true");

        chainLogger.logRequestAttempt(exchange, retryProps, elementId);

        verify(extendedErrorLogger).info("Request attempt: 2 (max 4).");
    }

    @Test
    void testLogRetryRequestAttempt() {
        Exchange exchange = new DefaultExchange();
        exchange.setProperty("serviceCallRetry.elem-1", "1");
        exchange.setProperty("serviceCallRetryEnabled.elem-1", "true");
        exchange.setProperty(org.apache.camel.Exchange.EXCEPTION_CAUGHT, new RuntimeException("Retry error"));

        ElementRetryProperties retryProps = new ElementRetryProperties(3, 1000);
        String elementId = "elem-1";

        chainLogger.logRetryRequestAttempt(exchange, retryProps, elementId);

        verify(extendedErrorLogger).warn(
                eq("Request failed and will be retried after 1000ms delay (retries left: 2): Retry error")
        );
    }
}

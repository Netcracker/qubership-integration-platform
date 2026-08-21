package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkus.test.InjectMock;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.enterprise.inject.Instance;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.logging.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.logging.ExtendedErrorLoggerFactory;
import org.qubership.integration.platform.engine.model.logging.LoggedPayloadValues;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "qip.logging.format", value = "json")
@TestConfigProperty(key = "application.prefix", value = "qip")
class JsonChainLoggerTest {

    JsonChainLogger jsonChainLogger;

    @InjectMock
    TracingService tracingService;

    @InjectMock
    Instance<OriginatingBusinessIdProvider> originatingBusinessIdProvider;

    @InjectMock
    PayloadExtractor payloadExtractor;

    @InjectMock
    ChainRuntimePropertiesService chainRuntimePropertiesService;

    @InjectMock
    VariablesService variablesService;

    LogExchangeMarkers logExchangeMarkers;

    ExtendedErrorLogger chainLogger;
    MockedStatic<ExtendedErrorLoggerFactory> factoryMock;

    @BeforeEach
    void setUp() {
        chainLogger = mock(ExtendedErrorLogger.class);
        doReturn(true).when(chainLogger).isErrorEnabled();
        factoryMock = Mockito.mockStatic(ExtendedErrorLoggerFactory.class);
        factoryMock.when(() -> ExtendedErrorLoggerFactory.getLogger(Mockito.any(Class.class)))
                .thenReturn(chainLogger);
        logExchangeMarkers = spy(new LogExchangeMarkers());
        jsonChainLogger = new JsonChainLogger(tracingService, originatingBusinessIdProvider, payloadExtractor,
                chainRuntimePropertiesService, variablesService, logExchangeMarkers);
    }

    @AfterEach
    void tearDown() {
        factoryMock.close();
    }

    @Test
    void shouldLogExchangeWithCorrectStructuredArguments() {
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"header1\":\"value1\"}")
                .properties("{\"prop1\":\"value1\"}")
                .body("test-body-content")
                .build();

        jsonChainLogger.logExchange("Test exchange message", loggedPayloadValues);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).info(
                eq("Test exchange message"),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        Map<String, String> actualArguments = getActualArguments(capturedArgs);

        assertEquals(actualArguments, buildExpectedExchangeArgs(loggedPayloadValues));
    }

    @Test
    void shouldLogExchangeWithTruncatedStructuredArgumentsValuesWhenLogFieldsMaxSizeIsSet() {
        jsonChainLogger.fieldValueMaxSize = 6;
        logExchangeMarkers.fieldValueMaxSize = 6;
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"header1\":\"value1\"}")
                .properties("{\"prop1\":\"value1\"}")
                .body("test-body-content")
                .build();

        jsonChainLogger.logExchange("Test exchange message", loggedPayloadValues);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).info(
                eq("Test exchange message"),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        Map<String, String> actualArguments = getActualArguments(capturedArgs);

        assertEquals(actualArguments, Map.of(
                "exchange_headers", "{\"head...",
                "exchange_properties", "{\"prop...",
                "exchange_body", "test-b..."));
    }

    @Test
    void shouldLogErrorWithExceptionAndErrorCode() {
        Exception exception = new RuntimeException("Test error message");
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"error-header\":\"headerValue\"}")
                .properties("{\"prop\":\"val\"}")
                .body("error-body-content")
                .build();
        try (MockedStatic<ErrorCode> mockedStatic = Mockito.mockStatic(ErrorCode.class)) {
            mockedStatic.when(() -> ErrorCode.match(exception))
                    .thenReturn(ErrorCode.SOCKET_TIMEOUT);
        }
        jsonChainLogger.logError("Error occurred", exception, loggedPayloadValues);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).error(
                eq("Error occurred Test error message"),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();

        assertEquals(getActualArguments(capturedArgs),
                buildExpectedErrorArgs(loggedPayloadValues, ErrorCode.UNEXPECTED_BUSINESS_ERROR));
    }

    @Test
    void shouldLogErrorWithHttpParamsAndErrorCode() {
        ErrorCode errorCode = ErrorCode.REQUEST_VALIDATION_ERROR;
        HttpLogParameters params = new HttpLogParameters("http://example.com/validate", 400, 150L, "REQUEST");

        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"content-type\":\"application/json\"}")
                .properties("{\"validation\":\"failed\"}")
                .body("{\"field\":\"invalid\"}")
                .build();

        jsonChainLogger.logErrorWithHttpParams("Validation error", errorCode, params, loggedPayloadValues);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).error(
                eq("Validation error"),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        assertEquals(getActualArguments(capturedArgs),
                buildExpectedHttpErrorArgs(loggedPayloadValues, params, errorCode));
    }

    @Test
    void shouldLogHttpParamsWithCorrectValues() {
        HttpLogParameters params = new HttpLogParameters("http://api.example.com/data", 200, 50L, "RESPONSE");
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"accept\":\"application/json\"}")
                .properties("{\"request-id\":\"123\"}")
                .body("{\"data\":\"response\"}")
                .build();

        jsonChainLogger.logHttpParams("HTTP operation completed", params, loggedPayloadValues);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).info(
                eq("HTTP operation completed"),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        assertEquals(getActualArguments(capturedArgs), buildExpectedHttpArgs(loggedPayloadValues, params));
    }

    @Test
    void shouldLogFailedHttpOperationWithErrorDetails() {
        HttpOperationFailedException httpException = mock(HttpOperationFailedException.class);
        doReturn("Connection timeout").when(httpException).getMessage();
        doReturn(504).when(httpException).getStatusCode();
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"retry\":\"attempt\"}")
                .properties("{\"timeout\":\"30s\"}")
                .body("request-payload")
                .build();
        try (MockedStatic<ErrorCode> mockedStatic = Mockito.mockStatic(ErrorCode.class)) {
            mockedStatic.when(() -> ErrorCode.match(httpException))
                    .thenReturn(ErrorCode.SOCKET_TIMEOUT);
        }

        jsonChainLogger.logFailedHttpOperation(loggedPayloadValues, httpException, 30000L);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).error(
                eq("HTTP request failed."),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        assertEquals(getActualArguments(capturedArgs), buildExpectedFailedHttpArgs(loggedPayloadValues,
                HttpLogParameters.createErrorResponse(httpException, 30000L), ErrorCode.SERVICE_RETURNED_ERROR));
    }

    @Test
    void shouldLogFailedOperationWithError() {
        Exception exception = new RuntimeException("Processing failed");
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"trace-id\":\"abc123\"}")
                .properties("{\"step\":\"validation\"}")
                .body("failure-details")
                .build();
        try (MockedStatic<ErrorCode> mockedStatic = Mockito.mockStatic(ErrorCode.class)) {
            mockedStatic.when(() -> ErrorCode.match(exception))
                    .thenReturn(ErrorCode.SOCKET_TIMEOUT);
        }

        jsonChainLogger.logFailedOperation(loggedPayloadValues, exception, 100L);

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).error(
                eq("HTTP request failed."),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        assertEquals(getActualArguments(capturedArgs),
                buildExpectedFailedHttpArgs(loggedPayloadValues, HttpLogParameters.createResponse(100L),
                        ErrorCode.UNEXPECTED_BUSINESS_ERROR));
    }

    @Test
    void shouldLogExternalServiceParamsWithServiceInfo() {
        HttpLogParameters params = new HttpLogParameters("http://external-service.com/api", 200, 200L, "OUTBOUND");
        LoggedPayloadValues loggedPayloadValues = LoggedPayloadValues.builder()
                .headers("{\"external-call\":\"true\"}")
                .properties("{\"env\":\"production\"}")
                .body("{\"request\":\"data\"}")
                .build();

        jsonChainLogger.logExternalServiceParams("External service call",
                params, loggedPayloadValues, "PROD_ENV", "http://external-service.com/api");

        ArgumentCaptor<Object[]> varargsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(chainLogger).info(
                eq("External service call"),
                varargsCaptor.capture());
        List<Object[]> capturedArgs = varargsCaptor.getAllValues();
        assertEquals(getActualArguments(capturedArgs),
                buildExpectedExternalServiceArgs(loggedPayloadValues, params, "PROD_ENV",
                        "http://external-service.com/api"));
    }

    private Map<String, String> buildExpectedExchangeArgs(LoggedPayloadValues values) {
        return new HashMap<>(Map.of(
                "exchange_headers", values.getHeaders(),
                "exchange_body", values.getBody(),
                "exchange_properties", values.getProperties()));
    }

    private Map<String, String> buildExpectedErrorArgs(LoggedPayloadValues values, ErrorCode errorCode) {
        Map<String, String> result = buildExpectedExchangeArgs(values);
        result.put("error_code", errorCode.getFormattedCode());
        return result;
    }

    private Map<String, String> buildExpectedHttpArgs(LoggedPayloadValues values, HttpLogParameters params) {
        Map<String, String> result = buildExpectedExchangeArgs(values);
        result.put("url", params.getTargetUrl());
        result.put("response_code", params.getResponseCode());
        result.put("response_time", params.getResponseTime());
        result.put("direction", params.getDirection());
        return result;
    }

    private Map<String, String> buildExpectedHttpErrorArgs(LoggedPayloadValues values, HttpLogParameters params,
            ErrorCode errorCode) {
        Map<String, String> result = buildExpectedHttpArgs(values, params);
        result.put("error_code", errorCode.getFormattedCode());
        return result;
    }

    private Map<String, String> buildExpectedFailedHttpArgs(LoggedPayloadValues values, HttpLogParameters params,
            ErrorCode errorCode) {
        Map<String, String> result = buildExpectedExchangeArgs(values);
        result.put("url", params.getTargetUrl());
        result.put("response_code", params.getResponseCode());
        result.put("response_time", params.getResponseTime());
        result.put("direction", params.getDirection());
        result.put("error_code", errorCode.getFormattedCode());
        return result;
    }

    private Map<String, String> buildExpectedExternalServiceArgs(LoggedPayloadValues values, HttpLogParameters params,
            String envName, String address) {
        Map<String, String> result = buildExpectedExchangeArgs(values);
        result.put("external_service_env_name", envName);
        result.put("external_service_address", address);
        if (params != null) {
            result.put("url", params.getTargetUrl());
            result.put("response_code", params.getResponseCode());
            result.put("response_time", params.getResponseTime());
            result.put("direction", params.getDirection());
        }
        return result;
    }

    private Map<String, String> getActualArguments(List<Object[]> capturedArgs) {
        Map<String, String> actualArguments = new HashMap<>();
        try {
            for (Object o : capturedArgs.get(0)) {
                Field keyField = o.getClass().getDeclaredField("key");
                keyField.setAccessible(true);

                Field valueField = o.getClass().getDeclaredField("value");
                valueField.setAccessible(true);

                actualArguments.put((String) keyField.get(o), (String) valueField.get(o));
            }
            return actualArguments;
        } catch (Exception e) {
            throw new RuntimeException("Unable to read actual argument keys and values", e);
        }
    }
}

package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkiverse.loggingjson.providers.KeyValueStructuredArgument;
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

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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

    ExtendedErrorLogger chainLogger;
    MockedStatic<ExtendedErrorLoggerFactory> factoryMock;

    @BeforeEach
    void setUp() {
        chainLogger = mock(ExtendedErrorLogger.class);
        doReturn(true).when(chainLogger).isErrorEnabled();
        factoryMock = Mockito.mockStatic(ExtendedErrorLoggerFactory.class);
        factoryMock.when(() -> ExtendedErrorLoggerFactory.getLogger(Mockito.any(Class.class)))
                .thenReturn(chainLogger);
        jsonChainLogger = new JsonChainLogger(tracingService, originatingBusinessIdProvider, payloadExtractor,
                chainRuntimePropertiesService, variablesService);
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
        Object[] expected = buildExpectedExchangeArgs(loggedPayloadValues).toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
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
        Object[] expected = buildExpectedErrorArgs(loggedPayloadValues, ErrorCode.UNEXPECTED_BUSINESS_ERROR).toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
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
        Object[] expected = buildExpectedHttpErrorArgs(loggedPayloadValues, params, errorCode).toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
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
        Object[] expected = buildExpectedHttpArgs(loggedPayloadValues, params).toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
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
        Object[] expected = buildExpectedFailedHttpArgs(loggedPayloadValues,
                HttpLogParameters.createErrorResponse(httpException, 30000L), ErrorCode.SOCKET_TIMEOUT)
                .toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
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
        Object[] expected = buildExpectedFailedHttpArgs(loggedPayloadValues, HttpLogParameters.createResponse(100L),
                ErrorCode.UNEXPECTED_BUSINESS_ERROR)
                .toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
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
        Object[] expected = buildExpectedExternalServiceArgs(loggedPayloadValues, params, "PROD_ENV",
                "http://external-service.com/api").toArray();
        Arrays.deepEquals(expected, capturedArgs.get(0));
    }

    private List<Object> buildExpectedExchangeArgs(LoggedPayloadValues values) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(KeyValueStructuredArgument.kv("exchange_headers", values.getHeaders()));
        args.add(KeyValueStructuredArgument.kv("exchange_body", values.getBody()));
        args.add(KeyValueStructuredArgument.kv("exchange_properties", values.getProperties()));
        return args;
    }

    private List<Object> buildExpectedErrorArgs(LoggedPayloadValues values, ErrorCode errorCode) {
        List<Object> args = buildExpectedExchangeArgs(values);
        args.add(KeyValueStructuredArgument.kv("error_code", errorCode.getFormattedCode()));
        return args;
    }

    private List<Object> buildExpectedHttpArgs(LoggedPayloadValues values, HttpLogParameters params) {
        List<Object> args = buildExpectedExchangeArgs(values);
        args.add(KeyValueStructuredArgument.kv("url", params.getTargetUrl()));
        args.add(KeyValueStructuredArgument.kv("response_code", params.getResponseCode()));
        args.add(KeyValueStructuredArgument.kv("response_time", params.getResponseTime()));
        args.add(KeyValueStructuredArgument.kv("direction", params.getDirection()));
        return args;
    }

    private List<Object> buildExpectedHttpErrorArgs(LoggedPayloadValues values, HttpLogParameters params,
            ErrorCode errorCode) {
        List<Object> args = buildExpectedHttpArgs(values, params);
        args.add(KeyValueStructuredArgument.kv("error_code", errorCode.getFormattedCode()));
        return args;
    }

    private List<Object> buildExpectedFailedHttpArgs(LoggedPayloadValues values, HttpLogParameters params,
            ErrorCode errorCode) {
        List<Object> args = buildExpectedExchangeArgs(values);
        args.add(KeyValueStructuredArgument.kv("url", params.getTargetUrl()));
        args.add(KeyValueStructuredArgument.kv("response_code", params.getResponseCode()));
        args.add(KeyValueStructuredArgument.kv("response_time", params.getResponseTime()));
        args.add(KeyValueStructuredArgument.kv("direction", params.getDirection()));
        args.add(KeyValueStructuredArgument.kv("error_code", errorCode.getFormattedCode()));
        return args;
    }

    private List<Object> buildExpectedExternalServiceArgs(LoggedPayloadValues values, HttpLogParameters params,
            String envName, String address) {
        List<Object> args = buildExpectedExchangeArgs(values);
        args.add(KeyValueStructuredArgument.kv("external_service_env_name", envName));
        args.add(KeyValueStructuredArgument.kv("external_service_address", address));
        if (params != null) {
            args.add(KeyValueStructuredArgument.kv("url", params.getTargetUrl()));
            args.add(KeyValueStructuredArgument.kv("response_code", params.getResponseCode()));
            args.add(KeyValueStructuredArgument.kv("response_time", params.getResponseTime()));
            args.add(KeyValueStructuredArgument.kv("direction", params.getDirection()));
        }
        return args;
    }
}

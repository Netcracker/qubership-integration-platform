package org.qubership.integration.platform.engine.service.debugger.logging;

import net.logstash.logback.marker.LogstashMarker;
import net.logstash.logback.marker.Markers;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCodePrefix;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;
import org.slf4j.Marker;

import java.util.Optional;

import static net.logstash.logback.marker.Markers.append;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class JsonChainLoggerTest {

    JsonChainLogger jsonChainLogger;

    @Mock
    TracingService tracingService;

    @Mock
    Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider;

    ExtendedErrorLogger chainLogger;
    MockedStatic<ExtendedErrorLoggerFactory> factoryMock;

    @BeforeEach
    void setUp() {
        chainLogger = mock(ExtendedErrorLogger.class);
        factoryMock = mockStatic(ExtendedErrorLoggerFactory.class);
        factoryMock.when(() -> ExtendedErrorLoggerFactory.getLogger(any(Class.class)))
                .thenReturn(chainLogger);
        jsonChainLogger = new JsonChainLogger(tracingService, originatingBusinessIdProvider);
    }

    @AfterEach
    void tearDown() {
        factoryMock.close();
    }

    @Test
    void shouldLogExchangeWithCorrectStructuredArguments() {
        String body = "test-body-content";
        String header = "header";
        String properties = "properties";

        jsonChainLogger.logExchange("Test exchange message", body, header, properties);

        LogstashMarker expectedMarker = buildExpectedExchangeArgs(body, header, properties);

        verify(chainLogger).info(
                argThat((Marker arg) -> {
                    assertEquals(expectedMarker.toString(), arg.toString());
                    return true;
                }),
                eq("Test exchange message"));
    }

    @Test
    void shouldLogErrorWithExceptionAndErrorCode() {
        Exception exception = new RuntimeException("Test error message");
        String body = "error-body-content";
        String header = "header";
        String properties = "properties";

        try (MockedStatic<ErrorCodePrefix> mockedStatic = mockStatic(ErrorCodePrefix.class)) {
            mockedStatic.when(ErrorCodePrefix::getCodePrefix)
                    .thenReturn("qip");
            jsonChainLogger.logError("Error occurred", exception, body, header, properties);

            LogstashMarker expectedMarker = buildExpectedErrorArgs(body, header, properties,
                    ErrorCode.UNEXPECTED_BUSINESS_ERROR);

            verify(chainLogger).error(
                    argThat((Marker arg) -> {
                        assertEquals(expectedMarker.toString(), arg.toString());
                        return true;
                    }),
                    eq("{} {}"),
                    eq("Error occurred"),
                    eq("Test error message"));
        }
    }

    @Test
    void shouldLogErrorWithHttpParamsAndErrorCode() {
        ErrorCode errorCode = ErrorCode.REQUEST_VALIDATION_ERROR;
        HttpLogParameters params = new HttpLogParameters("http://example.com/validate", 400, 150L, "REQUEST");

        String body = "error-body-content";
        String header = "header";
        String properties = "properties";

        try (MockedStatic<ErrorCodePrefix> mockedStatic = mockStatic(ErrorCodePrefix.class)) {
            mockedStatic.when(ErrorCodePrefix::getCodePrefix)
                    .thenReturn("qip");

            jsonChainLogger.logErrorWithHttpParams("Validation error", errorCode, params, body, header, properties);

            LogstashMarker expectedMarker = buildExpectedHttpErrorArgs(body, header, properties, params, errorCode);

            verify(chainLogger).error(
                    argThat((Marker arg) -> {
                        assertEquals(expectedMarker.toString(), arg.toString());
                        return true;
                    }),
                    eq("Validation error"));
        }
    }

    @Test
    void shouldLogHttpParamsWithCorrectValues() {
        HttpLogParameters params = new HttpLogParameters("http://api.example.com/data", 200, 50L, "RESPONSE");

        String body = "test-body-content";
        String header = "header";
        String properties = "properties";

        jsonChainLogger.logHttpParams("HTTP operation completed", params, body, header, properties);

        LogstashMarker expectedMarker = buildExpectedHttpArgs(body, header, properties, params);

        verify(chainLogger).info(
                argThat((Marker arg) -> {
                    assertEquals(expectedMarker.toString(), arg.toString());
                    return true;
                }),
                eq("HTTP operation completed"));
    }

    @Test
    void shouldLogFailedHttpOperationWithErrorDetails() {
        HttpOperationFailedException httpException = mock(HttpOperationFailedException.class);
        doReturn("Connection timeout").when(httpException).getMessage();
        doReturn(504).when(httpException).getStatusCode();

        String body = "test-body-content";
        String header = "header";
        String properties = "properties";

        try (MockedStatic<ErrorCodePrefix> mockedStatic = mockStatic(ErrorCodePrefix.class)) {
            mockedStatic.when(ErrorCodePrefix::getCodePrefix)
                    .thenReturn("qip");

            jsonChainLogger.logFailedHttpOperation(body, header, properties, httpException, 30000L);

            LogstashMarker expectedMarker = buildExpectedFailedHttpArgs(body, header, properties,
                    HttpLogParameters.createErrorResponse(httpException, 30000L), ErrorCode.SERVICE_RETURNED_ERROR);

            verify(chainLogger).error(
                    argThat((Marker arg) -> {
                        assertEquals(expectedMarker.toString(), arg.toString());
                        return true;
                    }),
                    eq("HTTP request failed."));
        }
    }

    @Test
    void shouldLogFailedOperationWithError() {
        Exception exception = new RuntimeException("Processing failed");
        String body = "error-body-content";
        String header = "header";
        String properties = "properties";

        try (MockedStatic<ErrorCodePrefix> mockedStatic = mockStatic(ErrorCodePrefix.class)) {
            mockedStatic.when(ErrorCodePrefix::getCodePrefix)
                    .thenReturn("qip");

            jsonChainLogger.logFailedOperation(body, header, properties, exception, 100L);

            LogstashMarker expectedMarker = buildExpectedFailedHttpArgs(body, header, properties,
                    HttpLogParameters.createResponse(100L),
                    ErrorCode.UNEXPECTED_BUSINESS_ERROR);

            verify(chainLogger).error(
                    argThat((Marker arg) -> {
                        assertEquals(expectedMarker.toString(), arg.toString());
                        return true;
                    }),
                    eq("HTTP request failed."));
        }
    }

    @Test
    void shouldLogExternalServiceParamsWithServiceInfo() {
        HttpLogParameters params = new HttpLogParameters("http://external-service.com/api", 200, 200L, "OUTBOUND");

        String body = "error-body-content";
        String header = "header";
        String properties = "properties";

        jsonChainLogger.logExternalServiceParams("External service call",
                params, body, header, properties, "PROD_ENV", "http://external-service.com/api");

        LogstashMarker expectedMarker = buildExpectedExternalServiceArgs(body, header, properties, params, "PROD_ENV",
                "http://external-service.com/api");

        verify(chainLogger).info(
                argThat((Marker arg) -> {
                    assertEquals(expectedMarker.toString(), arg.toString());
                    return true;
                }),
                eq("External service call"));
    }

    private LogstashMarker buildExpectedExchangeArgs(String body, String header, String properties) {
        LogstashMarker expectedMarker = Markers.append("exchange_body", body)
                .and(append("exchange_headers", header))
                .and(append("exchange_properties", properties));
        return expectedMarker;
    }

    private LogstashMarker buildExpectedErrorArgs(String body, String header, String properties, ErrorCode errorCode) {
        return buildExpectedExchangeArgs(body, header, properties)
                .and(append("error_code", errorCode.getFormattedCode()));
    }

    private LogstashMarker buildExpectedHttpArgs(String body, String header, String properties,
            HttpLogParameters params) {
        return buildExpectedExchangeArgs(body, header, properties)
                .and(append("url", params.getTargetUrl()))
                .and(append("response_code", params.getResponseCode()))
                .and(append("response_time", params.getResponseTime()))
                .and(append("direction", params.getDirection()));
    }

    private LogstashMarker buildExpectedHttpErrorArgs(String body, String header, String properties,
            HttpLogParameters params,
            ErrorCode errorCode) {
        return buildExpectedHttpArgs(body, header, properties, params)
                .and(append("error_code", errorCode.getFormattedCode()));
    }

    private LogstashMarker buildExpectedFailedHttpArgs(String body, String header, String properties,
            HttpLogParameters params,
            ErrorCode errorCode) {
        return buildExpectedExchangeArgs(body, header, properties)
                .and(append("url", params.getTargetUrl()))
                .and(append("response_code", params.getResponseCode()))
                .and(append("response_time", params.getResponseTime()))
                .and(append("direction", params.getDirection()))
                .and(append("error_code", errorCode.getFormattedCode()));
    }

    private LogstashMarker buildExpectedExternalServiceArgs(String body, String header, String properties,
            HttpLogParameters params,
            String envName, String address) {
        LogstashMarker marker = buildExpectedExchangeArgs(body, header, properties)
                .and(append("external_service_env_name", envName))
                .and(append("external_service_address", address));
        if (params != null) {
            marker.and(append("url", params.getTargetUrl()))
                    .and(append("response_code", params.getResponseCode()))
                    .and(append("response_time", params.getResponseTime()))
                    .and(append("direction", params.getDirection()));
        }
        return marker;
    }
}

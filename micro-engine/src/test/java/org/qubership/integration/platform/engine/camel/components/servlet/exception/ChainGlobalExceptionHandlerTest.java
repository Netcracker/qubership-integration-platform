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


package org.qubership.integration.platform.engine.camel.components.servlet.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.CamelAuthorizationException;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.http.HttpConstants;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.qubership.integration.platform.engine.camel.components.directvm.ChainConsumerNotAvailableException;
import org.qubership.integration.platform.engine.camel.exceptions.IterationLimitException;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTerminatedException;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.errorhandling.ResponseValidationException;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.service.debugger.util.ChainExceptionResponseHandlerService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainGlobalExceptionHandlerTest {

    private ChainGlobalExceptionHandler chainGlobalExceptionHandler;

    @Mock
    Exchange exchange;

    ChainExceptionResponseHandlerService service;
    Map<String, String> extras;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

        chainGlobalExceptionHandler = spy(new ChainGlobalExceptionHandler(mapper));
        service = new ChainExceptionResponseHandlerService(chainGlobalExceptionHandler);

        exchange = MockExchanges.withMessage();
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, "S-1");
        exchange.setProperty(CamelConstants.ChainProperties.FAILED_ELEMENT_ID, "E-1");

        extras = new HashMap<>();
    }

    @Test
    void shouldDispatchToDefaultHandlerWhenThrowable() throws Exception {
        service.handleExceptionResponse(exchange, new RuntimeException("boom"));

        verify(chainGlobalExceptionHandler).handleGeneralException(any(Throwable.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchValidationExceptionWhenSpecific() throws Exception {
        service.handleExceptionResponse(exchange, new ValidationException("bad"));

        verify(chainGlobalExceptionHandler).handleException(any(ValidationException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchResponseValidationExceptionWhenSpecific() throws Exception {
        service.handleExceptionResponse(exchange, new ResponseValidationException("bad"));

        verify(chainGlobalExceptionHandler).handleException(any(ResponseValidationException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchCamelAuthorizationExceptionWhenSpecific() throws Exception {
        service.handleExceptionResponse(exchange, new CamelAuthorizationException("denied", exchange));

        verify(chainGlobalExceptionHandler).handleException(any(CamelAuthorizationException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchUnknownHostExceptionWhenSpecific() throws Exception {
        service.handleExceptionResponse(exchange, new UnknownHostException("no_host"));

        verify(chainGlobalExceptionHandler).handleException(any(UnknownHostException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchHttpOperationFailedExceptionWhen504RedirectsToTimeout() throws Exception {
        doNothing().when(chainGlobalExceptionHandler).handleTimeoutException(any(Exception.class), any(), any(), anyMap());

        HttpOperationFailedException httpEx = new HttpOperationFailedException("http://x", 504, "Gateway Timeout", null, null, null);

        service.handleExceptionResponse(exchange, httpEx);

        verify(chainGlobalExceptionHandler).handleTimeoutException(eq(httpEx), eq(exchange), eq(ErrorCode.SOCKET_TIMEOUT), anyMap());
    }

    @Test
    void shouldDispatchHttpOperationFailedExceptionWhenNon504GoesServiceReturnedError() throws Exception {
        HttpOperationFailedException httpEx = new HttpOperationFailedException("http://x", 400, "Bad Request", null, null, null);

        service.handleExceptionResponse(exchange, httpEx);

        verify(chainGlobalExceptionHandler).handleException(eq(httpEx), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchTimeoutExceptionWhenKafkaTimeout() throws Exception {
        service.handleExceptionResponse(exchange, new TimeoutException("kafka timeout"));

        verify(chainGlobalExceptionHandler).handleException(any(TimeoutException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchIterationLimitExceptionWhenLimitReached() throws Exception {
        service.handleExceptionResponse(exchange, new IterationLimitException("limit"));

        verify(chainGlobalExceptionHandler).handleException(any(IterationLimitException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchChainExecutionTimeoutExceptionWhenTimeout() throws Exception {
        service.handleExceptionResponse(exchange, new ChainExecutionTimeoutException("t"));

        verify(chainGlobalExceptionHandler).handleException(any(ChainExecutionTimeoutException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchChainExecutionTerminatedExceptionWhenTerminated() throws Exception {
        service.handleExceptionResponse(exchange, new ChainExecutionTerminatedException("x"));

        verify(chainGlobalExceptionHandler).handleException(any(ChainExecutionTerminatedException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchChainConsumerNotAvailableExceptionWhenNoConsumer() throws Exception {
        service.handleExceptionResponse(exchange, new ChainConsumerNotAvailableException("chain", exchange));

        verify(chainGlobalExceptionHandler).handleChainCallException(any(ChainConsumerNotAvailableException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchSocketTimeoutExceptionWhenOccurs() throws Exception {
        service.handleExceptionResponse(exchange, new SocketTimeoutException("st"));

        verify(chainGlobalExceptionHandler).handleTimeoutException(any(Exception.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchInvalidProtocolBufferExceptionWhenOccurs() throws Exception {
        service.handleExceptionResponse(exchange, new InvalidProtocolBufferException("ipb"));

        verify(chainGlobalExceptionHandler).handleInvalidProtocolBufferException(any(InvalidProtocolBufferException.class), eq(exchange), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldHandleResponseValidationDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.RESPONSE_VALIDATION_ERROR;

        chainGlobalExceptionHandler.handleException(new ResponseValidationException("bad"), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
        assertEquals("bad", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_VALIDATION_RESULT));
    }

    @Test
    void shouldHandleCamelAuthorizationDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.AUTHORIZATION_ERROR;

        chainGlobalExceptionHandler.handleException(new CamelAuthorizationException("denied", exchange), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
    }

    @Test
    void shouldHandleKafkaTimeoutDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.KAFKA_TIMEOUT;

        chainGlobalExceptionHandler.handleException(new TimeoutException("t"), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
    }

    @Test
    void shouldHandleIterationLimitDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.LOOP_ITERATIONS_LIMIT_REACHED;

        chainGlobalExceptionHandler.handleException(new IterationLimitException("l"), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
    }

    @Test
    void shouldHandleChainExecutionTimeoutDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.TIMEOUT_REACHED;

        chainGlobalExceptionHandler.handleException(new ChainExecutionTimeoutException("t"), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
    }

    @Test
    void shouldHandleChainExecutionTerminatedDirectly() throws Exception {
        ErrorCode err = ErrorCode.FORCE_TERMINATED;

        chainGlobalExceptionHandler.handleException(new ChainExecutionTerminatedException("x"), exchange, err, extras);

        verifyCommonHeaders(exchange, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleChainConsumerNotAvailableDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.CHAIN_ENDPOINT_NOT_FOUND;

        chainGlobalExceptionHandler.handleChainCallException(new ChainConsumerNotAvailableException("c", exchange), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
    }

    @Test
    void shouldHandleInvalidProtocolBufferWhenCalledDirectly() throws Exception {
        ErrorCode errorCode = ErrorCode.REQUEST_VALIDATION_ERROR;

        chainGlobalExceptionHandler.handleInvalidProtocolBufferException(new InvalidProtocolBufferException("ipb"), exchange, errorCode, extras);

        verifyCommonHeaders(exchange, errorCode.getHttpErrorCode());
    }

    private void verifyCommonHeaders(Exchange ex, int httpCode) throws Exception {
        Message message = MockExchanges.getMessage(ex);
        verify(message).removeHeaders("*");
        verify(message).setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        verify(message).setHeader(HttpConstants.HTTP_RESPONSE_CODE, httpCode);
        verify(message).setBody("{\"ok\":true}");
    }
}

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
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainGlobalExceptionHandlerTest {

    private ChainGlobalExceptionHandler newHandler(ObjectMapper mapper) throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");
        return new ChainGlobalExceptionHandler(mapper);
    }

    private Exchange newExchangeWithExtras() {
        Exchange ex = MockExchanges.withMessage();
        ex.setProperty(CamelConstants.Properties.SESSION_ID, "S-1");
        ex.setProperty(CamelConstants.ChainProperties.FAILED_ELEMENT_ID, "E-1");
        return ex;
    }

    private void verifyCommonHeaders(Exchange ex, int httpCode) throws Exception {
        Message msg = MockExchanges.getMessage(ex);
        verify(msg).removeHeaders("*");
        verify(msg).setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        verify(msg).setHeader(HttpConstants.HTTP_RESPONSE_CODE, httpCode);
        verify(msg).setBody("{\"ok\":true}");
    }

    @Test
    void shouldDispatchToDefaultHandlerWhenThrowable() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new RuntimeException("boom"));

        verify(spyHandler).handleGeneralException(any(Throwable.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchValidationExceptionWhenSpecific() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new ValidationException("bad"));

        verify(spyHandler).handleException(any(ValidationException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchResponseValidationExceptionWhenSpecific() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new ResponseValidationException("bad"));

        verify(spyHandler).handleException(any(ResponseValidationException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchCamelAuthorizationExceptionWhenSpecific() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new CamelAuthorizationException("denied", ex));

        verify(spyHandler).handleException(any(CamelAuthorizationException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchUnknownHostExceptionWhenSpecific() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new UnknownHostException("nohost"));

        verify(spyHandler).handleException(any(UnknownHostException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchHttpOperationFailedExceptionWhen504RedirectsToTimeout() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        doNothing().when(spyHandler).handleTimeoutException(any(Exception.class), any(), any(), anyMap());

        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();
        var httpEx = new HttpOperationFailedException("http://x", 504, "Gateway Timeout", null, null, null);

        service.handleExceptionResponse(ex, httpEx);

        verify(spyHandler).handleTimeoutException(eq(httpEx), eq(ex), eq(ErrorCode.SOCKET_TIMEOUT), anyMap());
    }

    @Test
    void shouldDispatchHttpOperationFailedExceptionWhenNon504GoesServiceReturnedError() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();
        var httpEx = new HttpOperationFailedException("http://x", 400, "Bad Request", null, null, null);

        service.handleExceptionResponse(ex, httpEx);

        verify(spyHandler).handleException(eq(httpEx), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchTimeoutExceptionWhenKafkaTimeout() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new TimeoutException("kafka timeout"));

        verify(spyHandler).handleException(any(TimeoutException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchIterationLimitExceptionWhenLimitReached() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new IterationLimitException("limit"));

        verify(spyHandler).handleException(any(IterationLimitException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchChainExecutionTimeoutExceptionWhenTimeout() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new ChainExecutionTimeoutException("t"));

        verify(spyHandler).handleException(any(ChainExecutionTimeoutException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchChainExecutionTerminatedExceptionWhenTerminated() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new ChainExecutionTerminatedException("x"));

        verify(spyHandler).handleException(any(ChainExecutionTerminatedException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchChainConsumerNotAvailableExceptionWhenNoConsumer() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new ChainConsumerNotAvailableException("chain", ex));

        verify(spyHandler).handleChainCallException(any(ChainConsumerNotAvailableException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchSocketTimeoutExceptionWhenOccurs() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new SocketTimeoutException("st"));

        verify(spyHandler).handleTimeoutException(any(Exception.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldDispatchInvalidProtocolBufferExceptionWhenOccurs() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var spyHandler = spy(newHandler(mapper));
        var service = new ChainExceptionResponseHandlerService(spyHandler);
        var ex = newExchangeWithExtras();

        service.handleExceptionResponse(ex, new InvalidProtocolBufferException("ipb"));

        verify(spyHandler).handleInvalidProtocolBufferException(any(InvalidProtocolBufferException.class), eq(ex), any(ErrorCode.class), anyMap());
    }

    @Test
    void shouldHandleResponseValidationDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.RESPONSE_VALIDATION_ERROR;

        handler.handleException(new ResponseValidationException("bad"), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
        assertEquals("bad", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_VALIDATION_RESULT));
    }

    @Test
    void shouldHandleCamelAuthorizationDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.AUTHORIZATION_ERROR;

        handler.handleException(new CamelAuthorizationException("denied", ex), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleKafkaTimeoutDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.KAFKA_TIMEOUT;

        handler.handleException(new TimeoutException("t"), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleIterationLimitDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.LOOP_ITERATIONS_LIMIT_REACHED;

        handler.handleException(new IterationLimitException("l"), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleChainExecutionTimeoutDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.TIMEOUT_REACHED;

        handler.handleException(new ChainExecutionTimeoutException("t"), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleChainExecutionTerminatedDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.FORCE_TERMINATED;

        handler.handleException(new ChainExecutionTerminatedException("x"), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleChainConsumerNotAvailableDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.CHAIN_ENDPOINT_NOT_FOUND;

        handler.handleChainCallException(new ChainConsumerNotAvailableException("c", ex), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }

    @Test
    void shouldHandleInvalidProtocolBufferWhenCalledDirectly() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        var handler = newHandler(mapper);
        var ex = newExchangeWithExtras();
        var extras = new HashMap<String, String>();
        var err = ErrorCode.REQUEST_VALIDATION_ERROR;

        handler.handleInvalidProtocolBufferException(new InvalidProtocolBufferException("ipb"), ex, err, extras);

        verifyCommonHeaders(ex, err.getHttpErrorCode());
    }
}

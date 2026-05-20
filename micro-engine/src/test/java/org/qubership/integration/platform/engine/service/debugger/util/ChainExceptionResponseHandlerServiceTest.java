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

package org.qubership.integration.platform.engine.service.debugger.util;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.ChainGlobalExceptionHandler;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainExceptionResponseHandlerServiceTest {

    ChainGlobalExceptionHandler handlerMock;
    ChainExceptionResponseHandlerService service;

    AtomicReference<Method> calledMethod;
    AtomicReference<Object[]> calledArgs;

    @BeforeEach
    void setUp() {
        calledMethod = new AtomicReference<>();
        calledArgs = new AtomicReference<>();

        Answer<Object> recorder = invocation -> {
            calledMethod.set(invocation.getMethod());
            calledArgs.set(invocation.getArguments());
            return null;
        };
        handlerMock = mock(ChainGlobalExceptionHandler.class, withSettings().defaultAnswer(recorder));
        service = new ChainExceptionResponseHandlerService(handlerMock);
    }

    @Test
    void shouldInvokeSpecificHandlerWhenUnknownHostException() throws Exception {
        UnknownHostException ex = new UnknownHostException("boom");
        Exchange exchange = mockExchange("S-1", "E-1");

        service.handleExceptionResponse(exchange, ex);

        assertNotNull(calledMethod.get());
        assertEquals("handleException", calledMethod.get().getName());
        assertEquals(UnknownHostException.class, calledMethod.get().getParameterTypes()[0]);

        assertEquals(4, calledArgs.get().length);
        assertSame(ex, calledArgs.get()[0]);
        assertSame(exchange, calledArgs.get()[1]);
        assertEquals(ErrorCode.match(ex), calledArgs.get()[2]);

        @SuppressWarnings("unchecked")
        Map<String, String> extras = (Map<String, String>) calledArgs.get()[3];
        assertEquals("S-1", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_SESSION_ID));
        assertEquals("E-1", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_FAILED_ELEMENT));
    }

    @Test
    void shouldInvokeSpecificHandlerWhenKafkaTimeout() throws Exception {
        org.apache.kafka.common.errors.TimeoutException ex =
                new org.apache.kafka.common.errors.TimeoutException("timeout");
        Exchange exchange = mockExchange("S-2", "E-2");

        service.handleExceptionResponse(exchange, ex);

        assertNotNull(calledMethod.get());
        assertEquals("handleException", calledMethod.get().getName());
        assertEquals(org.apache.kafka.common.errors.TimeoutException.class,
                calledMethod.get().getParameterTypes()[0]);

        assertEquals(ErrorCode.match(ex), calledArgs.get()[2]);

        @SuppressWarnings("unchecked")
        Map<String, String> extras = (Map<String, String>) calledArgs.get()[3];
        assertEquals("S-2", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_SESSION_ID));
        assertEquals("E-2", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_FAILED_ELEMENT));
    }

    @Test
    void shouldInvokeDefaultHandlerWhenNoSpecificMatch() throws Exception {
        IllegalArgumentException ex = new IllegalArgumentException("no-match");
        Exchange exchange = mockExchange("S-3", "E-3");

        service.handleExceptionResponse(exchange, ex);

        assertNotNull(calledMethod.get());
        assertEquals("handleGeneralException", calledMethod.get().getName());

        assertEquals(4, calledArgs.get().length);
        assertSame(ex, calledArgs.get()[0]);
        assertSame(exchange, calledArgs.get()[1]);
        assertEquals(ErrorCode.match(ex), calledArgs.get()[2]);

        @SuppressWarnings("unchecked")
        Map<String, String> extras = (Map<String, String>) calledArgs.get()[3];
        assertEquals("S-3", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_SESSION_ID));
        assertEquals("E-3", extras.get(CamelConstants.ChainProperties.EXCEPTION_EXTRA_FAILED_ELEMENT));
    }

    private static Exchange mockExchange(String sessionId, String failedElementId) {
        Exchange ex = MockExchanges.basic();
        when(ex.getProperty(eq(CamelConstants.Properties.SESSION_ID), eq(String.class))).thenReturn(sessionId);
        when(ex.getProperty(eq(CamelConstants.ChainProperties.FAILED_ELEMENT_ID), eq(String.class))).thenReturn(failedElementId);
        return ex;
    }
}

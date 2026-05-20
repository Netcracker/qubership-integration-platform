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
import org.apache.camel.Message;
import org.apache.camel.StreamCache;
import org.apache.camel.TypeConverter;
import org.apache.camel.WrappedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MessageHelperTest {

    @Mock
    Exchange exchange;
    @Mock
    Message message;
    @Mock
    TypeConverter converter;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.withMessageAndConverter();
        message = MockExchanges.getMessage(exchange);
        converter = MockExchanges.getTypeConverter(exchange);
    }

    @Test
    void shouldReturnNullWhenBodyIsNull() {
        when(message.getBody()).thenReturn(null);

        assertNull(MessageHelper.extractBody(exchange));
        verifyNoInteractions(converter);
    }

    @Test
    void shouldReturnFileBasedStringWhenBodyIsFile() {
        File file = new File("readme.txt");
        when(message.getBody()).thenReturn(file);

        String res = MessageHelper.extractBody(exchange);
        assertEquals("[Body is file based: " + file + "]", res);
        verifyNoInteractions(converter);
    }

    @Test
    void shouldReturnFileBasedStringWhenBodyIsWrappedFile() {
        WrappedFile<?> wf = mock(WrappedFile.class);
        when(wf.toString()).thenReturn("WF(myfile)");
        when(message.getBody()).thenReturn(wf);

        String res = MessageHelper.extractBody(exchange);
        assertEquals("[Body is file based: WF(myfile)]", res);
        verifyNoInteractions(converter);
    }

    @Test
    void shouldConvertStreamCacheWithTypeConverterAndResetTwice() {
        StreamCache sc = mock(StreamCache.class);
        when(message.getBody()).thenReturn(sc);
        assert converter != null;
        when(converter.tryConvertTo(eq(String.class), eq(exchange), same(sc))).thenReturn("converted");

        String res = MessageHelper.extractBody(exchange);
        assertEquals("converted", res);
        verify(sc, times(2)).reset();
    }

    @Test
    void shouldReadAndConvertInputStreamBodyWhenInputStreamProvided() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream is = new ByteArrayInputStream(bytes);
        when(message.getBody()).thenReturn(is);

        assert converter != null;
        when(converter.tryConvertTo(eq(String.class), eq(exchange), any(byte[].class)))
                .thenAnswer(inv -> new String((byte[]) inv.getArgument(2), StandardCharsets.UTF_8));

        String res = MessageHelper.extractBody(exchange);
        assertEquals("hello", res);
    }

    @Test
    void shouldFallbackToToStringWhenConverterFailsOrReturnsNull() {
        Object body = new Object() {
            @Override
            public String toString() {
                return "obj";
            }
        };
        when(message.getBody()).thenReturn(body);
        assert converter != null;
        when(converter.tryConvertTo(eq(String.class), eq(exchange), same(body)))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(null);

        String res = MessageHelper.extractBody(exchange);
        assertEquals("obj", res);
    }

    @Test
    void shouldReturnNullWhenConverterReturnsNullAndToStringThrows() {
        Object body = new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("nope");
            }
        };
        when(message.getBody()).thenReturn(body);
        assert converter != null;
        when(converter.tryConvertTo(eq(String.class), eq(exchange), same(body))).thenReturn(null);

        String res = MessageHelper.extractBody(exchange);
        assertNull(res);
    }
}

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
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MessageHelperTest {

    @Test
    void shouldReturnNullWhenBodyIsNull() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        when(msg.getBody()).thenReturn(null);

        assertNull(MessageHelper.extractBody(ex));
        verifyNoInteractions(tc);
    }

    @Test
    void shouldReturnFileBasedStringWhenBodyIsFile() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        File file = new File("readme.txt");
        when(msg.getBody()).thenReturn(file);

        String res = MessageHelper.extractBody(ex);
        assertEquals("[Body is file based: " + file + "]", res);
        verifyNoInteractions(tc);
    }

    @Test
    void shouldReturnFileBasedStringWhenBodyIsWrappedFile() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        WrappedFile<?> wf = mock(WrappedFile.class);
        when(wf.toString()).thenReturn("WF(myfile)");
        when(msg.getBody()).thenReturn(wf);

        String res = MessageHelper.extractBody(ex);
        assertEquals("[Body is file based: WF(myfile)]", res);
        verifyNoInteractions(tc);
    }

    @Test
    void shouldConvertStreamCacheWithTypeConverterAndResetTwice() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        StreamCache sc = mock(StreamCache.class);
        when(msg.getBody()).thenReturn(sc);
        assert tc != null;
        when(tc.tryConvertTo(eq(String.class), eq(ex), same(sc))).thenReturn("converted");

        String res = MessageHelper.extractBody(ex);
        assertEquals("converted", res);
        verify(sc, times(2)).reset();
    }

    @Test
    void shouldReadAndConvertInputStreamBodyWhenInputStreamProvided() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream is = new ByteArrayInputStream(bytes);
        when(msg.getBody()).thenReturn(is);

        assert tc != null;
        when(tc.tryConvertTo(eq(String.class), eq(ex), any(byte[].class)))
                .thenAnswer(inv -> new String((byte[]) inv.getArgument(2), StandardCharsets.UTF_8));

        String res = MessageHelper.extractBody(ex);
        assertEquals("hello", res);
    }

    @Test
    void shouldFallbackToToStringWhenConverterFailsOrReturnsNull() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        Object body = new Object() {
            @Override
            public String toString() {
                return "obj";
            }
        };
        when(msg.getBody()).thenReturn(body);
        assert tc != null;
        when(tc.tryConvertTo(eq(String.class), eq(ex), same(body)))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(null);

        String res = MessageHelper.extractBody(ex);
        assertEquals("obj", res);
    }

    @Test
    void shouldReturnNullWhenConverterReturnsNullAndToStringThrows() {
        Exchange ex = MockExchanges.withMessageAndConverter();
        Message msg = MockExchanges.getMessage(ex);
        TypeConverter tc = MockExchanges.getTypeConverter(ex);

        Object body = new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("nope");
            }
        };
        when(msg.getBody()).thenReturn(body);
        assert tc != null;
        when(tc.tryConvertTo(eq(String.class), eq(ex), same(body))).thenReturn(null);

        String res = MessageHelper.extractBody(ex);
        assertNull(res);
    }
}

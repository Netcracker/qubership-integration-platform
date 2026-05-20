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

package org.qubership.integration.platform.engine.service.debugger.util.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CircularReferencesFinderSerializerTest {

    static class TestableFinderSerializer extends CircularReferencesFinderSerializer {
        private final JsonSerializer<Object> delegate;

        TestableFinderSerializer(Consumer<Object> consumer, JsonSerializer<Object> delegate) {
            super(consumer);
            this.delegate = delegate;
        }

        @Override
        protected JsonSerializer<Object> getSerializer(Object value) {
            return delegate;
        }
    }

    @Test
    void shouldDelegateWhenNotInScope() throws IOException {
        Object value = new Object();
        @SuppressWarnings("unchecked") Consumer<Object> consumer = mock(Consumer.class);
        @SuppressWarnings("unchecked") JsonSerializer<Object> delegate = mock(JsonSerializer.class);

        CircularReferencesFinderSerializer ser = new TestableFinderSerializer(consumer, delegate);
        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider prov = mock(SerializerProvider.class);

        ser.serialize(value, gen, prov);

        verify(delegate, times(1)).serialize(eq(value), eq(gen), eq(prov));
        verify(consumer, never()).accept(any());
    }

    @Test
    void shouldNotifyConsumerWhenReenteredForSameInstance() throws IOException {
        Object value = new Object();
        @SuppressWarnings("unchecked") Consumer<Object> consumer = mock(Consumer.class);
        @SuppressWarnings("unchecked") JsonSerializer<Object> delegate = mock(JsonSerializer.class);

        CircularReferencesFinderSerializer ser = new TestableFinderSerializer(consumer, delegate);
        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider prov = mock(SerializerProvider.class);

        AtomicBoolean recursed = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (!recursed.getAndSet(true)) {
                ser.serialize(value, gen, prov);
            }
            return null;
        }).when(delegate).serialize(eq(value), eq(gen), eq(prov));

        ser.serialize(value, gen, prov);

        verify(consumer, times(1)).accept(eq(value));
        verify(delegate, times(1)).serialize(eq(value), eq(gen), eq(prov));
    }

    @Test
    void shouldNotKeepObjectInScopeBetweenSeparateCalls() throws IOException {
        Object value = new Object();
        @SuppressWarnings("unchecked") Consumer<Object> consumer = mock(Consumer.class);
        @SuppressWarnings("unchecked") JsonSerializer<Object> delegate = mock(JsonSerializer.class);

        CircularReferencesFinderSerializer ser = new TestableFinderSerializer(consumer, delegate);
        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider prov = mock(SerializerProvider.class);

        ser.serialize(value, gen, prov);
        ser.serialize(value, gen, prov);

        verify(delegate, times(2)).serialize(eq(value), eq(gen), eq(prov));
        verify(consumer, never()).accept(any());
    }
}

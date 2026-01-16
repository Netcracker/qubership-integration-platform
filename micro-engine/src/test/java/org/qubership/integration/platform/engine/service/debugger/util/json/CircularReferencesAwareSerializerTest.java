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
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CircularReferencesAwareSerializerTest {

    static class TestableCircularSerializer extends CircularReferencesAwareSerializer {
        private final JsonSerializer<Object> delegate;

        TestableCircularSerializer(Set<Object> self, JsonSerializer<Object> delegate) {
            super(self);
            this.delegate = delegate;
        }

        @Override
        protected JsonSerializer<Object> getSerializer(Object value) {
            return delegate;
        }
    }

    @Test
    void shouldDelegateToUnderlyingSerializerWhenObjectIsNotInSelfReferencedSet() throws IOException {
        Object value = new Object();
        JsonSerializer<Object> delegate = mock(JsonSerializer.class);
        CircularReferencesAwareSerializer ser =
                new TestableCircularSerializer(new HashSet<>(), delegate);

        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider prov = mock(SerializerProvider.class);

        ser.serialize(value, gen, prov);

        verify(delegate, times(1)).serialize(eq(value), eq(gen), eq(prov));
        verify(gen, never()).writeRaw(anyString());
        verify(gen, never()).writeString(anyString());
    }

    @Test
    void shouldGenerateJsonIdAndReferenceWhenSelfReferencedObjectFirstSeen() throws IOException {
        Object value = new Object();
        Set<Object> self = new HashSet<>();
        self.add(value);

        JsonSerializer<Object> delegate = mock(JsonSerializer.class);
        CircularReferencesAwareSerializer ser =
                new TestableCircularSerializer(self, delegate);

        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider prov = mock(SerializerProvider.class);

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);

        ser.serialize(value, gen, prov);

        verify(gen, times(2)).writeRaw(rawCaptor.capture());
        String firstRaw = rawCaptor.getAllValues().get(0);
        String lastRaw = rawCaptor.getAllValues().get(1);

        verify(delegate, times(1)).serialize(eq(value), eq(gen), eq(prov));
        verify(gen, never()).writeString(anyString());

        String prefix = "{\"@json-id\":\"";
        String mid = "\",\"reference\":";
        int start = firstRaw.indexOf(prefix);
        int midIdx = firstRaw.indexOf(mid);
        String id = firstRaw.substring(start + prefix.length(), midIdx);
        assertEquals("}", lastRaw);

        String[] parts = id.split("-");
        assertEquals(5, parts.length);
        assertEquals(8, parts[0].length());
        assertEquals(4, parts[1].length());
        assertEquals(4, parts[2].length());
        assertEquals(4, parts[3].length());
        assertEquals(12, parts[4].length());
    }

    @Test
    void shouldWriteOnlyIdWhenSelfReferencedObjectSerializedAgain() throws IOException {
        Object value = new Object();
        Set<Object> self = new HashSet<>();
        self.add(value);

        JsonSerializer<Object> delegate = mock(JsonSerializer.class);
        CircularReferencesAwareSerializer ser =
                new TestableCircularSerializer(self, delegate);

        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider prov = mock(SerializerProvider.class);

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);

        ser.serialize(value, gen, prov);

        verify(gen, times(2)).writeRaw(rawCaptor.capture());
        String firstRaw = rawCaptor.getAllValues().get(0);
        String prefix = "{\"@json-id\":\"";
        String mid = "\",\"reference\":";
        String id = firstRaw.substring(firstRaw.indexOf(prefix) + prefix.length(), firstRaw.indexOf(mid));

        reset(gen, delegate);

        ser.serialize(value, gen, prov);

        verify(gen, times(1)).writeString(idCaptor.capture());
        verify(delegate, never()).serialize(any(), any(), any());
        assertEquals(id, idCaptor.getValue());
        verify(gen, never()).writeRaw(anyString());
    }
}

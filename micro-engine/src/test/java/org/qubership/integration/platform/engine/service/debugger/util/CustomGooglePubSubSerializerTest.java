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

import org.apache.camel.converter.stream.InputStreamCache;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomGooglePubSubSerializerTest {

    private final CustomGooglePubSubSerializer serializer = new CustomGooglePubSubSerializer();

    @Test
    void shouldReturnRawBytesWhenPayloadIsInputStreamCache() throws Exception {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        InputStreamCache cache = new InputStreamCache(data);
        byte[] result = serializer.serialize(cache);
        assertArrayEquals(data, result);
    }

    @Test
    void shouldSerializeObjectWhenPayloadIsSerializable() throws Exception {
        String payload = "hello";
        byte[] bytes = serializer.serialize(payload);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object restored = ois.readObject();
            assertEquals(payload, restored);
        }
    }

    @Test
    void shouldThrowIOExceptionWhenPayloadIsNotSerializable() {
        Object notSerializable = new Object();
        assertThrows(IOException.class, () -> serializer.serialize(notSerializable));
    }
}

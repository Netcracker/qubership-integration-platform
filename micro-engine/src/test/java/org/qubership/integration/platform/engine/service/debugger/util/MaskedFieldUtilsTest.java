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
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MaskedFieldUtilsTest {

    private Exchange mockExchange() {
        Exchange exchange = mock(Exchange.class);
        Map<String, Object> properties = new HashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return properties.get(key);
        }).when(exchange).getProperty(any(String.class));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            properties.put(key, value);
            return null;
        }).when(exchange).setProperty(any(String.class), any());
        return exchange;
    }

    @Test
    void shouldReturnEmptySetWhenPropertyIsNull() {
        Exchange ex = mockExchange();
        Set<String> result = MaskedFieldUtils.getMaskedFields(ex);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptySetWhenPropertyIsNotSetType() {
        Exchange ex = mockExchange();
        ex.setProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY, "oops");
        Set<String> result = MaskedFieldUtils.getMaskedFields(ex);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnCopyWhenPropertyIsSet() {
        Exchange ex = mockExchange();
        Set<String> original = new HashSet<>(Set.of("a", "b"));
        MaskedFieldUtils.setMaskedFields(ex, original);
        Set<String> copy = MaskedFieldUtils.getMaskedFields(ex);
        assertEquals(original, copy);
        assertNotSame(original, copy);
        copy.add("c");
        assertFalse(original.contains("c"));
    }

    @Test
    void shouldSetMaskedFieldsWhenNonNull() {
        Exchange ex = mockExchange();
        Set<String> fields = new HashSet<>(Set.of("x", "y"));
        MaskedFieldUtils.setMaskedFields(ex, fields);

        @SuppressWarnings("unchecked")
        Set<String> stored = (Set<String>) ex.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY);
        assertNotNull(stored);
        assertEquals(fields, stored);
        assertNotSame(fields, stored);
        fields.add("z");
        assertFalse(stored.contains("z"));
    }

    @Test
    void shouldNotSetMaskedFieldsWhenNull() {
        Exchange ex = mockExchange();
        MaskedFieldUtils.setMaskedFields(ex, null);
        assertNull(ex.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY));
    }

    @Test
    void shouldAddMaskedFieldsWhenExistingAbsent() {
        Exchange ex = mockExchange();
        MaskedFieldUtils.addMaskedFields(ex, new HashSet<>(Set.of("a")));
        @SuppressWarnings("unchecked")
        Set<String> stored = (Set<String>) ex.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY);
        assertEquals(Set.of("a"), stored);
    }

    @Test
    void shouldMergeMaskedFieldsWhenExistingPresent() {
        Exchange ex = mockExchange();
        MaskedFieldUtils.setMaskedFields(ex, new HashSet<>(Set.of("a")));
        MaskedFieldUtils.addMaskedFields(ex, new HashSet<>(Set.of("b", "c")));

        @SuppressWarnings("unchecked")
        Set<String> stored = (Set<String>) ex.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY);
        assertEquals(Set.of("a", "b", "c"), stored);
    }

    @Test
    void shouldNotChangeWhenNewFieldsNullOrEmpty() {
        Exchange ex = mockExchange();
        Set<String> initial = new HashSet<>(Set.of("a"));
        MaskedFieldUtils.setMaskedFields(ex, initial);

        Object beforeRef = ex.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY);
        MaskedFieldUtils.addMaskedFields(ex, null);
        MaskedFieldUtils.addMaskedFields(ex, Collections.emptySet());
        Object afterRef = ex.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY);

        assertSame(beforeRef, afterRef);
        @SuppressWarnings("unchecked")
        Set<String> stored = (Set<String>) afterRef;
        assertEquals(Set.of("a"), stored);
    }
}

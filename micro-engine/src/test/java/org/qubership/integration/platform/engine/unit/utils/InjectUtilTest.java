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

package org.qubership.integration.platform.engine.unit.utils;

import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class InjectUtilTest {

    @Test
    void shouldThrowAmbiguousResolutionExceptionWhenInstanceIsAmbiguous() {
        @SuppressWarnings("unchecked")
        Instance<String> inst = mock(Instance.class);
        when(inst.isAmbiguous()).thenReturn(true);

        assertThrows(AmbiguousResolutionException.class, () -> InjectUtil.injectOptional(inst));
        verify(inst, never()).stream();
    }

    @Test
    void shouldReturnEmptyOptionalWhenInstanceStreamIsEmpty() {
        @SuppressWarnings("unchecked")
        Instance<String> inst = mock(Instance.class);
        when(inst.isAmbiguous()).thenReturn(false);
        when(inst.stream()).thenReturn(Stream.empty());

        Optional<String> result = InjectUtil.injectOptional(inst);

        assertTrue(result.isEmpty());
        verify(inst).stream();
    }

    @Test
    void shouldReturnFirstElementWhenInstanceStreamHasValues() {
        @SuppressWarnings("unchecked")
        Instance<String> inst = mock(Instance.class);
        when(inst.isAmbiguous()).thenReturn(false);
        when(inst.stream()).thenReturn(Stream.of("first", "second"));

        Optional<String> result = InjectUtil.injectOptional(inst);

        assertTrue(result.isPresent());
        assertEquals("first", result.get());
        verify(inst).stream();
    }

    @Test
    void shouldAllowNullInstanceToThrowNpeAsContract() {
        assertThrows(NullPointerException.class, () -> InjectUtil.injectOptional(null));
    }
}

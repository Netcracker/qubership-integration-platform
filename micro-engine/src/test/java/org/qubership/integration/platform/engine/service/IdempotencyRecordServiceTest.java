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

package org.qubership.integration.platform.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.persistence.shared.repository.IdempotencyRecordRepository;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class IdempotencyRecordServiceTest {

    @Mock
    ObjectMapper objectMapper;

    @Mock
    IdempotencyRecordRepository idempotencyRecordRepository;

    @InjectMocks
    IdempotencyRecordService service;

    @Test
    void shouldReturnTrueWhenInsertIfNotExistsOrUpdateIfExpiredReturnsPositive() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":\"RECEIVED\"}");
        when(idempotencyRecordRepository.insertIfNotExistsOrUpdateIfExpired(anyString(), any(), anyInt()))
                .thenReturn(1);

        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);

        boolean result = service.insertIfNotExists("k1", 123);

        assertTrue(result);

        verify(idempotencyRecordRepository).insertIfNotExistsOrUpdateIfExpired(eq("k1"), dataCaptor.capture(), eq(123));
        assertEquals("{\"status\":\"RECEIVED\"}", dataCaptor.getValue());
    }

    @Test
    void shouldReturnFalseWhenInsertIfNotExistsOrUpdateIfExpiredReturnsZero() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":\"RECEIVED\"}");
        when(idempotencyRecordRepository.insertIfNotExistsOrUpdateIfExpired(anyString(), any(), anyInt()))
                .thenReturn(0);

        boolean result = service.insertIfNotExists("k1", 123);

        assertFalse(result);
        verify(idempotencyRecordRepository).insertIfNotExistsOrUpdateIfExpired(eq("k1"), any(), eq(123));
    }

    @Test
    void shouldPassNullDataWhenJsonSerializationFails() throws Exception {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {
                });

        when(idempotencyRecordRepository.insertIfNotExistsOrUpdateIfExpired(anyString(), any(), anyInt()))
                .thenReturn(1);

        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);

        boolean result = service.insertIfNotExists("k1", 123);

        assertTrue(result);
        verify(idempotencyRecordRepository).insertIfNotExistsOrUpdateIfExpired(eq("k1"), dataCaptor.capture(), eq(123));
        assertNull(dataCaptor.getValue());
    }

    @Test
    void shouldReturnRepositoryValueWhenExists() {
        when(idempotencyRecordRepository.existsByKeyAndNotExpired("k1")).thenReturn(true);

        boolean result = service.exists("k1");

        assertTrue(result);
        verify(idempotencyRecordRepository).existsByKeyAndNotExpired("k1");
        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldReturnTrueWhenDeleteByKeyAndNotExpiredReturnsPositive() {
        when(idempotencyRecordRepository.deleteByKeyAndNotExpired("k1")).thenReturn(1);

        boolean result = service.delete("k1");

        assertTrue(result);
        verify(idempotencyRecordRepository).deleteByKeyAndNotExpired("k1");
        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldReturnFalseWhenDeleteByKeyAndNotExpiredReturnsZero() {
        when(idempotencyRecordRepository.deleteByKeyAndNotExpired("k1")).thenReturn(0);

        boolean result = service.delete("k1");

        assertFalse(result);
        verify(idempotencyRecordRepository).deleteByKeyAndNotExpired("k1");
        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldDeleteExpiredByCallingRepository() {
        service.deleteExpired();

        verify(idempotencyRecordRepository).deleteExpired();
        verifyNoInteractions(objectMapper);
    }
}

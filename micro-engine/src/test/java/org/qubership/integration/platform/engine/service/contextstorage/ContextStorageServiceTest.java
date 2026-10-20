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

package org.qubership.integration.platform.engine.service.contextstorage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.qubership.integration.platform.engine.errorhandling.ContextStorageException;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;
import org.qubership.integration.platform.engine.persistence.shared.repository.ContextStorageRepository;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextStorageServiceTest {

    private ContextStorageService service;

    @Mock
    private ContextStorageRepository repository;

    ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        repository = mock(ContextStorageRepository.class);
        mapper = ObjectMappers.getObjectMapper();
        service = new ContextStorageService(repository, mapper);
    }

    @Test
    void storeValueShouldCreateNewRecordWhenNoOldRecordAndNoExistingStorage() {
        when(repository.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.empty());

        long ttl = 60;

        service.storeValue("k1", "v1", "svc", "ctx", ttl);

        ArgumentCaptor<ContextSystemRecords> captor = ArgumentCaptor.forClass(ContextSystemRecords.class);
        verify(repository).persist(captor.capture());

        ContextSystemRecords saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals("svc", saved.getContextServiceId());
        assertEquals("ctx", saved.getContextId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertNotNull(saved.getExpiresAt());

        Instant now = Instant.now();
        Instant exp = saved.getExpiresAt().toInstant();
        assertTrue(exp.isAfter(now.minusSeconds(5)));
        assertTrue(exp.isBefore(now.plusSeconds(ttl + 10)));

        JsonNode valueNode = saved.getValue();
        assertNotNull(valueNode);
        assertTrue(valueNode.has("context"));
        assertEquals("v1", valueNode.get("context").get("k1").asText());

        verify(repository).findByContextServiceIdAndContextId("svc", "ctx");
    }

    @Test
    void storeValueShouldReuseIdAndCreatedAtAndUpdateExistingKey() throws Exception {
        ObjectMapper om = new ObjectMapper();

        JsonNode existingValue = om.readTree("""
            { "context": { "a": "1", "b": "2" } }
        """);

        Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(3600));
        ContextSystemRecords old = ContextSystemRecords.builder()
                .id("fixed-id")
                .createdAt(createdAt)
                .value(existingValue)
                .build();

        when(repository.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.of(old));

        service.storeValue("b", "NEW", "svc", "ctx", 120);

        verify(repository).findByContextServiceIdAndContextId("svc", "ctx");
        verify(repository, never()).persist(any(ContextSystemRecords.class));
        assertEquals("fixed-id", old.getId());
        assertEquals(createdAt, old.getCreatedAt());
        assertNotNull(old.getUpdatedAt());
        assertTrue(old.getExpiresAt().toInstant().isAfter(Instant.now().plusSeconds(60)));

        JsonNode ctx = old.getValue().get("context");
        assertEquals("1", ctx.get("a").asText());
        assertEquals("NEW", ctx.get("b").asText());
    }

    @Test
    void getValueShouldReturnEmptyWhenRecordMissing() {
        when(repository.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.empty());

        Map<String, String> result = service.getValue("svc", "ctx", List.of("a", "b"));
        assertTrue(result.isEmpty());
    }

    @Test
    void getValueShouldReturnEmptyWhenExpired() throws Exception {
        JsonNode value = mapper.readTree("""
            { "context": { "a": "1" } }
        """);

        ContextSystemRecords rec = mock(ContextSystemRecords.class);
        when(rec.getExpiresAt()).thenReturn(Timestamp.from(Instant.now().minusSeconds(5)));
        when(rec.getValue()).thenReturn(value);

        when(repository.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.of(rec));

        Map<String, String> result = service.getValue("svc", "ctx", List.of("a"));
        assertTrue(result.isEmpty());
    }

    @Test
    void getValueShouldReturnOnlyRequestedExistingKeysWhenNotExpired() throws Exception {
        JsonNode value = mapper.readTree("""
            { "context": { "a": "1", "b": "2" } }
        """);

        ContextSystemRecords rec = mock(ContextSystemRecords.class);
        when(rec.getExpiresAt()).thenReturn(Timestamp.from(Instant.now().plusSeconds(300)));
        when(rec.getValue()).thenReturn(value);

        when(repository.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.of(rec));

        Map<String, String> result = service.getValue("svc", "ctx", List.of("a", "c"));
        assertEquals(Map.of("a", "1"), result);
    }

    @Test
    void getValueShouldThrowRuntimeExceptionWhenJsonProcessingExceptionOccurs() throws Exception {
        ContextStorageRepository repo = mock(ContextStorageRepository.class);

        ObjectMapper mapper = mock(ObjectMapper.class);

        ContextStorageService service = new ContextStorageService(repo, mapper);

        ContextSystemRecords rec = mock(ContextSystemRecords.class);
        when(rec.getExpiresAt()).thenReturn(Timestamp.from(Instant.now().plusSeconds(60)));

        when(rec.getValue()).thenReturn(mock(JsonNode.class));

        when(repo.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.of(rec));

        when(mapper.readTree(anyString()))
                .thenThrow(new JsonProcessingException("bad json") {});

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getValue("svc", "ctx", List.of("a")));

        assertNotNull(ex.getCause());
        assertInstanceOf(JsonProcessingException.class, ex.getCause());
    }

    @Test
    void deleteValueShouldCallRepository() {
        service.deleteValue("svc", "ctx");
        verify(repository).deleteRecordByContextServiceIdAndContextId("svc", "ctx");
    }

    @Test
    void deleteValueShouldWrapExceptionIntoContextStorageException() {
        doThrow(new RuntimeException("boom"))
                .when(repository).deleteRecordByContextServiceIdAndContextId("svc", "ctx");

        ContextStorageException ex = assertThrows(ContextStorageException.class,
                () -> service.deleteValue("svc", "ctx"));

        assertTrue(ex.getMessage().contains("Error occurred while deleting value"));
        assertNotNull(ex.getCause());
    }

    @Test
    void deleteOldRecordsShouldDeleteWhenOldRecordsExist() {
        ContextSystemRecords r1 = mock(ContextSystemRecords.class);
        ContextSystemRecords r2 = mock(ContextSystemRecords.class);
        when(r1.getId()).thenReturn("1");
        when(r2.getId()).thenReturn("2");

        when(repository.findAllByExpiresAtBefore(any(Timestamp.class)))
                .thenReturn(List.of(r1, r2));

        service.deleteOldRecords();

        verify(repository).deleteByIds(List.of("1", "2"));
    }

    @Test
    void deleteOldRecordsShouldNotDeleteWhenNoOldRecords() {
        when(repository.findAllByExpiresAtBefore(any(Timestamp.class)))
                .thenReturn(List.of());

        service.deleteOldRecords();

        verify(repository, never()).deleteByIds(anyList());
    }

    @Test
    void deleteOldRecordsShouldWrapExceptionIntoContextStorageException() {
        when(repository.findAllByExpiresAtBefore(any(Timestamp.class)))
                .thenThrow(new RuntimeException("boom"));

        ContextStorageException ex = assertThrows(ContextStorageException.class,
                () -> service.deleteOldRecords());

        assertTrue(ex.getMessage().contains("Error occurred while deleting old records"));
        assertNotNull(ex.getCause());
    }

    @Test
    void storeValueShouldCreateNewContextWhenExistingRecordValueIsNull() {
        ContextStorageRepository repo = mock(ContextStorageRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ContextStorageService service = new ContextStorageService(repo, mapper);

        Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(100));
        ContextSystemRecords old = ContextSystemRecords.builder()
                .id("fixed-id")
                .createdAt(createdAt)
                .build();

        when(repo.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.of(old));

        service.storeValue("k1", "v1", "svc", "ctx", 60);

        verify(repo, never()).persist(any(ContextSystemRecords.class));
        assertEquals("fixed-id", old.getId());
        assertEquals(createdAt, old.getCreatedAt());

        JsonNode ctx = old.getValue().get("context");
        assertEquals("v1", ctx.get("k1").asText());
    }

    @Test
    void storeValueShouldThrowContextStorageExceptionWhenStoredContextCannotBeMerged() {
        ContextStorageRepository repo = mock(ContextStorageRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ContextStorageService service = new ContextStorageService(repo, mapper);

        ContextSystemRecords old = ContextSystemRecords.builder()
                .id("fixed-id")
                .value(mapper.createObjectNode())
                .build();
        when(repo.findByContextServiceIdAndContextId("svc", "ctx"))
                .thenReturn(Optional.of(old));

        ContextStorageException ex = assertThrows(ContextStorageException.class,
                () -> service.storeValue("k", "v", "svc", "ctx", 60));

        assertTrue(ex.getMessage().contains("Error occurred while processing contextKey"));
        assertInstanceOf(NullPointerException.class, ex.getCause());
    }
}

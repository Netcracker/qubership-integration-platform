package org.qubership.integration.platform.engine.service.contextstorage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.ContextStorageException;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;
import org.qubership.integration.platform.engine.persistence.shared.repository.ContextStorageRespository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextStorageServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ContextStorageRespository repository;
    private ContextStorageService service;

    @BeforeEach
    void setUp() {
        service = new ContextStorageService(repository, MAPPER);
    }

    @Test
    void storeValueLooksUpOnceAndSavesNewRecordWhenNoneIsStored() {
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.empty());

        service.storeValue("k1", "v1", "svc", "ctx", 60);

        verify(repository).findByContextServiceIdAndContextId("svc", "ctx");
        ContextSystemRecords saved = captureSaved();
        assertNotNull(saved.getId());
        assertEquals("svc", saved.getContextServiceId());
        assertEquals("ctx", saved.getContextId());
        assertEquals("v1", saved.getValue().get("context").get("k1").asText());
    }

    @Test
    void storeValueLooksUpOnceAndMergesKeyIntoStoredRecord() throws Exception {
        Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(3600));
        ContextSystemRecords old = ContextSystemRecords.builder()
                .id("fixed-id")
                .createdAt(createdAt)
                .value(MAPPER.readTree("{\"context\": {\"a\": \"1\", \"b\": \"2\"}}"))
                .build();
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.of(old));

        service.storeValue("b", "NEW", "svc", "ctx", 120);

        verify(repository).findByContextServiceIdAndContextId("svc", "ctx");
        ContextSystemRecords saved = captureSaved();
        assertEquals("fixed-id", saved.getId());
        assertEquals(createdAt, saved.getCreatedAt());
        JsonNode context = saved.getValue().get("context");
        assertEquals("1", context.get("a").asText());
        assertEquals("NEW", context.get("b").asText());
    }

    @Test
    void storeValueCreatesContextWhenStoredRecordHasNoValue() {
        ContextSystemRecords old = ContextSystemRecords.builder().id("fixed-id").build();
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.of(old));

        service.storeValue("k1", "v1", "svc", "ctx", 60);

        ContextSystemRecords saved = captureSaved();
        assertEquals("fixed-id", saved.getId());
        assertEquals("v1", saved.getValue().get("context").get("k1").asText());
    }

    @Test
    void storeValueThrowsContextStorageExceptionWhenStoredContextCannotBeMerged() {
        ContextSystemRecords old = ContextSystemRecords.builder()
                .contextServiceId("svc")
                .contextId("ctx")
                .value(MAPPER.createObjectNode())
                .build();
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.of(old));

        ContextStorageException e = assertThrows(ContextStorageException.class,
                () -> service.storeValue("k", "v", "svc", "ctx", 60));

        assertEquals("Error occurred while processing contextKey: k contextServiceId: svc contextId: ctx", e.getMessage());
        assertInstanceOf(NullPointerException.class, e.getCause());
    }

    private ContextSystemRecords captureSaved() {
        ArgumentCaptor<ContextSystemRecords> captor = ArgumentCaptor.forClass(ContextSystemRecords.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}

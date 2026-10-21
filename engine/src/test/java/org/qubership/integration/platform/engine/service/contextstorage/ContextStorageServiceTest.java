package org.qubership.integration.platform.engine.service.contextstorage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.ContextStorageException;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;
import org.qubership.integration.platform.engine.persistence.shared.repository.ContextStorageRespository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextStorageServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ContextStorageRespository repository;
    @Mock
    private EntityManager entityManager;
    private ContextStorageService service;

    @BeforeEach
    void setUp() {
        service = new ContextStorageService(repository, entityManager, MAPPER);
    }

    @Test
    void storeValueLooksUpOnceAndPersistsNewRecordWhenNoneIsStored() {
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.empty());

        service.storeValue("k1", "v1", "svc", "ctx", 60);

        verify(repository).findByContextServiceIdAndContextId("svc", "ctx");
        verifyNoMoreInteractions(repository);
        ArgumentCaptor<ContextSystemRecords> captor = ArgumentCaptor.forClass(ContextSystemRecords.class);
        verify(entityManager).persist(captor.capture());
        ContextSystemRecords persisted = captor.getValue();
        assertNotNull(persisted.getId());
        assertEquals("svc", persisted.getContextServiceId());
        assertEquals("ctx", persisted.getContextId());
        assertNotNull(persisted.getCreatedAt());
        assertEquals("v1", persisted.getValue().get("context").get("k1").asText());
    }

    @Test
    void storeValueLooksUpOnceAndUpdatesStoredRecordInPlace() throws Exception {
        ContextSystemRecords old = ContextSystemRecords.builder()
                .value(MAPPER.readTree("{\"context\": {\"a\": \"1\", \"b\": \"2\"}}"))
                .build();
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.of(old));

        service.storeValue("b", "NEW", "svc", "ctx", 120);

        verify(repository).findByContextServiceIdAndContextId("svc", "ctx");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(entityManager);
        assertNotNull(old.getUpdatedAt());
        assertTrue(old.getExpiresAt().toInstant().isAfter(Instant.now().plusSeconds(60)));
        JsonNode context = old.getValue().get("context");
        assertEquals("1", context.get("a").asText());
        assertEquals("NEW", context.get("b").asText());
    }

    @Test
    void storeValueCreatesContextWhenStoredRecordHasNoValue() {
        ContextSystemRecords old = ContextSystemRecords.builder().build();
        when(repository.findByContextServiceIdAndContextId("svc", "ctx")).thenReturn(Optional.of(old));

        service.storeValue("k1", "v1", "svc", "ctx", 60);

        verifyNoInteractions(entityManager);
        assertEquals("v1", old.getValue().get("context").get("k1").asText());
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
}

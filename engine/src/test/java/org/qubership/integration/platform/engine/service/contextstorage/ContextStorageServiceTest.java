package org.qubership.integration.platform.engine.service.contextstorage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.persistence.shared.repository.ContextStorageRespository;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ContextStorageServiceTest {

    // NON_NULL as in MapperConfiguration, so a null map value would be dropped.
    private static final ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Mock
    private ContextStorageRespository repository;
    private ContextStorageService service;

    @BeforeEach
    void setUp() {
        service = new ContextStorageService(repository, MAPPER);
    }

    @Test
    void storeValueIssuesOneMergeWithTheKeyUnderContext() {
        Instant before = Instant.now();

        service.storeValue("k1", "v1", "svc", "ctx", 60);

        ArgumentCaptor<Timestamp> now = ArgumentCaptor.forClass(Timestamp.class);
        ArgumentCaptor<Timestamp> expiresAt = ArgumentCaptor.forClass(Timestamp.class);
        verify(repository).mergeValue(anyString(), eq("{\"context\":{\"k1\":\"v1\"}}"), eq("svc"), eq("ctx"), now.capture(), expiresAt.capture());
        verifyNoMoreInteractions(repository);
        assertFalse(now.getValue().toInstant().isBefore(before));
        assertEquals(now.getValue().toInstant().plusSeconds(60), expiresAt.getValue().toInstant());
    }

    @Test
    void storeValueSendsANullValueAsAJsonNull() {
        service.storeValue("k1", null, "svc", "ctx", 60);

        verify(repository).mergeValue(anyString(), eq("{\"context\":{\"k1\":null}}"), eq("svc"), eq("ctx"), any(), any());
    }
}

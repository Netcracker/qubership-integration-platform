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

package org.qubership.integration.platform.engine.persistence.shared.repository;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextStorageRepositoryTest {

    @Test
    void findByContextServiceIdAndContextIdShouldDelegateToPanacheFind() {
        ContextStorageRepository repo = Mockito.spy(new ContextStorageRepository());

        @SuppressWarnings("unchecked")
        PanacheQuery<ContextSystemRecords> query = mock(PanacheQuery.class);

        ContextSystemRecords rec = mock(ContextSystemRecords.class);
        when(query.firstResultOptional()).thenReturn(Optional.of(rec));

        doReturn(query).when(repo).find("contextServiceId = ?1 and contextId = ?2", "svc", "ctx");

        Optional<ContextSystemRecords> result = repo.findByContextServiceIdAndContextId("svc", "ctx");
        assertTrue(result.isPresent());

        verify(repo).find("contextServiceId = ?1 and contextId = ?2", "svc", "ctx");
        verify(query).firstResultOptional();
    }

    @Test
    void deleteRecordByContextServiceIdAndContextIdShouldDelegateToPanacheDelete() {
        ContextStorageRepository repo = Mockito.spy(new ContextStorageRepository());

        doReturn(1L).when(repo).delete("contextServiceId = ?1 and contextId = ?2", "svc", "ctx");

        repo.deleteRecordByContextServiceIdAndContextId("svc", "ctx");

        verify(repo).delete("contextServiceId = ?1 and contextId = ?2", "svc", "ctx");
    }

    @Test
    void findAllByExpiresAtBeforeShouldDelegateToPanacheList() {
        ContextStorageRepository repo = Mockito.spy(new ContextStorageRepository());

        Timestamp ts = Timestamp.from(Instant.now());
        List<ContextSystemRecords> expected = List.of(mock(ContextSystemRecords.class));

        doReturn(expected).when(repo).list("expiresAt < ?1", ts);

        List<ContextSystemRecords> result = repo.findAllByExpiresAtBefore(ts);
        assertEquals(1, result.size());

        verify(repo).list("expiresAt < ?1", ts);
    }

    @Test
    void deleteByIdsShouldDelegateToPanacheDeleteInQuery() {
        ContextStorageRepository repo = Mockito.spy(new ContextStorageRepository());

        List<String> ids = List.of("1", "2", "3");
        doReturn(3L).when(repo).delete("id in (?1)", ids);

        repo.deleteByIds(ids);

        verify(repo).delete("id in (?1)", ids);
    }
}

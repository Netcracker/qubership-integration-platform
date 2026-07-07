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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHeaders;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.persistence.shared.repository.CheckpointRepository;
import org.qubership.integration.platform.engine.persistence.shared.repository.SessionInfoRepository;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CheckpointSessionServiceTest {

    private CheckpointSessionService checkpointSessionService;

    @Mock
    SessionInfoRepository sessionRepo;
    @Mock
    CheckpointRepository checkpointRepo;
    @RestClient
    @Inject
    @Mock
    CheckpointRestService rest;
    @Mock
    ObjectMapper mapper;
    @Mock
    IdempotencyRecordService idempotencyRecordService;

    @BeforeEach
    void setUp() {
        checkpointSessionService = new CheckpointSessionService(sessionRepo, checkpointRepo, rest, mapper, idempotencyRecordService);
        checkpointSessionService.idempotencyKeyTTL = "PT1H";
    }

    @Test
    void shouldThrowEntityNotFoundWhenRetryFromLastCheckpointAndNoCheckpoint() {
        when(checkpointRepo.findAllBySessionChainIdAndSessionId(anyString(), anyString(), any(Page.class), any(Sort.class)))
                .thenReturn(List.of());

        assertThrows(EntityNotFoundException.class, () ->
                checkpointSessionService.retryFromLastCheckpoint("chain", "session", "{\"x\":1}", () -> null, true)
        );

        verifyNoInteractions(rest);
    }

    @Test
    void shouldCallRetryCheckpointWhenRetryFromLastCheckpointAndCheckpointExists() {
        Uni<Buffer> uni = mockUni();
        when(rest.retryCheckpoint(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(uni);

        Checkpoint checkpoint = checkpoint();

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(eq("chain"), eq("session"), any(Page.class), any(Sort.class)))
                .thenReturn(List.of(checkpoint));

        Supplier<Pair<String, String>> auth = () -> Pair.of("Authorization", "Bearer 123");

        checkpointSessionService.retryFromLastCheckpoint("chain", "session", "{\"k\":\"v\"}", auth, true);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Map<String, String>> headersCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Optional<String>> bodyCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Optional.class);

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), headersCaptor.capture(), bodyCaptor.capture());

        Map<String, String> hdrs = headersCaptor.getValue();
        assertEquals("Bearer 123", hdrs.get("Authorization"));
        assertEquals("true", hdrs.get(Headers.TRACE_ME));
        assertEquals(MediaType.APPLICATION_JSON, hdrs.get(HttpHeaders.CONTENT_TYPE));

        assertEquals(Optional.of("{\"k\":\"v\"}"), bodyCaptor.getValue());

        verify(uni).subscribe();
    }

    @Test
    void shouldSendEmptyOptionalBodyWhenBodyBlank() {
        Uni<Buffer> uni = mockUni();
        when(rest.retryCheckpoint(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(uni);

        Checkpoint checkpoint = checkpoint();

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(eq("chain"), eq("session"), any(Page.class), any(Sort.class)))
                .thenReturn(List.of(checkpoint));

        checkpointSessionService.retryFromLastCheckpoint("chain", "session", "", () -> null, false);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Map<String, String>> headersCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Optional<String>> bodyCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Optional.class);

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), headersCaptor.capture(), bodyCaptor.capture());

        Map<String, String> hdrs = headersCaptor.getValue();
        assertEquals("false", hdrs.get(Headers.TRACE_ME));
        assertEquals(MediaType.APPLICATION_JSON, hdrs.get(HttpHeaders.CONTENT_TYPE));
        assertEquals(Optional.empty(), bodyCaptor.getValue());

        verify(uni).subscribe();
    }

    @Test
    void shouldThrowEntityNotFoundWhenRetryFromCheckpointAndCheckpointMissing() {
        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId(anyString(), anyString(), anyString()))
                .thenReturn(null);

        assertThrows(EntityNotFoundException.class, () ->
                checkpointSessionService.retryFromCheckpoint("chain", "session", "el-1", "{}", () -> null, true)
        );

        verifyNoInteractions(rest);
    }

    @Test
    void shouldCallRetryCheckpointWhenRetryFromCheckpointAndCheckpointExists() {
        Uni<Buffer> uni = mockUni();
        when(rest.retryCheckpoint(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(uni);

        Checkpoint checkpoint = checkpoint();

        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId("session", "chain", "el-1"))
                .thenReturn(checkpoint);

        checkpointSessionService.retryFromCheckpoint("chain", "session", "el-1", "{\"a\":1}", () -> Pair.of("X", "Y"), true);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Map<String, String>> headersCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Optional<String>> bodyCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Optional.class);

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), headersCaptor.capture(), bodyCaptor.capture());

        Map<String, String> hdrs = headersCaptor.getValue();
        assertEquals("Y", hdrs.get("X"));
        assertEquals("true", hdrs.get(Headers.TRACE_ME));
        assertEquals(MediaType.APPLICATION_JSON, hdrs.get(HttpHeaders.CONTENT_TYPE));
        assertEquals(Optional.of("{\"a\":1}"), bodyCaptor.getValue());

        verify(uni).subscribe();
    }

    @Test
    void shouldHandleRetryCheckpointSuccessCallback() {
        when(rest.retryCheckpoint(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Uni.createFrom().item(Buffer.buffer()));

        Checkpoint checkpoint = checkpoint();

        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId("session", "chain", "el-1"))
                .thenReturn(checkpoint);

        assertDoesNotThrow(() ->
                checkpointSessionService.retryFromCheckpoint("chain", "session", "el-1", "{}", () -> null, false));

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), anyMap(), any());
    }

    @Test
    void shouldHandleRetryCheckpointFailureCallback() {
        when(rest.retryCheckpoint(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Uni.createFrom().failure(new RuntimeException("boom")));

        Checkpoint checkpoint = checkpoint();

        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId("session", "chain", "el-1"))
                .thenReturn(checkpoint);

        assertDoesNotThrow(() ->
                checkpointSessionService.retryFromCheckpoint("chain", "session", "el-1", "{}", () -> null, false));

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), anyMap(), any());
    }

    @Test
    void shouldReturnNullWhenFindLastCheckpointAndRepositoryReturnsNullOrEmpty() {
        when(checkpointRepo.findAllBySessionChainIdAndSessionId(anyString(), anyString(), any(Page.class), any(Sort.class)))
                .thenReturn(null);

        assertNull(checkpointSessionService.findLastCheckpoint("c", "s"));

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(anyString(), anyString(), any(Page.class), any(Sort.class)))
                .thenReturn(List.of());

        assertNull(checkpointSessionService.findLastCheckpoint("c", "s"));
    }

    @Test
    void shouldReturnFirstWhenFindLastCheckpointAndRepositoryReturnsList() {
        Checkpoint c1 = mock(Checkpoint.class);
        Checkpoint c2 = mock(Checkpoint.class);

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(eq("c"), eq("s"), any(Page.class), any(Sort.class)))
                .thenReturn(List.of(c1, c2));

        assertSame(c1, checkpointSessionService.findLastCheckpoint("c", "s"));
    }

    @Test
    void shouldDelegateFindAllFailedChainSessionsInfo() {
        List<SessionInfo> list = List.of(mock(SessionInfo.class));
        when(sessionRepo.findAllByChainIdAndExecutionStatus("c", ExecutionStatus.COMPLETED_WITH_ERRORS)).thenReturn(list);

        assertSame(list, checkpointSessionService.findAllFailedChainSessionsInfo("c"));
    }

    @Test
    void shouldDelegateFindCheckpoint() {
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId("session", "chain", "element"))
                .thenReturn(checkpoint);

        Checkpoint result = checkpointSessionService.findCheckpoint("session", "chain", "element");

        assertSame(checkpoint, result);
    }

    @Test
    void shouldDelegateFindSessions() {
        List<String> sessionIds = List.of("session-1", "session-2");
        List<SessionInfo> sessions = List.of(mock(SessionInfo.class), mock(SessionInfo.class));
        when(sessionRepo.findAllById(sessionIds)).thenReturn(sessions);

        List<SessionInfo> result = checkpointSessionService.findSessions(sessionIds);

        assertSame(sessions, result);
    }

    @Test
    void shouldDelegateFindOriginalSessionInfo() {
        Optional<SessionInfo> session = Optional.of(mock(SessionInfo.class));
        when(sessionRepo.findOriginalSessionInfo("session")).thenReturn(session);

        Optional<SessionInfo> result = checkpointSessionService.findOriginalSessionInfo("session");

        assertSame(session, result);
    }

    @Test
    void shouldPersistAndReturnSameSessionWhenSaveSession() {
        SessionInfo session = mock(SessionInfo.class);
        SessionInfo mergedSession = mock(SessionInfo.class);

        EntityManager entityManager = mock(EntityManager.class);
        when(sessionRepo.getEntityManager()).thenReturn(entityManager);
        when(entityManager.merge(session)).thenReturn(mergedSession);

        SessionInfo out = checkpointSessionService.saveSession(session);

        assertSame(mergedSession, out);
        verify(sessionRepo).persistAndFlush(mergedSession);
    }

    @Test
    void shouldThrowEntityNotFoundWhenSaveAndAssignCheckpointAndSessionMissing() {
        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.empty());

        Checkpoint checkpoint = mock(Checkpoint.class);

        assertThrows(EntityNotFoundException.class, () -> checkpointSessionService.saveAndAssignCheckpoint(checkpoint, "s"));
    }

    @Test
    void shouldAssignCheckpointToSessionWhenSaveAndAssignCheckpointAndSessionExists() {
        SessionInfo session = mock(SessionInfo.class);
        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.of(session));

        Checkpoint checkpoint = mock(Checkpoint.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        List props = List.of("a");

        when(checkpoint.getProperties()).thenReturn(props);

        checkpointSessionService.saveAndAssignCheckpoint(checkpoint, "s");

        verify(checkpoint).assignProperties(props);
        verify(session).assignCheckpoint(checkpoint);
    }

    @Test
    void shouldUpdateParentWhenBothSessionsExist() {
        SessionInfo session = mock(SessionInfo.class);
        SessionInfo parent = mock(SessionInfo.class);

        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.of(session));
        when(sessionRepo.findByIdOptional("p")).thenReturn(Optional.of(parent));

        checkpointSessionService.updateSessionParent("s", "p");

        verify(session).setParentSession(parent);
    }

    @Test
    void shouldThrowEntityNotFoundWhenUpdateParentAndAnySessionMissing() {
        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> checkpointSessionService.updateSessionParent("s", "p"));
    }

    @Test
    void shouldDeleteRootSessionByIdWhenRemoveAllRelatedCheckpointsAndIsRoot() {
        checkpointSessionService.removeAllRelatedCheckpoints("s", true);

        verify(sessionRepo).deleteById("s");
        verify(sessionRepo, never()).deleteAllRelatedSessionsAndCheckpoints(anyString());
    }

    @Test
    void shouldDeleteRelatedWhenRemoveAllRelatedCheckpointsAndNotRoot() {
        checkpointSessionService.removeAllRelatedCheckpoints("s", false);

        verify(sessionRepo).deleteAllRelatedSessionsAndCheckpoints("s");
        verify(sessionRepo, never()).deleteById(anyString());
    }

    @Test
    void shouldDelegateDeleteOldRecordsByInterval() {
        checkpointSessionService.deleteOldRecordsByInterval("P30D");

        verify(sessionRepo).deleteOldRecordsByInterval("P30D");
    }

    @Test
    void shouldReturnUniqueIdempotencyKey() {
        String result = checkpointSessionService.getUniqueKeyForIdempotency("retry-1", "session-1");

        assertEquals("session-retries:session-1:retry-1", result);
    }

    @Test
    void shouldInsertIdempotencyKeyAndReturnFalseWhenKeyDoesNotExist() {
        String uniqueKey = "session-retries:session-1:retry-1";
        when(idempotencyRecordService.exists(uniqueKey)).thenReturn(false);

        boolean result = checkpointSessionService.verifyAndInsertIfNotExistIdempotencyKey("retry-1", "session-1");

        assertFalse(result);
        verify(idempotencyRecordService).insertIfNotExists(uniqueKey, "PT1H");
    }

    @Test
    void shouldReturnTrueAndSkipInsertWhenIdempotencyKeyExists() {
        String uniqueKey = "session-retries:session-1:retry-1";
        when(idempotencyRecordService.exists(uniqueKey)).thenReturn(true);

        boolean result = checkpointSessionService.verifyAndInsertIfNotExistIdempotencyKey("retry-1", "session-1");

        assertTrue(result);
        verify(idempotencyRecordService, never()).insertIfNotExists(anyString(), anyString());
    }

    private Uni mockUni() {
        return mock(Uni.class, RETURNS_DEEP_STUBS);
    }

    private Checkpoint checkpoint() {
        SessionInfo session = mock(SessionInfo.class);
        when(session.getChainId()).thenReturn("chain");
        when(session.getId()).thenReturn("session");

        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getSession()).thenReturn(session);
        when(checkpoint.getCheckpointElementId()).thenReturn("el-1");

        return checkpoint;
    }
}

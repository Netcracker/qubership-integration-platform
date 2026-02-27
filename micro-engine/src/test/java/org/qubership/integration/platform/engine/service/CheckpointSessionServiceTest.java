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
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CheckpointSessionServiceTest {

    private CheckpointSessionService service(
            SessionInfoRepository sessionInfoRepository,
            CheckpointRepository checkpointRepository,
            CheckpointRestService checkpointRestService,
            ObjectMapper mapper
    ) {
        return new CheckpointSessionService(sessionInfoRepository, checkpointRepository, checkpointRestService, mapper);
    }

    @Test
    void shouldThrowEntityNotFoundWhenRetryFromLastCheckpointAndNoCheckpoint() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);
        CheckpointRestService rest = mock(CheckpointRestService.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, rest, mock(ObjectMapper.class));

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(anyString(), anyString(), any(Page.class), any(Sort.class)))
                .thenReturn(List.of());

        assertThrows(EntityNotFoundException.class, () ->
                svc.retryFromLastCheckpoint("chain", "session", "{\"x\":1}", () -> null, true)
        );

        verifyNoInteractions(rest);
    }

    @Test
    void shouldCallRetryCheckpointWhenRetryFromLastCheckpointAndCheckpointExists() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);
        CheckpointRestService rest = mock(CheckpointRestService.class);

        Uni<Buffer> uni = mock(Uni.class, RETURNS_DEEP_STUBS);
        when(rest.retryCheckpoint(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn(uni);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, rest, mock(ObjectMapper.class));

        SessionInfo session = mock(SessionInfo.class);
        when(session.getChainId()).thenReturn("chain");
        when(session.getId()).thenReturn("session");

        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getSession()).thenReturn(session);
        when(checkpoint.getCheckpointElementId()).thenReturn("el-1");

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(eq("chain"), eq("session"), any(Page.class), any(Sort.class)))
                .thenReturn(List.of(checkpoint));

        Supplier<Pair<String, String>> auth = () -> Pair.of("Authorization", "Bearer 123");

        svc.retryFromLastCheckpoint("chain", "session", "{\"k\":\"v\"}", auth, true);

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Optional<String>> bodyCaptor = ArgumentCaptor.forClass(Optional.class);

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
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);
        CheckpointRestService rest = mock(CheckpointRestService.class);

        @SuppressWarnings("unchecked")
        Uni<Buffer> uni = mock(Uni.class, RETURNS_DEEP_STUBS);

        when(rest.retryCheckpoint(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(uni);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, rest, mock(ObjectMapper.class));

        SessionInfo session = mock(SessionInfo.class);
        when(session.getChainId()).thenReturn("chain");
        when(session.getId()).thenReturn("session");

        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getSession()).thenReturn(session);
        when(checkpoint.getCheckpointElementId()).thenReturn("el-1");

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(eq("chain"), eq("session"), any(Page.class), any(Sort.class)))
                .thenReturn(List.of(checkpoint));

        svc.retryFromLastCheckpoint("chain", "session", "", () -> null, false);

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Optional<String>> bodyCaptor = ArgumentCaptor.forClass(Optional.class);

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), headersCaptor.capture(), bodyCaptor.capture());

        Map<String, String> hdrs = headersCaptor.getValue();
        assertEquals("false", hdrs.get(org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers.TRACE_ME));
        assertEquals(MediaType.APPLICATION_JSON, hdrs.get(HttpHeaders.CONTENT_TYPE));
        assertEquals(Optional.empty(), bodyCaptor.getValue());

        verify(uni).subscribe();
    }

    @Test
    void shouldThrowEntityNotFoundWhenRetryFromCheckpointAndCheckpointMissing() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);
        CheckpointRestService rest = mock(CheckpointRestService.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, rest, mock(ObjectMapper.class));

        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId(anyString(), anyString(), anyString()))
                .thenReturn(null);

        assertThrows(EntityNotFoundException.class, () ->
                svc.retryFromCheckpoint("chain", "session", "el-1", "{}", () -> null, true)
        );

        verifyNoInteractions(rest);
    }

    @Test
    void shouldCallRetryCheckpointWhenRetryFromCheckpointAndCheckpointExists() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);
        CheckpointRestService rest = mock(CheckpointRestService.class);

        Uni<Buffer> uni = mock(Uni.class, RETURNS_DEEP_STUBS);
        when(rest.retryCheckpoint(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn(uni);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, rest, mock(ObjectMapper.class));

        SessionInfo session = mock(SessionInfo.class);
        when(session.getChainId()).thenReturn("chain");
        when(session.getId()).thenReturn("session");

        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getSession()).thenReturn(session);
        when(checkpoint.getCheckpointElementId()).thenReturn("el-1");

        when(checkpointRepo.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId("session", "chain", "el-1"))
                .thenReturn(checkpoint);

        svc.retryFromCheckpoint("chain", "session", "el-1", "{\"a\":1}", () -> Pair.of("X", "Y"), true);

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Optional<String>> bodyCaptor = ArgumentCaptor.forClass(Optional.class);

        verify(rest).retryCheckpoint(eq("chain"), eq("session"), eq("el-1"), headersCaptor.capture(), bodyCaptor.capture());

        Map<String, String> hdrs = headersCaptor.getValue();
        assertEquals("Y", hdrs.get("X"));
        assertEquals("true", hdrs.get(Headers.TRACE_ME));
        assertEquals(MediaType.APPLICATION_JSON, hdrs.get(HttpHeaders.CONTENT_TYPE));
        assertEquals(Optional.of("{\"a\":1}"), bodyCaptor.getValue());

        verify(uni).subscribe();
    }

    @Test
    void shouldReturnNullWhenFindLastCheckpointAndRepositoryReturnsNullOrEmpty() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(anyString(), anyString(), any(Page.class), any(Sort.class)))
                .thenReturn(null);

        assertNull(svc.findLastCheckpoint("c", "s"));

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(anyString(), anyString(), any(Page.class), any(Sort.class)))
                .thenReturn(List.of());

        assertNull(svc.findLastCheckpoint("c", "s"));
    }

    @Test
    void shouldReturnFirstWhenFindLastCheckpointAndRepositoryReturnsList() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        Checkpoint c1 = mock(Checkpoint.class);
        Checkpoint c2 = mock(Checkpoint.class);

        when(checkpointRepo.findAllBySessionChainIdAndSessionId(eq("c"), eq("s"), any(Page.class), any(Sort.class)))
                .thenReturn(List.of(c1, c2));

        assertSame(c1, svc.findLastCheckpoint("c", "s"));
    }

    @Test
    void shouldDelegateFindAllFailedChainSessionsInfo() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        List<SessionInfo> list = List.of(mock(SessionInfo.class));
        when(sessionRepo.findAllByChainIdAndExecutionStatus("c", ExecutionStatus.COMPLETED_WITH_ERRORS)).thenReturn(list);

        assertSame(list, svc.findAllFailedChainSessionsInfo("c"));
    }

    @Test
    void shouldPersistAndReturnSameSessionWhenSaveSession() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        SessionInfo session = mock(SessionInfo.class);
        SessionInfo out = svc.saveSession(session);

        assertSame(session, out);
        verify(sessionRepo).persistAndFlush(session);
    }

    @Test
    void shouldThrowEntityNotFoundWhenSaveAndAssignCheckpointAndSessionMissing() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.empty());

        Checkpoint checkpoint = mock(Checkpoint.class);

        assertThrows(EntityNotFoundException.class, () -> svc.saveAndAssignCheckpoint(checkpoint, "s"));
    }

    @Test
    void shouldAssignCheckpointToSessionWhenSaveAndAssignCheckpointAndSessionExists() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        SessionInfo session = mock(SessionInfo.class);
        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.of(session));

        Checkpoint checkpoint = mock(Checkpoint.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        List props = List.of("a");

        when(checkpoint.getProperties()).thenReturn(props);

        svc.saveAndAssignCheckpoint(checkpoint, "s");

        verify(checkpoint).assignProperties(props);
        verify(session).assignCheckpoint(checkpoint);
    }

    @Test
    void shouldUpdateParentWhenBothSessionsExist() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        SessionInfo session = mock(SessionInfo.class);
        SessionInfo parent = mock(SessionInfo.class);

        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.of(session));
        when(sessionRepo.findByIdOptional("p")).thenReturn(Optional.of(parent));

        svc.updateSessionParent("s", "p");

        verify(session).setParentSession(parent);
    }

    @Test
    void shouldThrowEntityNotFoundWhenUpdateParentAndAnySessionMissing() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        when(sessionRepo.findByIdOptional("s")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> svc.updateSessionParent("s", "p"));
    }

    @Test
    void shouldDeleteRootSessionByIdWhenRemoveAllRelatedCheckpointsAndIsRoot() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        svc.removeAllRelatedCheckpoints("s", true);

        verify(sessionRepo).deleteById("s");
        verify(sessionRepo, never()).deleteAllRelatedSessionsAndCheckpoints(anyString());
    }

    @Test
    void shouldDeleteRelatedWhenRemoveAllRelatedCheckpointsAndNotRoot() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        svc.removeAllRelatedCheckpoints("s", false);

        verify(sessionRepo).deleteAllRelatedSessionsAndCheckpoints("s");
        verify(sessionRepo, never()).deleteById(anyString());
    }

    @Test
    void shouldDelegateDeleteOldRecordsByInterval() {
        SessionInfoRepository sessionRepo = mock(SessionInfoRepository.class);
        CheckpointRepository checkpointRepo = mock(CheckpointRepository.class);

        CheckpointSessionService svc = service(sessionRepo, checkpointRepo, mock(CheckpointRestService.class), mock(ObjectMapper.class));

        svc.deleteOldRecordsByInterval("P30D");

        verify(sessionRepo).deleteOldRecordsByInterval("P30D");
    }
}

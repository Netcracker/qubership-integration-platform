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
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHeaders;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.engine.model.checkpoint.CheckpointPayloadOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.persistence.shared.repository.CheckpointRepository;
import org.qubership.integration.platform.engine.persistence.shared.repository.SessionInfoRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@ApplicationScoped
public class CheckpointSessionService {

    private final SessionInfoRepository sessionInfoRepository;
    private final CheckpointRepository checkpointRepository;
    private final CheckpointRestService checkpointRestService;
    private final ObjectMapper jsonMapper;

    @Inject
    public CheckpointSessionService(
            SessionInfoRepository sessionInfoRepository,
            CheckpointRepository checkpointRepository,
            @RestClient CheckpointRestService checkpointRestService,
            @Identifier("jsonMapper") ObjectMapper jsonMapper
    ) {
        this.sessionInfoRepository = sessionInfoRepository;
        this.checkpointRepository = checkpointRepository;
        this.checkpointRestService = checkpointRestService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional("checkpointTransactionManager")
    public void retryFromLastCheckpoint(String chainId, String sessionId, String body,
        Supplier<Pair<String, String>> authHeaderProvider, boolean traceMe) {

        Checkpoint lastCheckpoint = findLastCheckpoint(chainId, sessionId);

        if (lastCheckpoint == null) {
            throw new EntityNotFoundException(
                "Can't find checkpoint for session with id: " + sessionId);
        }
        retryFromCheckpointAsync(lastCheckpoint, body, authHeaderProvider, traceMe);
    }

    @Transactional("checkpointTransactionManager")
    public void retryFromCheckpoint(
        String chainId,
        String sessionId,
        String checkpointElementId,
        String body,
        Supplier<Pair<String, String>> authHeaderProvider,
        boolean traceMe) {
        Checkpoint checkpoint = checkpointRepository
            .findFirstBySessionIdAndSessionChainIdAndCheckpointElementId(sessionId, chainId, checkpointElementId);
        if (checkpoint == null) {
            throw new EntityNotFoundException(
                "Can't find checkpoint " + checkpointElementId + " for session with id: "
                    + sessionId);
        }
        retryFromCheckpointAsync(checkpoint, body, authHeaderProvider, traceMe);
    }

    private void retryFromCheckpointAsync(
            Checkpoint checkpoint,
            String body,
            Supplier<Pair<String, String>> authHeaderProvider,
            boolean traceMe
    ) {
        Map<String, String> headers = new HashMap<>();
        Pair<String, String> authPair = authHeaderProvider.get();
        if (authPair != null) {
            headers.put(authPair.getKey(), authPair.getValue());
        }
        headers.put(Headers.TRACE_ME, String.valueOf(traceMe));
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        checkpointRestService.retryCheckpoint(
                checkpoint.getSession().getChainId(),
                checkpoint.getSession().getId(),
                checkpoint.getCheckpointElementId(),
                headers,
                StringUtils.isNotEmpty(body) ? Optional.of(body) : Optional.empty()
        ).subscribe().with(
                rsp -> {},
                failure -> {
                    log.error("Failed to trigger checkpoint: {}", failure.getMessage());
                }
        );
    }

    private void validateRetryBody(String body) {
        try {
            jsonMapper.readValue(body, CheckpointPayloadOptions.class);
        } catch (Exception e) {
            log.error("Failed to parse checkpoint options from retry request", e);
            throw new RuntimeException("Failed to parse checkpoint options from retry request", e);
        }
    }

    @Transactional("checkpointTransactionManager")
    public Checkpoint findLastCheckpoint(String chainId, String sessionId) {
        List<Checkpoint> checkpoints = checkpointRepository
                .findAllBySessionChainIdAndSessionId(
                        chainId,
                        sessionId,
                        Page.of(0, 1),
                        Sort.by("timestamp", Sort.Direction.Descending));
        return (checkpoints == null || checkpoints.isEmpty()) ? null : checkpoints.getFirst();
    }

    @Transactional("checkpointTransactionManager")
    public List<SessionInfo> findAllFailedChainSessionsInfo(String chainId) {
        List<SessionInfo> allByChainIdAndExecutionStatus = sessionInfoRepository.findAllByChainIdAndExecutionStatus(
            chainId, ExecutionStatus.COMPLETED_WITH_ERRORS);
        return allByChainIdAndExecutionStatus;
    }

    @Transactional("checkpointTransactionManager")
    public SessionInfo saveSession(SessionInfo sessionInfo) {
        sessionInfoRepository.persistAndFlush(sessionInfo); // Calling flush to generate entity ID, if needed
        return sessionInfo;
    }

    @Transactional("checkpointTransactionManager")
    public void saveAndAssignCheckpoint(Checkpoint checkpoint, String sessionId) {
        SessionInfo sessionInfo = findSession(sessionId);
        if (sessionInfo == null) {
            throw new EntityNotFoundException("Failed to assign checkpoint to session with id " + sessionId);
        }
        checkpoint.assignProperties(checkpoint.getProperties());
        sessionInfo.assignCheckpoint(checkpoint);
    }

    @Transactional("checkpointTransactionManager")
    public Checkpoint findCheckpoint(String sessionId, String chainId, String checkpointElementId) {
        return checkpointRepository.findFirstBySessionIdAndSessionChainIdAndCheckpointElementId(
            sessionId, chainId, checkpointElementId);
    }

    @Transactional("checkpointTransactionManager")
    public SessionInfo findSession(String sessionId) {
        return sessionInfoRepository.findByIdOptional(sessionId).orElse(null);
    }

    @Transactional("checkpointTransactionManager")
    public List<SessionInfo> findSessions(List<String> sessionIds) {
        return sessionInfoRepository.findAllById(sessionIds);
    }

    @Transactional("checkpointTransactionManager")
    public void updateSessionParent(String sessionId, String parentId) {
        SessionInfo sessionInfo = sessionInfoRepository.findByIdOptional(sessionId)
                .orElseThrow(EntityNotFoundException::new);
        SessionInfo parentSessionInfo = sessionInfoRepository.findByIdOptional(parentId)
                .orElseThrow(EntityNotFoundException::new);
        sessionInfo.setParentSession(parentSessionInfo);
    }

    @Transactional("checkpointTransactionManager")
    public Optional<SessionInfo> findOriginalSessionInfo(String sessionId) {
        return sessionInfoRepository.findOriginalSessionInfo(sessionId);
    }

    /**
     * Remove all related checkpoint recursively
     */
    @Transactional("checkpointTransactionManager")
    public void removeAllRelatedCheckpoints(String sessionId, boolean isRootSession) {
        if (isRootSession) {
            // do not execute complex query if possible
            sessionInfoRepository.deleteById(sessionId);
        } else {
            sessionInfoRepository.deleteAllRelatedSessionsAndCheckpoints(sessionId);
        }
    }

    @Transactional("checkpointTransactionManager")
    public void deleteOldRecordsByInterval(String checkpointsInterval) {
        sessionInfoRepository.deleteOldRecordsByInterval(checkpointsInterval);
    }
}

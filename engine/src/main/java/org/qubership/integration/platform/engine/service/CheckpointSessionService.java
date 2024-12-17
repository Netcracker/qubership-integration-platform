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

import static org.qubership.integration.platform.engine.util.CheckpointUtils.CHECKPOINT_RETRY_PATH_TEMPLATE;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.model.checkpoint.CheckpointPayloadOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.persistence.shared.repository.CheckpointRepository;
import org.qubership.integration.platform.engine.persistence.shared.repository.SessionInfoRepository;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityNotFoundException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;

@Slf4j
@Component
public class CheckpointSessionService {

    private final SessionInfoRepository sessionInfoRepository;
    private final CheckpointRepository checkpointRepository;
    private final WebClient localhostWebclient;
    private final ObjectMapper jsonMapper;

    @Autowired
    public CheckpointSessionService(SessionInfoRepository sessionInfoRepository,
        CheckpointRepository checkpointRepository, WebClient localhostWebclient,
        @Qualifier("jsonMapper") ObjectMapper jsonMapper) {
        this.sessionInfoRepository = sessionInfoRepository;
        this.checkpointRepository = checkpointRepository;
        this.localhostWebclient = localhostWebclient;
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

    private void retryFromCheckpointAsync(Checkpoint checkpoint,
                                          String body,
                                          Supplier<Pair<String, String>> authHeaderProvider,
                                          boolean traceMe) {
        RequestBodySpec request = localhostWebclient
            .post()
            .uri(CamelServletConfiguration.CAMEL_ROUTES_PREFIX + CHECKPOINT_RETRY_PATH_TEMPLATE,
                checkpoint.getSession().getChainId(),
                checkpoint.getSession().getId(),
                checkpoint.getCheckpointElementId());

        Pair<String, String> authPair = authHeaderProvider.get();
        if (authPair != null) {
            request.header(authPair.getKey(), authPair.getValue());
        }
        request.header(Headers.TRACE_ME, String.valueOf(traceMe));
        request.contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.isNotEmpty(body)) {
            validateRetryBody(body);
            request.bodyValue(body);
        }

        request.retrieve().toBodilessEntity().subscribe();
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
            .findAllBySessionChainIdAndSessionId(chainId, sessionId,
                PageRequest.of(0, 1, Sort.by("timestamp").descending()));
        return (checkpoints == null || checkpoints.isEmpty()) ? null : checkpoints.get(0);
    }

    @Transactional("checkpointTransactionManager")
    public List<SessionInfo> findAllFailedChainSessionsInfo(String chainId) {
        List<SessionInfo> allByChainIdAndExecutionStatus = sessionInfoRepository.findAllByChainIdAndExecutionStatus(
            chainId, ExecutionStatus.COMPLETED_WITH_ERRORS);
        return allByChainIdAndExecutionStatus;
    }

    @Transactional("checkpointTransactionManager")
    public SessionInfo saveSession(SessionInfo sessionInfo) {
        return sessionInfoRepository.save(sessionInfo);
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
        return sessionInfoRepository.findById(sessionId).orElse(null);
    }

    @Transactional("checkpointTransactionManager")
    public List<SessionInfo> findSessions(List<String> sessionIds) {
        return sessionInfoRepository.findAllById(sessionIds);
    }

    @Transactional("checkpointTransactionManager")
    public void updateSessionParent(String sessionId, String parentId) {
        SessionInfo sessionInfo = sessionInfoRepository.findById(sessionId)
                .orElseThrow(EntityNotFoundException::new);
        SessionInfo parentSessionInfo = sessionInfoRepository.findById(parentId)
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

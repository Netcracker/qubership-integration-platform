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

package org.qubership.integration.platform.variables.management.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.logging.constant.ContextHeaders;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.user.User;
import org.qubership.integration.platform.variables.management.persistence.configs.repository.actionlog.ActionLogRepository;
import org.qubership.integration.platform.variables.management.rest.exception.InvalidEnumConstantException;
import org.qubership.integration.platform.variables.management.rest.v1.dto.actionlog.ActionLogSearchCriteria;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Service
public class ActionsLogService {
    private final ActionLogRepository actionLogRepository;
    private final AuditorAware<User> auditor;

    // <tenant, queue>
    private final BlockingQueue<ActionLog> queue = new LinkedBlockingQueue<>();

    @Autowired
    public ActionsLogService(ActionLogRepository actionLogRepository, AuditorAware<User> auditor) {
        this.actionLogRepository = actionLogRepository;
        this.auditor = auditor;

        new ActionWriterThread(actionLogRepository, queue).start();
    }

    public Pair<Long, List<ActionLog>> findBySearchRequest(ActionLogSearchCriteria request) {
        try {
            List<ActionLog> actionLogsByFilter = actionLogRepository.findActionLogsByFilter(
                    request.getOffsetTime(),
                    request.getRangeTime(),
                    request.getFilters());

            long recordsAfterRange = actionLogRepository.getRecordsCountAfterTime(
                    new Timestamp(request.getOffsetTime().getTime() - request.getRangeTime()),
                    request.getFilters());

            return Pair.of(recordsAfterRange, actionLogsByFilter);
        } catch (InvalidEnumConstantException e) {
            log.debug(e.getMessage());
            return Pair.of(0L, Collections.emptyList());
        }
    }

    public List<ActionLog> findAllByActionTimeBetween(Timestamp actionTimeFrom, Timestamp actionTimeTo) {
        return actionLogRepository.findAllByActionTimeBetween(actionTimeFrom, actionTimeTo);
    }

    public boolean logAction(ActionLog action) {
        injectCurrentUser(action);
        injectRequestId(action);
        try {
            if (!queue.offer(action)) {
                log.error("Queue of actions is full, element is not added, {}", maskSecretName(action));
                return false;
            }
            consoleLogAction(maskSecretName(action));
            return true;
        } catch (Exception e) {
            log.error("Failed to save action log to database: {}", maskSecretName(action), e);
        }
        return false;
    }

    private void injectRequestId(ActionLog action) {
        action.setRequestId(MDC.get(ContextHeaders.REQUEST_ID));
    }

    private void injectCurrentUser(ActionLog action) {
        auditor.getCurrentAuditor().ifPresent(action::setUser);
    }

    @Transactional
    public void deleteAllOldRecordsByInterval(String olderThan) {
        actionLogRepository.deleteAllOldRecordsByInterval(olderThan);
    }

    private void consoleLogAction(ActionLog action) {
        MDC.put("logType", "audit");
        StringBuilder sb = new StringBuilder();
        sb.append("Action ");
        sb.append(Optional.ofNullable(action.getOperation()).map(LogOperation::name).orElse("-"));
        sb.append(" for ");
        sb.append(Optional.ofNullable(action.getEntityType()).map(EntityType::name).orElse("-"));
        Optional.ofNullable(action.getEntityName()).ifPresent(name -> sb.append(" with name ").append(name));
        Optional.ofNullable(action.getEntityId()).ifPresent(id -> sb.append(" with id: ").append(id));
        Optional.ofNullable(action.getParentType()).ifPresent(type -> sb.append(" under parent entity ").append(type.name()));
        Optional.ofNullable(action.getParentName()).ifPresent(name -> sb.append(" with name ").append(name));
        Optional.ofNullable(action.getParentId()).ifPresent(id -> sb.append(" with id: ").append(id));
        Optional.ofNullable(action.getUser().getUsername()).ifPresent(name -> sb.append(" performed by user ").append(name));
        Optional.ofNullable(action.getUser().getId()).ifPresent(id -> sb.append(" with id: ").append(id));
        log.debug(sb.toString());
        MDC.remove("logType");
    }

    private static class ActionWriterThread extends Thread {
        private final ActionLogRepository actionLogRepository;
        private final BlockingQueue<ActionLog> queue;
        private final List<ActionLog> actionsToSave = new ArrayList<>();


        public ActionWriterThread(ActionLogRepository actionLogRepository, BlockingQueue<ActionLog> queue) {
            this.actionLogRepository = actionLogRepository;
            this.queue = queue;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    actionsToSave.add(queue.take());
                } catch (InterruptedException ignored) {
                    continue;
                }

                queue.drainTo(actionsToSave);
                trySaveAllActions(actionsToSave);
                actionsToSave.clear();
            }
        }

        private void trySaveAllActions(Collection<ActionLog> actions) {
            try {
                actionLogRepository.saveAll(actions);
            } catch (Exception e) {
                log.error("Failed to save actions in database", e);
            }
        }
    }

    private ActionLog maskSecretName(ActionLog actionLog) {
        ActionLog.ActionLogBuilder builder = actionLog.toBuilder();
        if (actionLog.getEntityType() == EntityType.SECRET) {
            builder.entityName("Secret");
        }
        if (actionLog.getParentType() == EntityType.SECRET) {
            builder.parentName("Secret");
        }
        return builder.build();
    }
}

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

import org.qubership.integration.platform.variables.management.logging.constant.ContextHeaders;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.user.User;
import org.qubership.integration.platform.variables.management.persistence.configs.repository.actionlog.ActionLogRepository;
import org.qubership.integration.platform.variables.management.rest.exception.InvalidEnumConstantException;
import org.qubership.integration.platform.variables.management.rest.v1.dto.actionlog.ActionLogSearchCriteria;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
        MDC.put("logType","audit");
        String actionOperationName = action.getOperation() != null ? action.getOperation().name() : "-";
        String entityTypeName = action.getEntityType() != null ? action.getEntityType().name() : "-";
        String entityNameDescriptionString = action.getEntityName() != null ? " with name ".concat(action.getEntityName()) : "";
        String entityIdDescriptionString = action.getEntityId() != null ? " with id: ".concat(action.getEntityId()) : "";
        String parentTypeName = action.getParentType() != null ? " under parent entity ".concat(action.getParentType().name()) : "-";
        String parentNameDescriptionString = action.getParentName() != null ? " with name ".concat(action.getParentName()) : "";
        String parentIdDescriptionString = action.getParentId() != null ? " with id: ".concat(action.getParentId()) : "";
        String userDescriptionString = action.getUser().getUsername() != null ? " performed by user ".concat(action.getUser().getUsername()) : "";
        userDescriptionString = action.getUser().getId() != null ? userDescriptionString.concat(" with id: ").concat(action.getUser().getId()) : "";
        log.debug("Action {} for {}{}{}{}{}{}{}", actionOperationName, entityTypeName, entityNameDescriptionString,
                entityIdDescriptionString, parentTypeName, parentNameDescriptionString, parentIdDescriptionString,
                userDescriptionString);
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
        if (actionLog.getEntityType() == EntityType.SECRET) {
            actionLog.setEntityName("Secret");
        }
        if (actionLog.getParentType() == EntityType.SECRET) {
            actionLog.setParentName("Secret");
        }

        return actionLog;
    }
}

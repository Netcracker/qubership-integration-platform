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

package org.qubership.integration.platform.variables.management.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TasksScheduler {
    private final ActionsLogService actionsLogService;

    @Value("${qip.actions-log.cleanup.interval}")
    private String actionLogInterval;

    public TasksScheduler(ActionsLogService actionsLogService) {
        this.actionsLogService = actionsLogService;
    }

    @Scheduled(cron = "${qip.actions-log.cleanup.cron}")
    public void cleanupActionsLog() {
        actionsLogService.deleteAllOldRecordsByInterval(actionLogInterval);
        log.info("Remove old records from actions log table");
    }
}

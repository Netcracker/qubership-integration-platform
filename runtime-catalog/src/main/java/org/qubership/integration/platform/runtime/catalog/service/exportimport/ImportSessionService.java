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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.ImportSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ImportSessionService {

    public static final short COMMON_VARIABLES_IMPORT_PERCENTAGE_THRESHOLD = 10;
    public static final short SERVICE_IMPORT_PERCENTAGE_THRESHOLD = 30;
    public static final short CHAIN_IMPORT_PERCENTAGE_THRESHOLD = 60;
    public static final short SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD = 80;

    private final Integer importSessionLifetimeMinutes;
    private final ImportSessionRepository importSessionRepository;

    @Autowired
    public ImportSessionService(
            @Value("${qip.import.session-lifetime-minutes:300}") Integer importSessionLifetimeMinutes,
            ImportSessionRepository importSessionRepository
    ) {
        this.importSessionLifetimeMinutes = importSessionLifetimeMinutes;
        this.importSessionRepository = importSessionRepository;
    }

    @Nullable
    public ImportSession getImportSession(String importId) {
        return importSessionRepository.findById(importId).orElse(null);
    }

    public void saveImportSession(ImportSession importSession) {
        importSessionRepository.save(importSession);
    }

    @Transactional
    public void deleteObsoleteImportSessionStatuses() {
        importSessionRepository.deleteSessionsOlderThan(importSessionLifetimeMinutes);
    }

    public void setImportProgressPercentage(String importId, int percentage) {
        if (importId == null) {
            return;
        }

        ImportSession importSession = getImportSession(importId);
        if (importSession == null) {
            importSession = new ImportSession();
            importSession.setId(importId);
        }

        importSession.setCompletion(percentage);
        saveImportSession(importSession);
    }

    public void calculateImportStatus(String importId, int total, int counter, int fromPercentage, int toPercentage) {
        if (importId == null) {
            return;
        }

        int percentage;
        if (total == 0) {
            percentage = toPercentage;
        } else {
            percentage = (int) (fromPercentage + (toPercentage - fromPercentage) * ((double) counter / (double) total));
        }

        setImportProgressPercentage(importId, percentage);
    }
}

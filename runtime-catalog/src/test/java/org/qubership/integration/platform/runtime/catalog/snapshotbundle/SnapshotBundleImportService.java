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

package org.qubership.integration.platform.runtime.catalog.snapshotbundle;

import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ChainImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ContextExportImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportSessionService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.MCPSystemImportExportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.SystemExportImportService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.variables.CommonVariablesService;

import java.io.File;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import static org.awaitility.Awaitility.await;

public class SnapshotBundleImportService extends GeneralImportService {

    private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    public SnapshotBundleImportService(
            CommonVariablesService commonVariablesService,
            SystemExportImportService systemExportImportService,
            ContextExportImportService contextExportImportService,
            MCPSystemImportExportService mcpSystemImportExportService,
            ChainImportService chainImportService,
            ImportSessionService importSessionService,
            ActionsLogService actionsLogService,
            ImportInstructionsService importInstructionsService,
            GeneralInstructionsMapper generalInstructionsMapper
    ) {
        super(
                commonVariablesService,
                systemExportImportService,
                contextExportImportService,
                mcpSystemImportExportService,
                chainImportService,
                importSessionService,
                actionsLogService,
                importInstructionsService,
                generalInstructionsMapper
        );
    }

    public ImportResult importDirectoryAndAwaitCompletion(
            File importDirectory,
            ImportRequest importRequest,
            Set<String> technicalLabels,
            boolean validateByHash
    ) {
        String importId = importDirectoryAsync(
                importDirectory,
                importRequest,
                technicalLabels,
                validateByHash
        );

        ImportSession importSession = await()
                .alias("catalog import session " + importId)
                .pollInterval(POLL_INTERVAL)
                .atMost(IMPORT_TIMEOUT)
                .until(
                        () -> getImportSession(importId),
                        session -> session != null
                                && (session.getResult() != null || session.getError() != null)
                );

        if (importSession.getError() != null) {
            throw new IllegalStateException(
                    "Catalog import '" + importId + "' failed: " + importSession.getError()
            );
        }

        return Objects.requireNonNull(
                importSession.getResult(),
                "Catalog import '" + importId + "' completed without a result"
        );
    }
}

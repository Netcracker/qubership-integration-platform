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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions;

import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ChainsIgnoreOverrideResult(
        Map<String, ChainCommitRequestAction> chainsToImport,
        Map<String, String> overridesMapping,
        List<ImportInstructionResult> chainImportInstructionResults
) {
    public ChainsIgnoreOverrideResult {
        if (chainsToImport == null) {
            chainsToImport = new HashMap<>();
        }
        if (overridesMapping == null) {
            overridesMapping = new HashMap<>();
        }
        if (chainImportInstructionResults == null) {
            chainImportInstructionResults = new ArrayList<>();
        }
    }
}

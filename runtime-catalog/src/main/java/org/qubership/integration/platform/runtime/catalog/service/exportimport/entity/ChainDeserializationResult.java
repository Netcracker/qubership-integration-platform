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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class ChainDeserializationResult {
    private Chain chain;
    private Map<String, String> propertiesFileNames;
    private ChainCommitRequestAction deployAction;
    private List<DeploymentExternalEntity> deployments;
    private ImportEntityStatus status;
    private String errorMessage;
    private String defaultSwimlaneId;
    private String reuseSwimlaneId;
}

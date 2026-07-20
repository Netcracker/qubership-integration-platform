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

package org.qubership.integration.platform.chain.impl;

import lombok.Data;
import org.qubership.integration.platform.chain.model.ImportEnvironment;
import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;
import org.qubership.integration.platform.io.model.exportimport.system.User;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Data
public class ImportSystemImpl implements ImportSystem {
    private String id;
    private String name;
    private String description;
    private User createdBy;
    private Timestamp createdWhen;
    private User modifiedBy;
    private Timestamp modifiedWhen;
    private String activeEnvironmentId;
    private IntegrationSystemType integrationSystemType;
    private String internalServiceName;
    private OperationProtocol protocol;
    private List<ImportEnvironment> environments = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private List<ImportSpecificationGroup> specificationGroups = new ArrayList<>();
}

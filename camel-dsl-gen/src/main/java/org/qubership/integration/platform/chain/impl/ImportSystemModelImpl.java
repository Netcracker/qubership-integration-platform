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
import org.qubership.integration.platform.chain.model.ImportOperation;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource;
import org.qubership.integration.platform.io.model.exportimport.system.User;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Data
public class ImportSystemModelImpl implements ImportSystemModel {
    private String id;
    private String name;
    private String description;
    private User createdBy;
    private Timestamp createdWhen;
    private User modifiedBy;
    private Timestamp modifiedWhen;
    private boolean deprecated;
    private String version;
    private SystemModelSource source;
    private List<ImportOperation> operations = new ArrayList<>();
    private String parentId;
    private List<String> labels = new ArrayList<>();
    private List<ImportSpecificationSource> specificationSources = new ArrayList<>();
}

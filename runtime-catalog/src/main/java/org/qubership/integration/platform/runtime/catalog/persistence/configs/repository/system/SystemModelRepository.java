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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system;

import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SystemModelRepository extends JpaRepository<SystemModel, String> {
    List<SystemModel> findSystemModelsBySpecificationGroupSystemId(String systemId);

    SystemModel findFirstBySpecificationGroupIdAndSourceEqualsOrderByCreatedWhenDesc(String specificationGroupId, SystemModelSource source);

    SystemModel findFirstBySpecificationGroupSystemIdOrderByCreatedWhenDesc(String systemId);

    SystemModel findFirstBySpecificationGroupIdAndVersion(String specificationGroupId, String version);

    List<SystemModel> findAllBySpecificationGroupId(String specificationGroupId);

    long countBySpecificationGroupIdAndVersion(String specificationId, String version);

    @Query("select model.id, lib.modifiedWhen "
            + "from SystemModel model "
            + "inner join model.compiledLibrary lib "
            + "where lib.data is not null")
    List<Object[]> findAllWithCompiledLibraries();
}

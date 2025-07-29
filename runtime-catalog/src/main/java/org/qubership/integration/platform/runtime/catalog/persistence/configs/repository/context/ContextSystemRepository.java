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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context;


import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ContextSystemRepository extends JpaRepository<ContextSystem, String>, JpaSpecificationExecutor<ContextSystem> {

    @Query(nativeQuery = true,
            value = "SELECT * FROM catalog.context_system sys WHERE sys.id = :searchCondition "
                    + "UNION "
                    + "SELECT * FROM catalog.context_system sys WHERE UPPER(sys.name) = UPPER(:searchCondition) "
                    + "UNION "
                    + "SELECT * FROM catalog.context_system sys WHERE UPPER(sys.name) <> UPPER(:searchCondition) "
                    + "AND UPPER(sys.name) LIKE UPPER('%'||:searchCondition||'%') "
                    + "UNION "
                    + "SELECT * FROM catalog.context_system sys WHERE UPPER(sys.description) LIKE UPPER('%'||:searchCondition||'%')"
    )
    List<ContextSystem> searchForContextSystems(String searchCondition);
}

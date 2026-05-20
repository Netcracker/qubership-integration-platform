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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DependencyRepository extends CommonRepository<Dependency>, JpaRepository<Dependency, String> {

    @Query(value = "SELECT d FROM dependencies d "
            + "WHERE d.elementFrom.id = :from "
            + "and d.elementTo.id = :to")
    Optional<Dependency> findByFromAndTo(@Param("from") String from, @Param("to") String to);

    @Query(value = "SELECT d FROM dependencies d "
            + "WHERE d.elementFrom.id in :elementIDs "
            + "or d.elementTo.id in :elementIDs")
    List<Dependency> findByElementIDs(List<String> elementIDs);
}

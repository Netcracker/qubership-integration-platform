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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.sql.Timestamp;
import java.util.List;

public interface ConfigParameterRepository extends JpaRepository<ConfigParameter, String> {

    List<ConfigParameter> findAllByNamespace(String namespace);

    ConfigParameter findByNamespaceAndName(String namespace, String name);

    void deleteByNamespaceAndName(String namespace, String name);

    void deleteAllByNamespace(String namespace);

    // Returns 1 if it took the lock: the row was missing, released, or last taken before staleBefore.
    @Modifying
    @Query(
            nativeQuery = true,
            value = """
                INSERT INTO catalog.config_parameters (id, namespace, name, value_type, value, created_when, modified_when)
                VALUES (:id, :namespace, :name, 'BOOLEAN', 'true', :now, :now)
                ON CONFLICT (namespace, name) DO UPDATE SET value = 'true', modified_when = :now
                WHERE config_parameters.value = 'false' OR config_parameters.modified_when < :staleBefore""")
    int acquireLock(String id, String namespace, String name, Timestamp now, Timestamp staleBefore);

}

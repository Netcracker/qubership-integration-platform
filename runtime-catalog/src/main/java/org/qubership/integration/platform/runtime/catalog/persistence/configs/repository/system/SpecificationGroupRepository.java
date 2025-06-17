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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpecificationGroupRepository extends JpaRepository<SpecificationGroup, String> {

    List<SpecificationGroup> findAllBySystemId(String systemId);

    SpecificationGroup findByNameAndSystem(String name, IntegrationSystem system);

    SpecificationGroup findBySystemIdAndUrl(String systemId, String url);
}

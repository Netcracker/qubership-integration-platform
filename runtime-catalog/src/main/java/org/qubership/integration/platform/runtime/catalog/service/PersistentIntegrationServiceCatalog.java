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

package org.qubership.integration.platform.runtime.catalog.service;

import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.runtime.catalog.adapters.IntegrationServiceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

@Component
public class PersistentIntegrationServiceCatalog implements IntegrationServiceCatalog {
    private final SystemService systemService;

    @Autowired
    public PersistentIntegrationServiceCatalog(SystemService systemService) {
        this.systemService = systemService;
    }

    @Override
    public Optional<IntegrationService> findById(String id) {
        return Optional.ofNullable(systemService.findById(id)).map(IntegrationServiceAdapter::new);
    }

    @Override
    public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
        return systemService.findAllByIds(ids).stream()
            .<IntegrationService>map(IntegrationServiceAdapter::new)
            .toList();
    }
}

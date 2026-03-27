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

package org.qubership.integration.platform.runtime.catalog.consul;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.compiledlibrary.CompiledLibraryUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Component
public class CompiledLibraryEventsProducerService {
    private final SystemModelRepository systemModelRepository;
    private final ConsulService consulService;

    @Getter
    private boolean initUpdateCompleted = false;

    @Autowired
    public CompiledLibraryEventsProducerService(
            SystemModelRepository systemModelRepository,
            ConsulService consulService) {
        this.systemModelRepository = systemModelRepository;
        this.consulService = consulService;
    }

    public synchronized void sendAllCompiledLibrariesUpdate() {
        List<CompiledLibraryUpdate> allLibs = new ArrayList<>(getAllLibrariesUpdates());
        log.debug("Send all compiled libraries update to Consul...");
        consulService.updateLibraries(allLibs);
        initUpdateCompleted = true;
    }

    private List<CompiledLibraryUpdate> getAllLibrariesUpdates() {
        return systemModelRepository.findAllWithCompiledLibraries().stream()
                .map(fields ->
                        CompiledLibraryUpdate.builder()
                                .modelId((String) fields[0])
                                .timestamp((Timestamp) fields[1])
                                .build())
                .collect(Collectors.toList());
    }
}

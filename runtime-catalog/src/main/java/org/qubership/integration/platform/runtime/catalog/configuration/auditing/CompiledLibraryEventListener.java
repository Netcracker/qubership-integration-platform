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

package org.qubership.integration.platform.runtime.catalog.configuration.auditing;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.compiledlibrary.CompiledLibraryEvent;
import org.qubership.integration.platform.runtime.catalog.model.compiledlibrary.CompiledLibraryEventType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.CompiledLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CompiledLibraryEventListener {
    private static ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        CompiledLibraryEventListener.applicationEventPublisher = applicationEventPublisher;
    }

    @PostRemove
    private void afterRemove(CompiledLibrary compiledLibrary) {
        if (log.isDebugEnabled()) {
            log.debug("Removed compiled library {}", compiledLibrary.getId());
        }
        notify(CompiledLibraryEventType.REMOVED, compiledLibrary);
    }

    @PostPersist
    private void afterPersist(CompiledLibrary compiledLibrary) {
        if (log.isDebugEnabled()) {
            log.debug("Created compiled library {}", compiledLibrary.getId());
        }
        notify(CompiledLibraryEventType.CREATED, compiledLibrary);
    }

    @PostUpdate
    private void afterUpdate(CompiledLibrary compiledLibrary) {
        if (log.isDebugEnabled()) {
            log.debug("Updated compiled library {}", compiledLibrary.getId());
        }
        notify(CompiledLibraryEventType.UPDATED, compiledLibrary);
    }

    private void notify(CompiledLibraryEventType eventType, CompiledLibrary compiledLibrary) {
        CompiledLibraryEvent event = CompiledLibraryEvent.builder()
                .eventType(eventType)
                .compiledLibraryId(compiledLibrary.getId())
                .build();
        log.debug("Create CompiledLibraryEvent {}", event);
        applicationEventPublisher.publishEvent(event);
    }
}

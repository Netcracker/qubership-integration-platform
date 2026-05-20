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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.model.compiledlibrary.CompiledLibraryEvent;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class CompiledLibrarySpringEventListener {
    public static final int UPDATE_RETRY_DELAY = 3000;
    private final BlockingQueue<Object> updateEvent = new LinkedBlockingQueue<>(1);

    private final CompiledLibraryEventsProducerService compiledLibraryEventsProducerService;

    @Autowired
    public CompiledLibrarySpringEventListener(CompiledLibraryEventsProducerService compiledLibraryEventsProducerService) {
        this.compiledLibraryEventsProducerService = compiledLibraryEventsProducerService;

        new Thread(this::sendAllCompiledLibrariesUpdateRetryable).start();
    }

    @EventListener
    public void onApplicationStarted(ApplicationStartedEvent event) {
        updateEvent.offer(event);
    }

    @TransactionalEventListener
    public void catchLibraryUpdate(CompiledLibraryEvent event) {
        updateEvent.offer(event);
    }

    private void sendAllCompiledLibrariesUpdateRetryable() {
        while (true) { // worker loop
            try {
                Object event = updateEvent.take(); // wait for events
                log.debug("Catch library update event: {}", event);

                while (true) { // retry loop
                    try {
                        log.debug("Sending all system model compiled libraries update started...");

                        compiledLibraryEventsProducerService.sendAllCompiledLibrariesUpdate();

                        log.debug("Sending all system model compiled libraries update completed");
                        break;
                    } catch (Exception e) {
                        MDC.put("error_code", "8050");
                        log.warn("Retry of Event Listener failed with error: "
                                + "Attempt to collect and send all compiled libraries update failed: {}", e.getMessage());
                        MDC.remove("error_code");

                        try {
                            Thread.sleep(UPDATE_RETRY_DELAY);
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.error("Failed to get event from queue", e);
            }
        }
    }
}

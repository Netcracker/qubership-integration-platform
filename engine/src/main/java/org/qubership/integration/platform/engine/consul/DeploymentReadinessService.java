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

package org.qubership.integration.platform.engine.consul;

import org.qubership.integration.platform.engine.configuration.DeploymentReadinessAutoConfiguration;
import org.qubership.integration.platform.engine.events.UpdateEvent;

import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeploymentReadinessService {
    public static final int CONSUMER_STARTUP_CHECK_DELAY_MILLIS = 20 * 1000;

    // <event_class, is_consumed>
    private final ConcurrentMap<Class<? extends UpdateEvent>, Boolean> receivedEvents;
    
    @Getter
    private boolean readyForDeploy = false;

    @Getter
    @Setter
    private boolean initialized = false;

    @Autowired
    public DeploymentReadinessService(
        @Qualifier(DeploymentReadinessAutoConfiguration.DEPLOYMENT_READINESS_EVENTS_BEAN) Set<Class<? extends UpdateEvent>> events
    ) {
        if (log.isDebugEnabled()) {
            String eventClassNames = events.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")); 
            log.debug("Required events to start deployments processing: {}", eventClassNames);
        }
        receivedEvents = new ConcurrentHashMap<>(events.stream().collect(Collectors.toMap(event -> event, event -> false)));
    }

    @Async
    @EventListener(ApplicationStartedEvent.class)
    public void onApplicationStarted() {
        try {
            Thread.sleep(CONSUMER_STARTUP_CHECK_DELAY_MILLIS);
        } catch (InterruptedException ignored) {
        }

        if (!isRequiredEventsReceived()) {
            Map<String, Boolean> outputMap = receivedEvents.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().getSimpleName(),
                    Map.Entry::getValue));
            log.error(
                "At least one required event was not received (for this time) to start deployments processing!"
                    + "Events status: {}", outputMap);
        }
    }

    @EventListener
    public synchronized void onUpdateEvent(UpdateEvent event) {
        Class<? extends UpdateEvent> cls = event.getClass();
        if (event.isInitialUpdate() && receivedEvents.containsKey(cls)) {
            log.debug("Initial UpdateEvent received: {}", cls.getSimpleName());
            receivedEvents.put(cls, true);
            checkAndStartDeploymentUpdatesConsumer();
        }
    }

    /**
     * Start deployment consuming if all required events received
     */
    private synchronized void checkAndStartDeploymentUpdatesConsumer() {
        if (!readyForDeploy && isRequiredEventsReceived()) {
            log.info("Required events to start deployment updates consumer received successfully");
            readyForDeploy = true;
        }
    }

    private boolean isRequiredEventsReceived() {
        return receivedEvents.entrySet().stream().allMatch(Entry::getValue);
    }
}

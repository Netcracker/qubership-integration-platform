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

import org.qubership.integration.platform.engine.events.CommonVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.SecuredVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.util.DevModeUtil;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeploymentReadinessService {

    public static final int CONSUMER_STARTUP_CHECK_DELAY_MILLIS = 20 * 1000;


    // <event_class, is_consumed>
    private final ConcurrentMap<Class<? extends UpdateEvent>, Boolean> receivedEvents =
        new ConcurrentHashMap<>(Map.of(
            CommonVariablesUpdatedEvent.class, false
        ));
    @Getter
    private boolean readyForDeploy = false;

    @Getter
    @Setter
    private boolean initialized = false;

    @Autowired
    public DeploymentReadinessService(DevModeUtil devModeUtil) {
        if (!devModeUtil.isDevMode()) {
            receivedEvents.put(SecuredVariablesUpdatedEvent.class, false);
        }
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
    public void onCommonVariablesUpdated(CommonVariablesUpdatedEvent event) {
        onUpdateEvent(event);
    }

    @EventListener
    public void onSecuredVariablesUpdated(SecuredVariablesUpdatedEvent event) {
        onUpdateEvent(event);
    }

    private synchronized void onUpdateEvent(UpdateEvent event) {
        if (event.isInitialUpdate()) {
            if (log.isDebugEnabled()) {
                log.debug("Initial UpdateEvent received: {}",
                    event.getClass().getSimpleName());
            }
            receivedEvents.put(event.getClass(), true);
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

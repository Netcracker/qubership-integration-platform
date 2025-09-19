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

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.configuration.DeploymentReadinessProvider;
import org.qubership.integration.platform.engine.configuration.EventClassesContainerWrapper;
import org.qubership.integration.platform.engine.events.UpdateEvent;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class DeploymentReadinessService {
    public static final int CONSUMER_STARTUP_CHECK_DELAY_MILLIS = 20 * 1000;

    // <event_class, is_consumed>
    private final ConcurrentMap<Class<? extends UpdateEvent>, Boolean> receivedEvents;

    @Getter
    private boolean readyForDeploy = false;

    @Getter
    @Setter
    private boolean initialized = false;

    @Inject
    public DeploymentReadinessService(
        @Named(DeploymentReadinessProvider.DEPLOYMENT_READINESS_EVENTS_BEAN)
        EventClassesContainerWrapper eventClassesContainerWrapper
    ) {
        if (log.isDebugEnabled()) {
            String eventClassNames = eventClassesContainerWrapper.getEventClasses()
                    .stream()
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(", "));
            log.debug("Required events to start deployments processing: {}", eventClassNames);
        }
        receivedEvents = new ConcurrentHashMap<>(
                eventClassesContainerWrapper.getEventClasses()
                        .stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                eventClass -> false)));
    }

    public void onApplicationStarted(@Observes StartupEvent ev) {
        try {
            Thread.sleep(CONSUMER_STARTUP_CHECK_DELAY_MILLIS);
        } catch (InterruptedException ignored) {
            // Do nothing
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

    @ConsumeEvent(UpdateEvent.EVENT_ADDRESS)
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

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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.events.EngineStateUpdateEvent;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.event.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.EngineMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Component
public class EventService {
    private static final int EVENT_TIME_THRESHOLD_MS = 15 * 1000;
    private static final int EVENTS_QUEUE_SIZE = 100;

    private final DeploymentService deploymentService;
    private final EngineService engineService;
    private final DeploymentMapper deploymentMapper;
    private final EngineMapper engineMapper;
    private final AuditorAware<User> auditor;
    private final ReadWriteLock readWriteLock;
    private final CircularFifoQueue<Event> circularFifoQueue;

    @Autowired
    public EventService(DeploymentService deploymentService,
                        EngineService engineService,
                        DeploymentMapper deploymentMapper,
                        EngineMapper engineMapper,
                        AuditorAware<User> auditor) {
        this.deploymentService = deploymentService;
        this.engineService = engineService;
        this.deploymentMapper = deploymentMapper;
        this.engineMapper = engineMapper;
        this.auditor = auditor;
        this.readWriteLock = new ReentrantReadWriteLock();
        this.circularFifoQueue = new CircularFifoQueue<>(EVENTS_QUEUE_SIZE);
    }

    @EventListener
    public void applicationStartedListener(ApplicationStartedEvent event) {
        try {
            subscribeOnEvents();
        } catch (Exception e) {
            log.error("Failed to subscribe on events", e);
        }
    }


    /**
     * Return all available events from lastEventId, but not older than EVENT_TIME_THRESHOLD_MS
     */
    public EventsUpdate getEvents(String lastEventId) {
        List<Event> result = new ArrayList<>();
        long now = new Date().getTime();
        boolean lastEventFound = lastEventId.isBlank();
        String newLastEventId = "";
        Optional<User> user = auditor.getCurrentAuditor();
        String userId = null;
        if (user.isPresent()) {
            userId = user.get().getId();
        }

        readWriteLock.readLock().lock();
        try {
            for (Event event : circularFifoQueue) {
                if (lastEventFound) {
                    if (now - event.getTime() < EVENT_TIME_THRESHOLD_MS
                            && (event.getUserId() == null || event.getUserId().equals(userId))) {
                        result.add(event);
                    }
                } else {
                    if (event.getId().equals(lastEventId)) {
                        lastEventFound = true;
                    }
                }
            }
            if (!circularFifoQueue.isEmpty()) {
                newLastEventId = circularFifoQueue.get(circularFifoQueue.size() - 1).getId();
            }
        } finally {
            readWriteLock.readLock().unlock();
        }

        return EventsUpdate.builder().events(result).lastEventId(newLastEventId).build();
    }

    @EventListener
    public void onEngineStateUpdate(EngineStateUpdateEvent stateUpdateEvent) {
        try {
            EngineDeployment engineDeployment = stateUpdateEvent.getEngineDeployment();
            addEvent(UUID.randomUUID().toString(),
                    deploymentMapper.toRuntimeUpdate(
                            engineDeployment,
                            stateUpdateEvent.getEngineInfo(),
                            stateUpdateEvent.getLoggingInfo()),
                    ObjectType.DEPLOYMENT,
                    stateUpdateEvent.getUserId());
        } catch (Exception e) {
            log.warn("Failed to add engine update state event: {}", e.getMessage());
        }
    }

    private void subscribeOnEvents() {
        deploymentService.subscribeMessages(this::addMessageEvent);

        engineService.subscribeEngines((id, pod, domain, actionType, userId) ->
                addEvent(UUID.randomUUID().toString(), engineMapper.asEngineUpdate(pod, domain, actionType), ObjectType.ENGINE, userId));
    }

    private void addMessageEvent(String id, String userId, String message, GenericMessageType type, Map<String, String> optionalFields) {
        readWriteLock.writeLock().lock();
        try {
            circularFifoQueue.add(Event.builder()
                    .id(id)
                    .userId(userId)
                    .time(new Date().getTime())
                    .objectType(ObjectType.GENERIC_MESSAGE)
                    .data(GenericMessage.builder()
                            .message(message)
                            .type(type)
                            .optionalFields(optionalFields)
                            .build())
                    .build());
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    private void addEvent(String id, Object data, ObjectType type, String userId) {
        readWriteLock.writeLock().lock();
        try {
            circularFifoQueue.add(Event.builder()
                    .id(id)
                    .userId(userId)
                    .time(new Date().getTime())
                    .data(data)
                    .objectType(type)
                    .build());
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }
}

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

package org.qubership.integration.platform.engine.service.debugger.sessions;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.configuration.opensearch.OpenSearchConfiguration;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.opensearch.QueueElement;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class OpenSearchWriter implements Runnable {

    private final int queueMaxSizeBytes;
    private final int bulkRequestMaxSizeBytes;
    private final int bulkRequestPayloadSizeThresholdBytes;
    private final int bulkRequestElementsCountThreshold;

    private final OpenSearchClientSupplier openSearchClientSupplier;
    private final ObjectMapper mapper;

    private final BlockingQueue<QueueElement> sessionElementsQueue;
    // total queue bodyBefore+bodyAfter size in bytes
    private final AtomicLong queueTotalPayloadSize = new AtomicLong(0);
    // <sessionId, session>
    private final ConcurrentMap<String, Pair<ReadWriteLock, Session>> sessionsCache = new ConcurrentHashMap<>();
    // <sessionId, <elementId, Element>>
    private final ConcurrentMap<String, ConcurrentMap<String, SessionElementElastic>> sessionElementsCache = new ConcurrentHashMap<>();
    // <sessionId, last_element>
    private final ConcurrentMap<String, SessionElementElastic> singleElementCache = new ConcurrentHashMap<>();
    private long currentWriteTimeout = 0;

    @Value("${qip.opensearch.write.batch.count}")
    private int queueDrainThreshold;
    @Value("${qip.opensearch.write.retry.timeout.minimum}")
    private long writeTimeoutDefaultMin;
    @Value("${qip.opensearch.write.retry.timeout.maximum}")
    private long writeTimeoutDefaultMax;
    @Value("${qip.opensearch.index.elements.name}-session-elements")
    private String indexName;

    private static final int EXCEPTION_COOLDOWN_DELAY = 10000;

    private static final int WRITE_TIMEOUT_MULTIPLIER = 2;
    private static final int ERROR_MESSAGE_COUNT_THRESHOLD = 3;

    private static final int RETRY_COUNT_ON_WRITE_ERROR = 5;
    private static final double REPEATED_ELEMENTS_RATIO = 2.2; // element objects in the queue can be repeated

    @Autowired
    public OpenSearchWriter(@Value("${qip.sessions.queue.capacity}") int sessionBufferCapacity,
                            @Value("${qip.sessions.queue.max-size-mb}") int queueMaxSizeMb,
                            @Value("${qip.sessions.bulk-request.max-size-kb}") int bulkRequestMaxSizeKb,
                            @Value("${qip.sessions.bulk-request.payload-size-threshold-kb}") int bulkRequestPayloadSizeThresholdKb,
                            @Value("${qip.sessions.bulk-request.elements-count-threshold}") int bulkRequestElementsCountThreshold,
                            OpenSearchClientSupplier openSearchClientSupplier,
                            @Qualifier("jsonMapper") ObjectMapper mapper) {
        sessionElementsQueue = new LinkedBlockingQueue<>(sessionBufferCapacity);
        this.queueMaxSizeBytes = (int) (queueMaxSizeMb * 1024 * 1024 * REPEATED_ELEMENTS_RATIO);

        this.bulkRequestMaxSizeBytes = bulkRequestMaxSizeKb * 1024;
        this.bulkRequestPayloadSizeThresholdBytes = bulkRequestPayloadSizeThresholdKb * 1024;
        this.bulkRequestElementsCountThreshold = bulkRequestElementsCountThreshold;

        this.openSearchClientSupplier = openSearchClientSupplier;
        this.mapper = mapper;

        // start permanent writer thread
        new Thread(this).start();
    }

    @Override
    public void run() {
        List<QueueElement> elementsToSave = new ArrayList<>(queueDrainThreshold);
        resetWriteTimeout();

        while (true) {
            try {
                try {
                    // Wait for any element
                    elementsToSave.add(sessionElementsQueue.take());
                } catch (InterruptedException ignored) {
                    continue;
                }
                sessionElementsQueue.drainTo(elementsToSave, queueDrainThreshold - 1);
                elementsToSave.forEach(element -> queueTotalPayloadSize.addAndGet(
                    -element.getCalculatedPayloadSize()));
                LinkedHashSet<QueueElement> filteredElements = new LinkedHashSet<>(elementsToSave);

                if (!CollectionUtils.isEmpty(filteredElements)) {
                    saveElements(filteredElements);
                }

                elementsToSave.clear();
            } catch (Exception e) {
                log.error("Failed to commit sessions to opensearch", e);
                try {
                    Thread.sleep(EXCEPTION_COOLDOWN_DELAY);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private void saveElements(LinkedHashSet<QueueElement> sessionElements) {
        int currentRetry = 0;
        int bulkRequestSize = 0;

        byte[] payload;
        int payloadSize;
        boolean needToExecuteBulk = false;
        List<BulkOperation> updateRequests = new ArrayList<>();

        Iterator<QueueElement> iterator = sessionElements.iterator();
        while (iterator.hasNext()) {
            SessionElementElastic element = iterator.next().getElement();
            try {
                payload = mapper.writeValueAsBytes(element);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse sessions write request. Element skipped");
                resetWriteTimeout();
                continue;
            }

            payloadSize = payload.length;
            BulkOperation request = new BulkOperation.Builder()
                    .index(IndexOperation.of(io -> io
                            .index(openSearchClientSupplier.normalize(indexName))
                            .id(element.getId())
                            .requireAlias(true)
                            .document(element)
                    ))
                    .build();

            do {
                try {
                    if (payloadSize >= bulkRequestPayloadSizeThresholdBytes || sessionElements.size() <= bulkRequestElementsCountThreshold) {
                        waitBeforeRequest();
                        executeBulk(new ArrayList<>(List.of(request)));
                    } else {
                        if (currentRetry == 0) {
                            updateRequests.add(request);
                            bulkRequestSize += payloadSize;
                        }
                    }

                    needToExecuteBulk =
                            bulkRequestSize >= bulkRequestMaxSizeBytes ||
                                    (!iterator.hasNext() && !updateRequests.isEmpty());

                    if (needToExecuteBulk) {
                        waitBeforeRequest();
                        if (executeBulk(updateRequests)) {
                            throw new RuntimeException();
                        }
                        bulkRequestSize = 0;
                        needToExecuteBulk = false;
                    }
                } catch (Exception e) {
                    log.error("While sessions writing an error has occurred", e);
                    increaseWriteTimeout();
                    if (currentRetry < RETRY_COUNT_ON_WRITE_ERROR) {
                        currentRetry++;
                        continue;
                    } else {
                        if (needToExecuteBulk) {
                            bulkRequestSize = 0;
                            updateRequests.clear();
                            needToExecuteBulk = false;
                        }
                    }
                }

                if (currentRetry < RETRY_COUNT_ON_WRITE_ERROR) {
                    resetWriteTimeout();
                }
                currentRetry = 0;
            } while (currentRetry > 0);
        }
    }

    private boolean executeBulk(List<BulkOperation> updateRequests) throws IOException {
        BulkRequest bulkRequest = new BulkRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .requireAlias(true)
                .operations(updateRequests)
                .build();
        BulkResponse bulk = openSearchClientSupplier.getClient().bulk(bulkRequest);
        updateRequests.clear();
        return checkAndLogFailedElements(bulk);
    }

    private boolean checkAndLogFailedElements(BulkResponse response) {
        int errCount = 0;
        String separator = System.lineSeparator();
        StringBuilder errorMessages = new StringBuilder(separator);
        for (BulkResponseItem bulkItemResponse : response.items()) {
            if (bulkItemResponse.error() != null) {
                if (errCount < ERROR_MESSAGE_COUNT_THRESHOLD) {
                    errorMessages.append(bulkItemResponse.error().reason());
                    errorMessages.append(separator);
                }
                errCount++;
            }
        }
        if (errCount > 0) {
            errorMessages.insert(0, "Some sessions elements can't be saved to opensearch:");
            if (errCount > ERROR_MESSAGE_COUNT_THRESHOLD) {
                errorMessages.append("...and {} more");
                log.error(errorMessages.toString(), errCount - ERROR_MESSAGE_COUNT_THRESHOLD);
            } else {
                log.error(errorMessages.toString());
            }
        }
        return errCount > 0;
    }

    private void resetWriteTimeout() {
        currentWriteTimeout = writeTimeoutDefaultMin;
        log.trace("OpenSearch write timeout has been reset to {}", currentWriteTimeout);
    }

    private void increaseWriteTimeout() {
        if (currentWriteTimeout == writeTimeoutDefaultMax) {
            return;
        }
        currentWriteTimeout = Math.max(writeTimeoutDefaultMin, currentWriteTimeout);
        currentWriteTimeout *= WRITE_TIMEOUT_MULTIPLIER;
        currentWriteTimeout = Math.min(writeTimeoutDefaultMax, currentWriteTimeout);
        log.info("OpenSearch write timeout has been increased to {}", currentWriteTimeout);
    }

    private void waitBeforeRequest() {
        try {
            Thread.sleep(currentWriteTimeout);
        } catch (InterruptedException ignored) {
        }
    }

    public void scheduleElementToLog(SessionElementElastic element) {
        scheduleElementToLog(element, false);
    }

    public void scheduleElementToLogAndCache(SessionElementElastic element) {
        Pair<ReadWriteLock, Session> sessionPair = sessionsCache.get(element.getSessionId());
        if (sessionPair != null) {
            sessionPair.getLeft().readLock().lock();
            try {
                if (sessionsCache.containsKey(element.getSessionId())) {
                    scheduleElementToLog(element, true);
                } else {
                    element.setExecutionStatus(ExecutionStatus.CANCELLED_OR_UNKNOWN);
                    scheduleElementToLog(element, false);
                }
            } finally {
                sessionPair.getLeft().readLock().unlock();
            }
        } else {
            element.setExecutionStatus(ExecutionStatus.CANCELLED_OR_UNKNOWN);
            scheduleElementToLog(element, false);
        }
    }

    private void scheduleElementToLog(SessionElementElastic element, boolean addToCache) {
        long payloadSize = calculatePayloadSizeInBytes(element);
        if (queueTotalPayloadSize.get() >= queueMaxSizeBytes ||
            !sessionElementsQueue.offer(
                QueueElement.builder()
                    .element(element)
                    .calculatedPayloadSize(payloadSize)
                    .build())) {
            log.error("Queue of opensearch elements is full, element is not added");
        } else {
            queueTotalPayloadSize.addAndGet(payloadSize);
        }

        if (addToCache) {
            putSessionElementToCache(element);
        }
    }

    public void putSessionToCache(Session session) {
        String sessionId = session.getId();
        sessionsCache.put(sessionId, Pair.of(new ReentrantReadWriteLock(), session));
    }

    @Nullable
    public Pair<ReadWriteLock, Session> getSessionFromCache(String sessionId) {
        Pair<ReadWriteLock, Session> sessionPair = sessionsCache.get(sessionId);
        if (sessionPair == null || sessionPair.getRight() == null) {
            log.warn("Unable to get session from cache {}", sessionId);
        }
        return sessionPair;
    }

    private void putSessionElementToCache(SessionElementElastic sessionElement) {
        String sessionId = sessionElement.getSessionId();

        if (!sessionElementsCache.containsKey(sessionId)) {
            sessionElementsCache.put(sessionId, new ConcurrentHashMap<>());
        }
        sessionElementsCache.get(sessionId).put(sessionElement.getId(), sessionElement);
    }

    @Nullable
    public SessionElementElastic getSessionElementFromCache(String sessionId, String elementId) {
        Map<String, SessionElementElastic> elements = sessionElementsCache.get(sessionId);
        return elements != null ? elements.get(elementId) : null;
    }

    public Collection<SessionElementElastic> getSessionElementsFromCache(String sessionId) {
        Map<String, SessionElementElastic> elements = sessionElementsCache.get(sessionId);
        return elements != null ? elements.values() : Collections.emptyList();
    }

    public void putToSingleElementCache(String sessionId, SessionElementElastic sessionElement) {
        runWithSessionReadLock(sessionId, () -> singleElementCache.put(sessionId, sessionElement));
    }

    public SessionElementElastic moveFromSingleElementCacheToElementCache(String sessionId) {
        AtomicReference<SessionElementElastic> elementRef = new AtomicReference<>();

        runWithSessionReadLock(sessionId, () -> {
            elementRef.set(singleElementCache.remove(sessionId));

            if (elementRef.get() != null) {
                putSessionElementToCache(elementRef.get());
            }
        });

        return elementRef.get();
    }

    public void clearSessionCache(String sessionId) {
        sessionsCache.remove(sessionId);
        sessionElementsCache.remove(sessionId);
        singleElementCache.remove(sessionId);
    }

    private void runWithSessionReadLock(String sessionId, Runnable runnable) {
        Pair<ReadWriteLock, Session> sessionPair = sessionsCache.get(sessionId);
        if (sessionPair != null) {
            sessionPair.getLeft().readLock().lock();
            try {
                if (sessionsCache.containsKey(sessionId)) {
                    runnable.run();
                    return;
                }
            } finally {
                sessionPair.getLeft().readLock().unlock();
            }
        }
        log.debug("Session {} is not alive, skip sessions cache update", sessionId);
    }

    private long calculatePayloadSizeInBytes(SessionElementElastic element) {
        long size = 0;
        String bodyBefore = element.getBodyBefore();
        String bodyAfter = element.getBodyAfter();
        if (bodyBefore != null) {
            size += bodyBefore.length();
        }
        if (bodyAfter != null) {
            size += bodyAfter.length();
        }
        return size;
    }
}

package org.qubership.integration.platform.engine.service.debugger.sessions;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

@Slf4j
public abstract class OpenSearchWriter {

    // <sessionId, session>
    private final ConcurrentMap<String, Pair<ReadWriteLock, Session>> sessionsCache = new ConcurrentHashMap<>();
    // <sessionId, <elementId, Element>>
    private final ConcurrentMap<String, ConcurrentMap<String, SessionElementElastic>> sessionElementsCache = new ConcurrentHashMap<>();
    // <sessionId, last_element>
    private final ConcurrentMap<String, SessionElementElastic> singleElementCache = new ConcurrentHashMap<>();


    public abstract void scheduleElementToLog(SessionElementElastic element);

    protected abstract void scheduleElementToLog(SessionElementElastic element, boolean addToCache);

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

    protected void putSessionElementToCache(SessionElementElastic sessionElement) {
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

    protected void runWithSessionReadLock(String sessionId, Runnable runnable) {
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
}

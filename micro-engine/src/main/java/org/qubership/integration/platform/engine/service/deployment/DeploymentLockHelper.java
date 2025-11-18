package org.qubership.integration.platform.engine.service.deployment;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class DeploymentLockHelper {
    private final ReadWriteLock processLock = new ReentrantReadWriteLock();
    private final ConcurrentMap<String, Lock> chainLocks = new ConcurrentHashMap<>();

    private Lock getLockForChain(String chainId) {
        log.debug("Acquire a lock for chain {}", chainId);
        return chainLocks.computeIfAbsent(chainId, (key) -> new ReentrantLock(true /* for FIFO tasks order */));
    }

    public void runWithChainLock(String chainId, Runnable runnable) {
        Lock lock = getLockForChain(chainId);
        try {
            lock.lock();
            log.debug("Locked by-chain lock");
            runnable.run();
        } finally {
            lock.unlock();
            log.debug("Unlocked by-chain lock");
        }
    }

    public void runWithProcessReadLock(Runnable runnable) {
        Lock lock = processLock.readLock();
        try {
            lock.lock();
            log.debug("Locked process read lock");
            runnable.run();
        } finally {
            lock.unlock();
            log.debug("Unlocked process read lock");
        }
    }

    public <T> T runWithProcessWriteLock(Callable<T> callable) throws Exception {
        Lock lock = processLock.writeLock();
        try {
            lock.lock();
            log.debug("Locked process write lock");
            return callable.call();
        } finally {
            lock.unlock();
            log.debug("Unlocked process write lock");
        }
    }

    public void runWithProcessWriteLock(Runnable runnable) {
        Lock lock = processLock.writeLock();
        try {
            lock.lock();
            log.debug("Locked process write lock");
            runnable.run();
        } finally {
            lock.unlock();
            log.debug("Unlocked process write lock");
        }
    }
}

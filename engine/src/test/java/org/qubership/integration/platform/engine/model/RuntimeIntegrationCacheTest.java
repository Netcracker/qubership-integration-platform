package org.qubership.integration.platform.engine.model;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeIntegrationCacheTest {

    private static final int PRODUCERS = 4;
    private static final int DEPLOYMENTS_PER_PRODUCER = 2000;

    // Regression case for #1194. The retry tick used to iterate the flushed list while deployment threads
    // still added to and removed from it, so the iterator threw and the whole batch left the queue.
    @Test
    void retryQueueKeepsEveryEntryUnderConcurrentPutRemoveAndFlush() throws Exception {
        RuntimeIntegrationCache cache = new RuntimeIntegrationCache();
        Set<String> removed = ConcurrentHashMap.newKeySet();
        AtomicBoolean producing = new AtomicBoolean(true);
        ExecutorService threads = Executors.newFixedThreadPool(PRODUCERS + 1);
        try {
            Future<Map<String, Integer>> retryTick = threads.submit(() -> {
                Map<String, Integer> flushedCount = new HashMap<>();
                boolean more;
                do {
                    more = producing.get();
                    for (DeploymentUpdate update : cache.flushDeploymentsToRetry()) {
                        flushedCount.merge(update.getDeploymentInfo().getDeploymentId(), 1, Integer::sum);
                    }
                } while (more);
                return flushedCount;
            });
            List<Future<?>> deploymentThreads = new ArrayList<>();
            for (int producer = 0; producer < PRODUCERS; producer++) {
                String prefix = producer + "-";
                deploymentThreads.add(threads.submit(() -> {
                    for (int i = 0; i < DEPLOYMENTS_PER_PRODUCER; i++) {
                        cache.putToRetryQueue(deployment(prefix + i));
                        if (i % 3 == 2) {
                            String previous = prefix + (i - 1);
                            cache.removeRetryDeploymentFromQueue(previous);
                            removed.add(previous);
                        }
                    }
                }));
            }
            for (Future<?> deploymentThread : deploymentThreads) {
                deploymentThread.get(1, TimeUnit.MINUTES);
            }
            producing.set(false);
            Map<String, Integer> flushedCount = retryTick.get(1, TimeUnit.MINUTES);

            List<String> duplicates = flushedCount.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .toList();
            assertTrue(duplicates.isEmpty(), () -> "retried twice: " + duplicates.size() + ", e.g. " + duplicates.get(0));
            List<String> lost = new ArrayList<>();
            for (int producer = 0; producer < PRODUCERS; producer++) {
                for (int i = 0; i < DEPLOYMENTS_PER_PRODUCER; i++) {
                    String deploymentId = producer + "-" + i;
                    if (!removed.contains(deploymentId) && !flushedCount.containsKey(deploymentId)) {
                        lost.add(deploymentId);
                    }
                }
            }
            assertTrue(lost.isEmpty(), () -> "never retried: " + lost.size() + ", e.g. " + lost.get(0));
        } finally {
            threads.shutdownNow();
        }
    }

    private static DeploymentUpdate deployment(String deploymentId) {
        return DeploymentUpdate.builder()
                .deploymentInfo(DeploymentInfo.builder().deploymentId(deploymentId).build())
                .build();
    }
}

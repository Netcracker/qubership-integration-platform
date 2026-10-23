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

package org.qubership.integration.platform.engine.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Getter
@NoArgsConstructor
public class RuntimeIntegrationCache {
    @Getter(AccessLevel.NONE)
    private final ConcurrentMap<String, Lock> chainLocks = new ConcurrentHashMap<>(); // <chainId, lock>

    private final ConcurrentMap<String, SpringCamelContext> contexts = new ConcurrentHashMap<>(); // <deploymentId, context>
    private final ConcurrentMap<String, EngineDeployment> deployments = new ConcurrentHashMap<>(); // <deploymentId, deployment>

    // Deployment threads add and remove while the retry tick flushes; the methods below synchronize on the cache.
    @Getter(AccessLevel.NONE)
    private final List<DeploymentUpdate> deploymentsToRetry = new ArrayList<>();

    public Lock getLockForChain(String chainId) {
        return chainLocks.computeIfAbsent(chainId, (key) -> new ReentrantLock(true /* for FIFO tasks order */));
    }

    public synchronized Collection<DeploymentUpdate> flushDeploymentsToRetry() {
        List<DeploymentUpdate> flushed = new ArrayList<>(deploymentsToRetry);
        deploymentsToRetry.clear();
        return flushed;
    }

    public synchronized void putToRetryQueue(DeploymentUpdate deployment) {
        deploymentsToRetry.add(deployment);
    }

    public synchronized void removeRetryDeploymentFromQueue(String deploymentId) {
        deploymentsToRetry.removeIf(deployment -> deploymentId.equals(deployment.getDeploymentInfo().getDeploymentId()));
    }
}

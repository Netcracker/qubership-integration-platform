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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.idempotency;

import org.qubership.integration.platform.engine.camel.idempotency.IdempotentRepositoryKeyStrategy;
import org.qubership.integration.platform.engine.camel.idempotency.IdempotentRepositoryKeyStrategyBuilder;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;

public abstract class IdempotencyKeyStrategyFactoryBase implements IdempotencyKeyStrategyFactory {
    @Override
    public IdempotentRepositoryKeyStrategy getStrategy(
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        IdempotentRepositoryKeyStrategyBuilder builder = new IdempotentRepositoryKeyStrategyBuilder();

        configurePrefix(builder, properties, deploymentInfo);
        configureStrategy(builder, properties, deploymentInfo);
        configureSuffix(builder, properties, deploymentInfo);

        return builder.build();
    }

    protected abstract void configureStrategy(
        IdempotentRepositoryKeyStrategyBuilder builder,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    );

    private void configurePrefix(
        IdempotentRepositoryKeyStrategyBuilder builder,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        builder
            .append("dupcheck:")
            .append(deploymentInfo.getChainId())
            .append(":");
    }

    private void configureSuffix(
        IdempotentRepositoryKeyStrategyBuilder builder,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        builder
            .append(":")
            .appendIdempotencyKey();
    }
}

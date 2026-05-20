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

package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.mapstruct.Mapper;
import org.qubership.integration.platform.catalog.mapping.UserMapper;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.catalog.util.MapperUtils;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentRouteUpdate;

import java.util.List;

@Mapper(
        componentModel = "spring",
        uses = {UserMapper.class, MapperUtils.class}
)
public interface DeploymentRouteMapper {
    DeploymentRouteUpdate asUpdate(DeploymentRoute deploymentRoutes);

    List<DeploymentRouteUpdate> asUpdates(List<DeploymentRoute> deploymentRoutes);
}

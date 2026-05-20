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

package org.qubership.integration.platform.engine.controlplane;

import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;

import java.util.List;

public interface ControlPlaneService {

    void postPublicEngineRoutes(
            List<RouteRegistrationInfo> routes,
            String endpoint
    ) throws ControlPlaneException;

    void postPrivateEngineRoutes(
            List<RouteRegistrationInfo> routes,
            String endpoint
    ) throws ControlPlaneException;

    void removeEngineRoutesByPathsAndEndpoint(
            List<Pair<String, RouteType>> paths,
            String endpoint
    ) throws ControlPlaneException;

    void postEgressGatewayRoutes(RouteRegistrationInfo route);
}

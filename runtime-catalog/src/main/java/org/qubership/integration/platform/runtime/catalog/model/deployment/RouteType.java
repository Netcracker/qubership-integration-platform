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

package org.qubership.integration.platform.runtime.catalog.model.deployment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Route type to register on control plane")
public enum RouteType {
    EXTERNAL_TRIGGER,
    PRIVATE_TRIGGER,
    EXTERNAL_PRIVATE_TRIGGER,
    INTERNAL_TRIGGER,
    EXTERNAL_SENDER,
    EXTERNAL_SERVICE,
    INTERNAL_SERVICE,
    IMPLEMENTED_SERVICE;

    public static RouteType convertTriggerType(boolean isExternal, boolean isPrivate) {
        if (isPrivate && isExternal) {
            return EXTERNAL_PRIVATE_TRIGGER;
        }
        if (isExternal) {
            return EXTERNAL_TRIGGER;
        }
        if (isPrivate) {
            return PRIVATE_TRIGGER;
        }
        return INTERNAL_TRIGGER;
    }

    public static boolean isPrivateTriggerRoute(RouteType routeType) {
        return routeType == PRIVATE_TRIGGER || routeType == EXTERNAL_PRIVATE_TRIGGER;
    }

    public static boolean isExternalTriggerRoute(RouteType routeType) {
        return routeType == EXTERNAL_TRIGGER || routeType == EXTERNAL_PRIVATE_TRIGGER;
    }

    public static boolean triggerRouteCleanupNeeded(RouteType routeType) {
        return routeType == EXTERNAL_TRIGGER || routeType == PRIVATE_TRIGGER || routeType == INTERNAL_TRIGGER;
    }

    public static boolean triggerRouteWithGateway(RouteType routeType) {
        return routeType == EXTERNAL_TRIGGER || routeType == PRIVATE_TRIGGER || routeType == EXTERNAL_PRIVATE_TRIGGER;
    }
}

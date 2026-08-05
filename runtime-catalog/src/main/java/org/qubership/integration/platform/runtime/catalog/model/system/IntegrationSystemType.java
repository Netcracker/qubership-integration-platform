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

package org.qubership.integration.platform.runtime.catalog.model.system;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Schema(description = "Service type")
public enum IntegrationSystemType {
    INTERNAL,
    EXTERNAL,
    IMPLEMENTED;

    private static final Set<OperationProtocol> ALL_PROTOCOLS = Set.of(OperationProtocol.values());

    private static final Set<OperationProtocol> NON_METAMODEL_PROTOCOLS = Arrays.stream(OperationProtocol.values())
            .filter(protocol -> !OperationProtocol.METAMODEL.equals(protocol))
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<OperationProtocol> SYNCHRONOUS_PROTOCOLS = Set.of(
            OperationProtocol.HTTP,
            OperationProtocol.SOAP,
            OperationProtocol.GRAPHQL
    );

    // Sentinel for "no limit": callers compare an environment count against it.
    private static final int UNBOUNDED_ENVIRONMENTS = Integer.MAX_VALUE;

    public Set<OperationProtocol> allowedProtocols() {
        return switch (this) {
            case INTERNAL -> ALL_PROTOCOLS;
            case EXTERNAL -> NON_METAMODEL_PROTOCOLS;
            case IMPLEMENTED -> SYNCHRONOUS_PROTOCOLS;
        };
    }

    public int maxEnvironments() {
        return switch (this) {
            case INTERNAL, IMPLEMENTED -> 1;
            case EXTERNAL -> UNBOUNDED_ENVIRONMENTS;
        };
    }
}

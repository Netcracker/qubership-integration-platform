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

import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.EventActionType;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.DomainResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.EngineResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.EngineUpdateResponse;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EngineMapper {

    @Mapping(target = "id", source = "name")
    DomainResponse asDomainResponse(KubeDeployment domain);

    List<DomainResponse> asDomainResponses(List<KubeDeployment> domains);

    @Mapping(target = "id", source = "ip")
    @Mapping(target = "host", source = "ip")
    EngineResponse asEngineResponse(KubePod pod);

    List<EngineResponse> asEngineResponses(List<KubePod> pods);

    @IterableMapping(elementTargetType = EngineUpdateResponse.class)
    @Mapping(target = "actionType", source = "actionType")
    @Mapping(target = "domainId", source = "domain")
    @Mapping(target = "domainName", source = "domain")
    @Mapping(target = "id", source = "pod.ip")
    @Mapping(target = "ready", source = "pod.ready")
    @Mapping(target = "name", source = "pod.name")
    @Mapping(target = "host", source = "pod.ip")
    @Mapping(target = "runningStatus", source = "pod.runningStatus")
    @Mapping(target = "namespace", source = "pod.namespace")
    EngineUpdateResponse asEngineUpdate(KubePod pod, String domain, EventActionType actionType);
}

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
import org.mapstruct.Mapping;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationAlertsSet;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.DiagnosticValidationDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.ValidationChainEntityDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.ValidationStatusDTO;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.AbstractValidation;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(
        componentModel = "spring",
        uses = { MapperUtils.class }
)
public interface DiagnosticValidationMapper {
    @Mapping(source = "alertsSet.alertsCount", target = "alertsCount")
    @Mapping(source = "alertsSet.chainAlerts", target = "chainEntities")
    DiagnosticValidationDTO asDTO(AbstractValidation validation, ValidationStatusDTO status, ValidationAlertsSet alertsSet);

    ValidationStatusDTO asStatusDTO(ValidationStatus currentStatus);

    List<ValidationChainEntityDTO> asChainEntityDTO(List<ValidationChainAlert> chainEntities);

    @Mapping(source = "chain.id", target = "chainId")
    @Mapping(source = "chain.name", target = "chainName")
    @Mapping(source = "element.id", target = "elementId")
    @Mapping(source = "element.name", target = "elementName")
    @Mapping(source = "element.type", target = "elementType")
    ValidationChainEntityDTO asChainEntityDTO(ValidationChainAlert entity);
}

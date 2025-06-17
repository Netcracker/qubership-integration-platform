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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

@Schema(description = "Action log entity type")
public enum EntityType {
    FOLDER,
    CHAIN,
    SNAPSHOT,
    SNAPSHOT_CLEANUP,
    DEPLOYMENT,
    ELEMENT,
    DOMAIN,
    MASKED_FIELD,
    CHAINS,
    CHAIN_RUNTIME_PROPERTIES,
    DATABASE_SYSTEM,   //removed databases in 24.3
    DATABASE_SCRIPT,   //This types remained to avoid error with old actions(in action log) with databases
    SERVICE_DISCOVERY,
    EXTERNAL_SERVICE,
    INNER_CLOUD_SERVICE,
    IMPLEMENTED_SERVICE,
    ENVIRONMENT,
    SPECIFICATION,
    SPECIFICATION_GROUP,
    SERVICES,
    SECURED_VARIABLE,
    COMMON_VARIABLE,
    MAAS_KAFKA,
    MAAS_RABBITMQ,
    DETAILED_DESIGN_TEMPLATE,
    IMPORT_INSTRUCTION,
    IMPORT_INSTRUCTIONS,
    EXCHANGE;

    public static EntityType getSystemType(IntegrationSystem system) {
        return switch (system.getIntegrationSystemType()) {
            case INTERNAL -> INNER_CLOUD_SERVICE;
            case EXTERNAL -> EXTERNAL_SERVICE;
            case IMPLEMENTED -> IMPLEMENTED_SERVICE;
            default -> EXTERNAL_SERVICE;
        };
    }
}

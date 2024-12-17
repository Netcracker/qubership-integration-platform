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

package org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Action log entity type")
public enum EntityType {
    SECURED_VARIABLE,
    COMMON_VARIABLE,
    FOLDER,
    CHAIN,
    SNAPSHOT,
    SNAPSHOT_CLEANUP,
    DEPLOYMENT,
    ELEMENT,
    DOMAIN,
    MASKED_FIELD,
    CHAINS,
    DATABASE_SYSTEM,
    DATABASE_SCRIPT,
    SERVICE_DISCOVERY,
    EXTERNAL_SERVICE,
    INNER_CLOUD_SERVICE,
    IMPLEMENTED_SERVICE,
    ENVIRONMENT,
    SPECIFICATION,
    SPECIFICATION_GROUP,
    SERVICES,
    SECRET,
    CHAIN_RUNTIME_PROPERTIES,
    MAAS_KAFKA,
    MAAS_RABBITMQ,
    IMPORT_INSTRUCTION,
    IMPORT_INSTRUCTIONS
}

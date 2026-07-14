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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.qubership.integration.platform.chain.model.McpService;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil;
import org.qubership.integration.platform.io.readers.migrations.mcp.MCPServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystemLabel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MCPServiceDtoMapper {
    private final URI schemaUri;
    private final List<MCPServiceImportFileMigration> migrations;

    @Autowired
    public MCPServiceDtoMapper(
            @Value("${qip.json.schemas.mcp-service:http://qubership.org/schemas/product/qip/mcp-service}") URI schemaUri,
            List<MCPServiceImportFileMigration> migrations
    ) {
        this.schemaUri = schemaUri;
        this.migrations = migrations;
    }

    public MCPSystem toInternalEntity(McpService mcpService) {
        MCPSystem system = MCPSystem.builder()
                .id(mcpService.getId())
                .name(mcpService.getName())
                .description(mcpService.getDescription())
                .identifier(mcpService.getIdentifier())
                .instructions(mcpService.getInstructions())
                .createdBy(toPersistenceUser(mcpService.getCreatedBy()))
                .createdWhen(mcpService.getCreatedWhen())
                .modifiedBy(toPersistenceUser(mcpService.getModifiedBy()))
                .modifiedWhen(mcpService.getModifiedWhen())
                .build();
        system.setLabels(mcpService.getLabels()
                .stream()
                .map(name -> new MCPSystemLabel(name, system))
                .collect(Collectors.toSet()));
        return system;
    }

    public MCPServiceDto toExternalEntity(MCPSystem system) {
        return MCPServiceDto.builder()
                .id(system.getId())
                .name(system.getName())
                .schema(schemaUri)
                .content(MCPServiceContentDto.builder()
                        .description(system.getDescription())
                        .identifier(system.getIdentifier())
                        .instructions(system.getInstructions())
                        .labels(system.getLabels().stream().map(MCPSystemLabel::getName).toList())
                        .migrations(MigrationUtil.formatVersions(migrations))
                        .build())
                .build();
    }

    private static User toPersistenceUser(
            org.qubership.integration.platform.io.model.exportimport.system.User user
    ) {
        if (user == null) {
            return null;
        }
        return User.builder()
                .id(user.getId())
                .username(user.getUsername())
                .build();
    }
}

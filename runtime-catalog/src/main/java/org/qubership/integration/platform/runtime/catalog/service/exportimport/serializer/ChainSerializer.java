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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.*;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.entity.FolderSerializeEntity;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ImportFileMigrationUtils;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Deprecated(since = "2023.4")
public class ChainSerializer extends StdSerializer<Chain> {

    public ChainSerializer() {
        this(null);
    }

    public ChainSerializer(Class<Chain> t) {
        super(t);
    }

    @Override
    public void serialize(Chain chain, JsonGenerator generator, SerializerProvider serializer) throws IOException {

        try {
            generator.writeStartObject();

            generator.writeStringField(ID, chain.getId());
            if (chain.getName() != null) {
                generator.writeStringField(NAME, chain.getName());
            }
            if (chain.getDescription() != null) {
                generator.writeStringField(DESCRIPTION, chain.getDescription());
            }
            if (chain.getModifiedWhen() != null) {
                generator.writeNumberField(MODIFIED_WHEN, chain.getModifiedWhen().getTime());
            }
            if (chain.getDefaultSwimlane() != null) {
                generator.writeStringField(DEFAULT_SWIMLANE_ID, chain.getDefaultSwimlane().getId());
            }
            if (chain.getReuseSwimlane() != null) {
                generator.writeStringField(REUSE_SWIMLANE_ID, chain.getReuseSwimlane().getId());
            }
            if (chain.getBusinessDescription() != null) {
                generator.writeStringField(BUSINESS_DESCRIPTION, chain.getBusinessDescription());
            }
            if (chain.getAssumptions() != null) {
                generator.writeStringField(ASSUMPTIONS, chain.getAssumptions());
            }
            if (chain.getOutOfScope() != null) {
                generator.writeStringField(OUT_OF_SCOPE, chain.getOutOfScope());
            }

            Set<MaskedField> maskedFields = chain.getMaskedFields();
            if (!CollectionUtils.isEmpty(maskedFields)) {
                generator.writeObjectField(MASKED_FIELDS, maskedFields);
            }
            if (!CollectionUtils.isEmpty(chain.getLabels())) {
                generator.writeArrayFieldStart(LABELS);
                for (ChainLabel label : chain.getLabels().stream().filter(l -> !l.isTechnical()).toList()) {
                    generator.writeString(label.getName());
                }
                generator.writeEndArray();
            }
            if (!CollectionUtils.isEmpty(chain.getElements())) {
                generator.writeObjectField(ELEMENTS, chain.getElements().stream()
                        .filter(element -> element.getParent() == null)
                        .collect(Collectors.toList())
                );
            }

            Set<Dependency> chainDependencies = getDependencies(chain);
            if (!CollectionUtils.isEmpty(chainDependencies)) {
                generator.writeObjectField(DEPENDENCIES, chainDependencies);
            }

            List<DeploymentExternalEntity> chainDeployments = getChainDeployments(chain);
            if (!CollectionUtils.isEmpty(chainDeployments)) {
                generator.writeObjectField(DEPLOYMENTS, chainDeployments);
                generator.writeObjectField(DEPLOY_ACTION, ChainCommitRequestAction.DEPLOY);
            } else {
                generator.writeObjectField(DEPLOY_ACTION, ChainCommitRequestAction.SNAPSHOT);
            }

            FolderSerializeEntity folder = getChainFolder(chain);
            if (folder != null) {
                generator.writeObjectField(FOLDER, folder);
            }

            generator.writeStringField(
                    ImportFileMigration.IMPORT_MIGRATIONS_FIELD,
                    ImportFileMigrationUtils.getActualChainFileMigrationVersions().stream()
                            .sorted()
                            .toList()
                            .toString());

            generator.writeEndObject();
        } catch (IOException e) {
            log.warn("Exception while serializing Chain {}, exception: ", chain.getId(), e);
            throw e;
        }
    }

    private FolderSerializeEntity getChainFolder(FoldableEntity folder) {
        FolderSerializeEntity folderEntity = null;
        while (folder.getParentFolder() != null) {
            folder = folder.getParentFolder();
            folderEntity = new FolderSerializeEntity(folder.getName(), folder.getDescription(), folderEntity);
        }
        return folderEntity;
    }

    private List<DeploymentExternalEntity> getChainDeployments(Chain chain) {
        List<DeploymentExternalEntity> deploymentsForExport = new ArrayList<>();

        chain.getDeployments().forEach(deployment -> deploymentsForExport.add(DeploymentExternalEntity.builder()
                .domain(deployment.getDomain())
                .build()));
        return deploymentsForExport;
    }

    private Set<Dependency> getDependencies(Chain chain) {
        Set<Dependency> dependencies = new LinkedHashSet<>();

        List<ChainElement> allChainElement = chain.getElements();
        for (ChainElement element : allChainElement) {
            dependencies.addAll(element.getInputDependencies());
            dependencies.addAll(element.getOutputDependencies());
        }
        return dependencies;
    }

}

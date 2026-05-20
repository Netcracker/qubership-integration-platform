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

package org.qubership.integration.platform.runtime.catalog.model.system.exportimport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ExportableObjectWriterVisitor;

import java.io.IOException;
import java.util.List;
import java.util.zip.ZipOutputStream;

@Getter
@Setter
public class ExportedSpecification extends ExportedSystemObject {
    private List<ExportedSpecificationSource> specificationSources;

    public ExportedSpecification(String id, ObjectNode objectNode, List<ExportedSpecificationSource> specificationSources) {
        super(id, objectNode);
        this.specificationSources = specificationSources;
    }

    @Override
    public void accept(ExportableObjectWriterVisitor visitor, ZipOutputStream zipOut, String entryPath) throws IOException {
        visitor.visit(this, zipOut, entryPath);
        for (ExportedSpecificationSource exportedSpecificationSource : specificationSources) {
            exportedSpecificationSource.accept(visitor, zipOut, entryPath);
        }
    }
}

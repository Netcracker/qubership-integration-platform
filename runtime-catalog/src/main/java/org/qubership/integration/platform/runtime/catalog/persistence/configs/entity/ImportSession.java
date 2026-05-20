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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.converter.ImportResultConverter;

import java.sql.Timestamp;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity(name = "import_sessions")
public class ImportSession {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    @Convert(converter = ImportResultConverter.class)
    private ImportResult result;
    @Column(name = "completion_percentage")
    private int completion;
    private String error;
    @Column(name = "modified_when")
    private Timestamp modifiedWhen;

    public boolean isDone() {
        return completion == 100;
    }

    @PrePersist
    @PreUpdate
    public void specifyModifiedWhen() {
        modifiedWhen = new Timestamp(System.currentTimeMillis());
    }
}

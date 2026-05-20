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

package org.qubership.integration.platform.variables.management.persistence.configs.entity.exportimport.instructions;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.ImportInstructionAction;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity(name = "import_instructions")
@EntityListeners(AuditingEntityListener.class)
public class ImportInstruction {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    private ImportInstructionAction action;

    @Builder.Default
    @OneToMany(
            mappedBy = "importInstruction",
            orphanRemoval = true,
            cascade = { CascadeType.PERSIST, CascadeType.REMOVE, CascadeType.MERGE }
    )
    private List<ImportInstructionLabel> labels = new ArrayList<>();

    @Column(name = "modified_when")
    @LastModifiedDate
    private Timestamp modifiedWhen;
}

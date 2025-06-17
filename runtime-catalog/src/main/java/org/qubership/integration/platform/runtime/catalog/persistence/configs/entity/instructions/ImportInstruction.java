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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Formula;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportEntityType;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    private ImportEntityType entityType;

    @Enumerated(EnumType.STRING)
    private ImportInstructionAction action;

    @Column(name = "overridden_by_id")
    private String overriddenBy;

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

    @Formula("""
            ( CASE entity_type
                    WHEN 'CHAIN' THEN ( SELECT c.name FROM catalog.chains c WHERE c.id = id )
                    WHEN 'SERVICE' THEN ( SELECT s.name FROM catalog.integration_system s WHERE s.id = id)
                END )""")
    @Basic(fetch = FetchType.LAZY)
    private String entityName;

    @Formula("( SELECT c.name FROM catalog.chains c WHERE c.id = overridden_by_id )")
    @Basic(fetch = FetchType.LAZY)
    private String overriddenByName;

    public ImportInstruction patch(ImportInstruction importInstructionPatch) {
        if (this.id.equals(importInstructionPatch.getId()) && this.entityType == importInstructionPatch.getEntityType()) {
            this.setAction(importInstructionPatch.getAction());
            this.setOverriddenBy(importInstructionPatch.getOverriddenBy());
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ImportInstruction that = (ImportInstruction) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

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
import lombok.experimental.FieldNameConstants;
import lombok.experimental.SuperBuilder;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.service.difference.DifferenceMember;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@FieldNameConstants
public abstract class AbstractEntity implements Serializable, Comparable<AbstractEntity> {

    @Id
    @Builder.Default
    protected String id = UUID.randomUUID().toString();

    @DifferenceMember
    protected String name;

    @DifferenceMember
    protected String description;

    @Column(updatable = false)
    @CreatedDate
    protected Timestamp createdWhen;

    @LastModifiedDate
    protected Timestamp modifiedWhen;

    @CreatedBy
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "created_by_id", updatable = false)),
            @AttributeOverride(name = "username", column = @Column(name = "created_by_name", updatable = false))
    })
    protected User createdBy;

    @LastModifiedBy
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "modified_by_id")),
            @AttributeOverride(name = "username", column = @Column(name = "modified_by_name"))
    })
    protected User modifiedBy;

    protected AbstractEntity(AbstractEntity entity) {
        this();
        this.name = entity.name;
        this.createdWhen = entity.createdWhen;
        this.modifiedWhen = entity.modifiedWhen;
        this.createdBy = entity.createdBy;
        this.modifiedBy = entity.modifiedBy;
        this.description = entity.description;
    }

    public void merge(AbstractEntity entity) {
        this.setName(entity.name);
        this.setDescription(entity.description);
    }

    @PreUpdate
    public void preUpdate() {
        if (this.createdWhen == null) {
            this.createdWhen = this.modifiedWhen;
        }
    }

    @Override
    public int compareTo(@NotNull AbstractEntity abstractEntity) {
        return Comparator.comparing(AbstractEntity::getId)
                .compare(this, abstractEntity);
    }
}

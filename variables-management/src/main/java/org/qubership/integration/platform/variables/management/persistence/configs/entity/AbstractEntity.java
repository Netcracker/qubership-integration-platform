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

package org.qubership.integration.platform.variables.management.persistence.configs.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.user.User;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractEntity {

    @Id
    @Builder.Default
    protected String id = UUID.randomUUID().toString();

    protected String name;

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
        this.id = UUID.randomUUID().toString();
        this.name = entity.name;
        this.createdWhen = entity.createdWhen;
        this.modifiedWhen = entity.modifiedWhen;
        this.createdBy = entity.createdBy;
        this.modifiedBy = entity.modifiedBy;
        this.description = entity.description;
    }

    public void merge(AbstractEntity entity) {
        this.name = entity.name;
        this.description = entity.description;
    }

    @PreUpdate
    public void preUpdate() {
        if (this.createdWhen == null) {
            this.createdWhen = this.modifiedWhen;
        }
    }

    @JsonIgnore
    public boolean isJustCreated() {
        long createdWhen = this.getCreatedWhen() == null ? -1 : this.getCreatedWhen().getTime();
        long modifiedWhen = this.getModifiedWhen() == null ? -1 : this.getModifiedWhen().getTime();
        return createdWhen == modifiedWhen;
    }
}

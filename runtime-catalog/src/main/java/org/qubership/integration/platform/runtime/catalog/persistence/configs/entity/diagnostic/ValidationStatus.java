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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic;

import jakarta.persistence.*;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import javax.annotation.Nullable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "validation_status")
@EntityListeners(AuditingEntityListener.class)
public class ValidationStatus {
    @Id
    private String validationId;

    @Enumerated(EnumType.STRING)
    private ValidationState state;

    @Nullable
    private String message;

    @Column(updatable = false)
    @CreatedDate
    protected Timestamp createdWhen;

    @CreatedBy
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "created_by_id", updatable = false)),
            @AttributeOverride(name = "username", column = @Column(name = "created_by_name", updatable = false))
    })
    protected User createdBy;

    @LastModifiedDate
    protected Timestamp modifiedWhen;

    protected Timestamp startedWhen;

    @LastModifiedBy
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "modified_by_id")),
            @AttributeOverride(name = "username", column = @Column(name = "modified_by_name"))
    })
    protected User modifiedBy;

    public void setState(ValidationState state, String message) {
        this.state = state;
        this.message = message;
    }

    public void setState(ValidationState state) {
        this.state = state;
        this.message = null;
    }
}

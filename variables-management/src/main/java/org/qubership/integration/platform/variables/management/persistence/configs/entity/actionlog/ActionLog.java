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

import jakarta.persistence.*;
import lombok.*;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.user.User;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "logged_actions")
@Table(indexes = @Index(name = "logged_actions_timestamp_idx", columnList = "actionTime"))
public class ActionLog {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private Timestamp actionTime = Timestamp.valueOf(LocalDateTime.now());

    @Enumerated(EnumType.STRING)
    private EntityType entityType;

    private String entityId;

    private String entityName;

    @Enumerated(EnumType.STRING)
    private EntityType parentType;

    private String parentId;

    private String parentName;

    @Enumerated(EnumType.STRING)
    private LogOperation operation;

    private String requestId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "user_id"))
    })
    protected User user;


    public ActionLog(EntityType entityType, String entityId, String entityName, EntityType parentType, String parentId,
                     String parentName, LogOperation operation) {
        this();
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityName = entityName;
        this.parentType = parentType;
        this.parentId = parentId;
        this.parentName = parentName;
        this.operation = operation;
    }

    public User getUser() {
        if (user == null) {
            user = new User();
        }
        return user;
    }
}

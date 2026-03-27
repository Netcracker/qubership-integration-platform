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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.qubership.integration.platform.runtime.catalog.model.chain.LogLoggingLevel;
import org.qubership.integration.platform.runtime.catalog.model.chain.LogPayloadLevel;
import org.qubership.integration.platform.runtime.catalog.model.chain.SessionsLoggingLevelDeprecated;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "deployments")
@EntityListeners(AuditingEntityListener.class)
public class Deployment implements Serializable {

    @Id
    private String id = UUID.randomUUID().toString();

    private String domain;

    private String name;

    @CreatedDate
    private Timestamp createdWhen;

    @CreatedBy
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "created_by_id")),
            @AttributeOverride(name = "username", column = @Column(name = "created_by_name"))
    })
    private User createdBy;

    @Deprecated
    private boolean suspended;


    // TODO remove deprecated fields in future releases
    @Deprecated(since = "23.4", forRemoval = true)
    @Enumerated(EnumType.STRING)
    private SessionsLoggingLevelDeprecated sessionsLoggingLevel = SessionsLoggingLevelDeprecated.NO_REPORTS;
    @Deprecated(since = "23.4", forRemoval = true)
    @Enumerated(EnumType.STRING)
    private LogLoggingLevel logLoggingLevel = LogLoggingLevel.ERROR;
    @Deprecated(since = "23.4", forRemoval = true)
    @Enumerated(EnumType.STRING)
    private LogPayloadLevel logPayloadLevel = LogPayloadLevel.NO_PAYLOAD;
    @Deprecated(since = "23.4", forRemoval = true)
    private Boolean dptEventsEnabled = false;
    @Deprecated(since = "23.4", forRemoval = true)
    private Boolean maskingEnabled = true;


    @OnDelete(action = OnDeleteAction.CASCADE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private Snapshot snapshot;

    @OnDelete(action = OnDeleteAction.CASCADE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chain_id")
    private Chain chain;

    @OnDelete(action = OnDeleteAction.CASCADE)
    @OneToMany(mappedBy = "deployment", fetch = FetchType.LAZY, cascade = { PERSIST, MERGE, REFRESH, DETACH })
    private List<DeploymentRoute> deploymentRoutes = new LinkedList<>();

    public void merge(Deployment deployment) {
        this.suspended = deployment.suspended;
        this.sessionsLoggingLevel = deployment.sessionsLoggingLevel;
    }


    public void setDeploymentRoutes(List<DeploymentRoute> deploymentRoutes) {
        this.deploymentRoutes = deploymentRoutes;
        this.deploymentRoutes.forEach(route -> route.setDeployment(this));
    }
}

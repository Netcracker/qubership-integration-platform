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
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity(name = "deployment_routes")
@ToString
@EntityListeners(AuditingEntityListener.class)
public class DeploymentRoute {
    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    private String path;
    private String gatewayPrefix; // for senders
    private String variableName; // to substitute with resolved path
    @Enumerated(EnumType.STRING)
    private RouteType type;
    @Builder.Default
    private Long connectTimeout = 120000L;

    @OnDelete(action = OnDeleteAction.CASCADE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id")
    @ToString.Exclude
    private Deployment deployment;
}

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

package org.qubership.integration.platform.engine.persistence.shared.entity;

import java.sql.Timestamp;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.*;

import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "checkpoints")
public class Checkpoint {

    @Id
    @Default
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.EAGER)
    private SessionInfo session;

    private String checkpointElementId;

    @OneToMany(orphanRemoval = true, mappedBy = "checkpoint", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<Property> properties = new LinkedList<>();

    @Column(columnDefinition = "TEXT")
    private String headers;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String contextData;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    private byte[] body;

    @Default
    private Timestamp timestamp = Timestamp.from(new Date().toInstant());

    public void assignProperties(List<Property> properties) {
        properties.forEach(property -> property.setCheckpoint(this));
        getProperties().addAll(properties);
    }
}

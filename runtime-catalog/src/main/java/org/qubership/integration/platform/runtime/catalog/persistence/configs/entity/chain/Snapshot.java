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
import lombok.experimental.FieldNameConstants;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Types;
import java.util.*;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "snapshots")
@EntityListeners(AuditingEntityListener.class)
@FieldNameConstants
public class Snapshot extends AbstractEntity {

    @Lob
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "xml_configuration", columnDefinition = "text")
    private String xmlDefinition;

    @OnDelete(action = OnDeleteAction.CASCADE)
    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.MERGE, CascadeType.REFRESH})
    @JoinColumn(name = "chain_id")
    private Chain chain;

    @Builder.Default
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OneToMany(mappedBy = "snapshot", fetch = FetchType.LAZY, cascade = { PERSIST, MERGE, REFRESH, DETACH, REMOVE  })
    private List<Deployment> deployments = new LinkedList<>();

    @Builder.Default
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OneToMany(mappedBy = "snapshot", fetch = FetchType.LAZY, cascade = { PERSIST, MERGE, REFRESH, DETACH, REMOVE })
    @Column(name = "element_id")
    private List<ChainElement> elements = new LinkedList<>();

    @OneToOne(cascade = ALL)
    @JoinColumn(name = "default_swimlane_id")
    private SwimlaneChainElement defaultSwimlane;

    @OneToOne(cascade = ALL)
    @JoinColumn(name = "reuse_swimlane_id")
    private SwimlaneChainElement reuseSwimlane;

    @Builder.Default
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OneToMany(mappedBy = "snapshot", fetch = FetchType.LAZY, cascade = { PERSIST, MERGE, REFRESH, DETACH, REMOVE })
    @Column
    private Set<MaskedField> maskedFields = new LinkedHashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "snapshot",
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<SnapshotLabel> labels = new LinkedHashSet<>();

    public void addDeployment(Deployment deployment) {
        getDeployments().add(deployment);
        deployment.setSnapshot(this);
    }

    public void removeDeployment(Deployment deployment) {
        getDeployments().remove(deployment);
        deployment.setSnapshot(null);
    }

    public void addElement(ChainElement element) {
        getElements().add(element);
        element.setSnapshot(this);
    }

    public void addMaskedField(MaskedField maskedField) {
        getMaskedFields().add(maskedField);
        maskedField.setSnapshot(this);
    }

    public void clearLabels() {
        this.labels.clear();
    }

    public void addLabel(SnapshotLabel label) {
        this.labels.add(label);
    }

    public void addLabels(Collection<SnapshotLabel> labels) {
        this.labels.addAll(labels);
    }

    public Set<Dependency> getDependencies() {
        Set<Dependency> dependencies = new HashSet<>();
        List<ChainElement> allChainElement = getElements();
        for (ChainElement element : allChainElement) {
            dependencies.addAll(element.getInputDependencies());
            dependencies.addAll(element.getOutputDependencies());
        }
        return dependencies;
    }

    @PrePersist
    @PreUpdate
    public void beforeUpdate() {
        if (defaultSwimlane != null) {
            defaultSwimlane.setDefaultSwimlane(true);
        }
        if (reuseSwimlane != null) {
            reuseSwimlane.setReuseSwimlane(true);
        }
    }
}

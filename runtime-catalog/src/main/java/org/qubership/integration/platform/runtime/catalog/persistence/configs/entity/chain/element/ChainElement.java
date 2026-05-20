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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element;

import com.google.common.collect.Maps;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.proxy.HibernateProxy;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.service.difference.DifferenceMember;

import java.util.*;


@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity(name = "elements")
public class ChainElement extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chain_id")
    private Chain chain;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_element_id")
    private ContainerChainElement parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "swimlane_id")
    private SwimlaneChainElement swimlane;

    @Builder.Default
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    @DifferenceMember
    private Map<String, Object> properties = new LinkedHashMap<>();

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private ServiceEnvironment environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private Snapshot snapshot;

    @DifferenceMember
    private String type;

    private String originalId;

    @Builder.Default
    @OrderBy("elementFrom.id")
    @OneToMany(mappedBy = "elementTo", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private List<Dependency> inputDependencies = new LinkedList<>();

    @Builder.Default
    @OrderBy("elementTo.id")
    @OneToMany(mappedBy = "elementFrom", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private List<Dependency> outputDependencies = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "element", fetch = FetchType.LAZY)
    private List<ValidationChainAlert> validationAlerts = new LinkedList<>();

    protected ChainElement(ChainElement element) {
        super(element);
        setDefaultValues();
        this.chain = element.chain;
        this.snapshot = element.snapshot;
        this.properties = Maps.newLinkedHashMap(element.properties);
        this.type = element.type;
        this.originalId = element.id;
        this.environment = element.environment;
        this.swimlane = element.swimlane;
    }

    private void setDefaultValues() {
        this.inputDependencies = new LinkedList<>();
        this.outputDependencies = new LinkedList<>();
    }

    public void addOutputDependency(Dependency dependency) {
        getOutputDependencies().add(dependency);
        dependency.setElementFrom(this);
    }

    public void addInputDependency(Dependency dependency) {
        getInputDependencies().add(dependency);
        dependency.setElementTo(this);
    }

    public ChainElement copy() {
        return new ChainElement(this);
    }

    public ChainElement copyWithoutSnapshot() {
        ChainElement copy = new ChainElement(this);
        copy.setSnapshot(null);
        return copy;
    }

    public ChainElement copyWithOriginalId() {
        ChainElement copy = new ChainElement(this);
        copy.setOriginalId(getOriginalId());
        return copy;
    }

    public Object getProperty(String name) {
        return getProperties().get(name);
    }

    public String getPropertyAsString(String name) {
        return Optional.ofNullable(getProperties().get(name))
            .map(String::valueOf)
            .orElse(null);
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        Class<?> oEffectiveClass = o instanceof HibernateProxy
                ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy
                ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass()
                : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) {
            return false;
        }
        ChainElement that = (ChainElement) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }
}

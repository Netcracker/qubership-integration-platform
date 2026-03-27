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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import com.fasterxml.jackson.annotation.*;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;
import lombok.experimental.SuperBuilder;
import org.apache.commons.collections4.CollectionUtils;
import org.hibernate.proxy.HibernateProxy;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.util.CompareListUtils;

import java.util.*;
import java.util.stream.Collectors;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "models")
@FieldNameConstants
@JsonIgnoreProperties(value = {"specificationSources"}, allowSetters = true)
public class SystemModel extends AbstractSystemEntity {

    @Column
    private boolean deprecated;

    @Column
    private String version; // TODO version == name

    @Column
    @Enumerated(EnumType.STRING)
    private SystemModelSource source;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "compiled_library_id")
    private CompiledLibrary compiledLibrary;

    @Builder.Default
    @JsonManagedReference
    @OrderBy("id")
    @OneToMany(mappedBy = "systemModel", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnoreProperties({"modifiedWhen"})
    private List<Operation> operations = new LinkedList<>();

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    @JsonIdentityReference(alwaysAsId = true)
    @JsonProperty("parentId")
    @ManyToOne
    @JoinColumn(name = "specification_group_id")
    private SpecificationGroup specificationGroup;

    @Builder.Default
    @JsonManagedReference
    @OneToMany(mappedBy = "systemModel", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnoreProperties({"modifiedWhen"})
    private List<SpecificationSource> specificationSources = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "specification",
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<SystemModelLabel> labels = new LinkedHashSet<>();

    @Transient
    private List<Chain> chains;

    public void addProvidedOperation(Operation operation) {
        getOperations().add(operation);
        operation.setSystemModel(this);
    }

    public void removeOperation(Operation operation) {
        getOperations().remove(operation);
        operation.setSystemModel(null);
    }

    public void addProvidedSpecificationSource(SpecificationSource specificationSource) {
        getSpecificationSources().add(specificationSource);
        specificationSource.setSystemModel(this);
    }

    public void removeSpecificationSource(SpecificationSource specificationSource) {
        getSpecificationSources().remove(specificationSource);
        specificationSource.setSystemModel(null);
    }

    public void addLabel(SystemModelLabel label) {
        this.labels.add(label);
    }

    public void addLabels(Collection<SystemModelLabel> labels) {
        this.labels.addAll(labels);
    }

    @JsonGetter("labels")
    public Set<SystemModelLabel> getNonTechnicalLabels() {
        if (CollectionUtils.isNotEmpty(labels)) {
            return labels.stream().filter(l -> !l.isTechnical()).collect(Collectors.toSet());
        }
        return labels;
    }

    @JsonSetter("labels")
    public void setNonTechnicalLabels(Set<String> newLabels) {
        if (CollectionUtils.isNotEmpty(newLabels)) {
            if (labels == null) {
                labels = new HashSet<>();
            }
            labels.addAll(newLabels.stream().map(labelName ->
                    SystemModelLabel.builder()
                            .specification(this)
                            .name(labelName)
                            .build()
            ).collect(Collectors.toSet()));
        }
    }

    public void setLabels(Set<SystemModelLabel> labels) {
        if (this.labels == null) {
            this.labels = new HashSet<>();
        }
        this.labels.clear();
        this.labels.addAll(labels);
    }

    @Override
    public boolean equals(Object object) {
        return equals(object, true);
    }

    public boolean equals(Object o, boolean strict) {
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

        SystemModel that = (SystemModel) o;
        return super.equals(o, strict)
                && this.isDeprecated() == that.isDeprecated()
                && isEqualsSourceType(that.getSource(), strict)
                && isSourcesEquals(that.getSpecificationSources(), strict);
    }

    public boolean isSourcesEquals(List<SpecificationSource> sources, boolean strict) {
        return CompareListUtils.listEquals(this.getSpecificationSources(), sources, strict);
    }

    private boolean isEqualsSourceType(SystemModelSource newSource, boolean strict) {
        SystemModelSource source = this.getSource();
        if (source == newSource) {
            return true;
        }
        if (!strict) {
            return (source == SystemModelSource.DISCOVERED && newSource == SystemModelSource.MANUAL)
                    || (newSource == SystemModelSource.DISCOVERED && source == SystemModelSource.MANUAL);
        }
        return false;
    }
}

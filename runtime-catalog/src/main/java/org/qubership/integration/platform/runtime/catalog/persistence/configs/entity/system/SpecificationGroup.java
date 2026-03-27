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
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.proxy.HibernateProxy;
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
@JsonIgnoreProperties(value = {"systemModels"}, allowSetters = true)
@FieldNameConstants
public class SpecificationGroup extends AbstractSystemEntity {

    @Column
    private String url;

    @Column
    private boolean synchronization;

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    @JsonIdentityReference(alwaysAsId = true)
    @JsonProperty("parentId")
    @ManyToOne
    @JoinColumn(name = "system_id")
    private IntegrationSystem system;

    @OrderBy("id")
    @OneToMany(mappedBy = "specificationGroup", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnoreProperties({"modifiedWhen"})
    private List<SystemModel> systemModels;

    @Builder.Default
    @OneToMany(mappedBy = "specificationGroup",
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<SpecificationGroupLabel> labels = new LinkedHashSet<>();

    @Transient
    private List<Chain> chains;

    public void addSystemModel(SystemModel systemModel) {
        if (systemModels == null) {
            systemModels = new ArrayList<>();
        }
        systemModels.add(systemModel);
        systemModel.setSpecificationGroup(this);
    }

    public void removeSystemModel(SystemModel systemModel) {
        systemModels.remove(systemModel);
        systemModel.setSpecificationGroup(null);
    }

    public void addLabel(SpecificationGroupLabel label) {
        this.labels.add(label);
    }

    public void addLabels(Collection<SpecificationGroupLabel> labels) {
        this.labels.addAll(labels);
    }

    @JsonGetter("labels")
    public Set<SpecificationGroupLabel> getNonTechnicalLabels() {
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
                    SpecificationGroupLabel.builder()
                            .specificationGroup(this)
                            .name(labelName)
                            .build()
            ).collect(Collectors.toSet()));
        }
    }

    public void setLabels(Set<SpecificationGroupLabel> labels) {
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

        SpecificationGroup that = (SpecificationGroup) o;
        return super.equals(o, strict)
                && StringUtils.equals(this.getUrl(), that.getUrl())
                && isSystemModelEquals(that.getSystemModels(), strict);
    }

    private boolean isSystemModelEquals(List<SystemModel> newSystemModels, boolean strict) {
        return CompareListUtils.listEquals(this.getSystemModels(), newSystemModels, strict);
    }
}

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

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonSetter;
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
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.util.CompareListUtils;

import java.util.*;
import java.util.stream.Collectors;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(value = {"specificationGroups"}, allowSetters = true)
@FieldNameConstants
public class IntegrationSystem extends AbstractSystemEntity {

    @Column
    private String activeEnvironmentId;

    @Column
    @Enumerated(EnumType.STRING)
    private IntegrationSystemType integrationSystemType;

    @Column
    private String internalServiceName;

    @Column
    @Enumerated(EnumType.STRING)
    private OperationProtocol protocol;

    @Builder.Default
    @JsonManagedReference
    @OrderBy("id")
    @OneToMany(mappedBy = "system", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnoreProperties({"modifiedWhen"})
    private List<Environment> environments = new LinkedList<>();

    @Builder.Default
    @OrderBy("id")
    @OneToMany(mappedBy = "system", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnoreProperties({"modifiedWhen"})
    private List<SpecificationGroup> specificationGroups = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "system",
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<IntegrationSystemLabel> labels = new LinkedHashSet<>();

    public IntegrationSystem(String id) {
        this();
        this.id = id;
    }

    public void addEnvironment(Environment environment) {
        getEnvironments().add(environment);
        environment.setSystem(this);
    }

    public void removeEnvironment(Environment environment) {
        getEnvironments().remove(environment);
        environment.setSystem(null);
        if (environment.getId().equals(activeEnvironmentId)) {
            activeEnvironmentId = null;
        }
    }

    public void addSpecificationGroup(SpecificationGroup specificationGroup) {
        getSpecificationGroups().add(specificationGroup);
        specificationGroup.setSystem(this);
    }

    public void removeSpecificationGroup(SpecificationGroup specificationGroup) {
        getSpecificationGroups().remove(specificationGroup);
        specificationGroup.setSystem(null);
    }

    public void addLabel(IntegrationSystemLabel label) {
        this.labels.add(label);
    }

    public void addLabels(Collection<IntegrationSystemLabel> labels) {
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

        IntegrationSystem that = (IntegrationSystem) o;
        return super.equals(o, strict)
                && StringUtils.equals(this.getInternalServiceName(), that.getInternalServiceName())
                && isLabelsEquals(that.getLabels(), strict)
                && isEnvironmentEquals(that.getEnvironments(), strict)
                && isSpecificationGroupEquals(that.getSpecificationGroups(), strict);
    }

    private boolean isLabelsEquals(Set<IntegrationSystemLabel> newLabels, boolean strict) {
        return CompareListUtils.listEquals(this.getLabels(), newLabels, strict);
    }

    private boolean isEnvironmentEquals(List<Environment> newEnvironments, boolean strict) {
        return CompareListUtils.listEquals(this.getEnvironments(), newEnvironments, strict);
    }

    private boolean isSpecificationGroupEquals(List<SpecificationGroup> newSpecificationGroups, boolean strict) {
        return CompareListUtils.listEquals(this.getSpecificationGroups(), newSpecificationGroups, strict);
    }

    @JsonGetter("labels")
    public Set<IntegrationSystemLabel> getNonTechnicalLabels() {
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
                    IntegrationSystemLabel.builder()
                            .system(this)
                            .name(labelName)
                            .build()
            ).collect(Collectors.toSet()));
        }
    }

    public void setLabels(Set<IntegrationSystemLabel> labels) {
        if (this.labels == null) {
            this.labels = new HashSet<>();
        }
        this.labels.clear();
        this.labels.addAll(labels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), getActiveEnvironmentId(), getIntegrationSystemType(),
                getInternalServiceName(), getProtocol(), getEnvironments(), getSpecificationGroups());
    }
}

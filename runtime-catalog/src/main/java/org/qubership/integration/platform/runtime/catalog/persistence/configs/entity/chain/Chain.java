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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;

import java.util.*;
import java.util.stream.Collectors;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@Slf4j
@SuperBuilder
@NoArgsConstructor
@Entity(name = "chains")
public class Chain extends FoldableEntity {

    @Builder.Default
    @OrderBy("id")
    @OneToMany(mappedBy = "chain",
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    private List<ChainElement> elements = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "chain", cascade = {PERSIST, MERGE, REFRESH, DETACH, REMOVE}, fetch = FetchType.LAZY)
    private List<Snapshot> snapshots = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "chain", cascade = {PERSIST, MERGE, REFRESH, DETACH, REMOVE}, fetch = FetchType.LAZY)
    private List<Deployment> deployments = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "chain", fetch = FetchType.LAZY)
    private List<ValidationChainAlert> validationAlerts = new LinkedList<>();

    @OneToOne()
    @JoinColumn(name = "default_swimlane_id")
    private SwimlaneChainElement defaultSwimlane;

    @OneToOne()
    @JoinColumn(name = "reuse_swimlane_id")
    private SwimlaneChainElement reuseSwimlane;

    @Builder.Default
    @OrderBy("name")
    @OneToMany(mappedBy = "chain")
    private Set<MaskedField> maskedFields = new LinkedHashSet<>();

    @Builder.Default
    @OneToMany(
            mappedBy = "chain",
            fetch = FetchType.LAZY,
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<ChainLabel> labels = new LinkedHashSet<>();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_snapshot_id")
    private Snapshot currentSnapshot;

    @Column(name = "overridden_by_chain")
    private String overriddenByChainId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "overridden_by_chain", insertable = false, updatable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Chain overriddenByChain;

    @Column(name = "overrides_chain")
    private String overridesChainId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "overrides_chain", insertable = false, updatable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Chain overridesChain;

    private boolean unsavedChanges;

    private String businessDescription;
    private String assumptions;
    private String outOfScope;

    @Column(name = "last_import_hash")
    private String lastImportHash;

    /**
     * Constructor is used to provide chain copy for building camel xml configuration
     */
    public Chain(Chain chain) {
        super(chain);
        setDefaultValues();
    }

    public Chain(String id) {
        this();
        setDefaultValues();
        this.id = id;
    }

    private void setDefaultValues() {
        this.elements = new LinkedList<>();
        this.snapshots = new LinkedList<>();
        this.deployments = new LinkedList<>();
        this.maskedFields = new LinkedHashSet<>(0);
        this.labels = new LinkedHashSet<>(0);
    }

    public void addDeployment(Deployment deployment) {
        getDeployments().add(deployment);
        deployment.setChain(this);
    }

    public void addElements(Collection<ChainElement> elements) {
        elements.forEach(this::addElement);
    }

    public void addElement(ChainElement element) {
        getElements().add(element);
        element.setChain(this);
    }

    public void addElementsHierarchy(Collection<ChainElement> elements) {
        elements.forEach(this::addElementHierarchy);
    }

    public void addElementHierarchy(ChainElement element) {
        addElement(element);
        if (element instanceof ContainerChainElement containerElement) {
            addElementsHierarchy(containerElement.getElements());
        }
    }

    public void clearMaskedFields() {
        this.maskedFields.clear();
    }

    public void addMaskedField(MaskedField maskedField) {
        getMaskedFields().add(maskedField);
        maskedField.setChain(this);
    }

    public void addMaskedFields(Collection<MaskedField> maskedFields) {
        this.maskedFields.addAll(maskedFields);
    }

    public void setDefaultSwimlane(SwimlaneChainElement defaultSwimlane) {
        if (defaultSwimlane != null) {
            defaultSwimlane.setChain(this);
        }
        this.defaultSwimlane = defaultSwimlane;
    }

    public void setReuseSwimlane(SwimlaneChainElement reuseSwimlane) {
        if (reuseSwimlane != null) {
            reuseSwimlane.setChain(this);
        }
        this.reuseSwimlane = reuseSwimlane;
    }

    public List<ChainElement> getRootElements() {
        return getElements().stream()
            .filter(element -> element.getParent() == null)
            .collect(Collectors.toList());
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

    public void removeElements(Collection<ChainElement> chainElements) {
        chainElements.forEach(this::removeElement);
    }

    public void removeElement(ChainElement chainElement) {
        getElements().remove(chainElement);
        chainElement.setChain(null);
    }

    public void clearLabels() {
        this.labels.clear();
    }

    public void addLabel(ChainLabel label) {
        this.labels.add(label);
    }

    public void addLabels(Collection<ChainLabel> labels) {
        this.labels.addAll(labels);
    }

    // May not be executed if there are no changes to this entity
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

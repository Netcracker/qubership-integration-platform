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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context;

import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static jakarta.persistence.CascadeType.MERGE;
import static jakarta.persistence.CascadeType.PERSIST;
import static jakarta.persistence.CascadeType.REMOVE;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@FieldNameConstants
public class ContextSystem extends AbstractSystemEntity {

    @Builder.Default
    @OneToMany(mappedBy = "system",
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<ContextSystemLabel> labels = new LinkedHashSet<>();

    @Override
    public boolean equals(Object object) {
        return equals(object, true);
    }

    @Transient
    private List<Chain> chains;

    public void addLabels(Collection<ContextSystemLabel> labels) {
        this.labels.addAll(labels);
    }
}


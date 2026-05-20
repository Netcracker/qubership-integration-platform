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
import lombok.experimental.SuperBuilder;
import org.hibernate.proxy.HibernateProxy;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@ToString
@NoArgsConstructor
@SuperBuilder
@Entity(name = "dependencies")
public class Dependency implements Serializable, Comparable<Dependency> {

    @Id
    @Builder.Default
    @Column(name = "dependency_id")
    private String id = UUID.randomUUID().toString();

    @ManyToOne
    @JoinColumn(name = "element_from_id")
    private ChainElement elementFrom;

    @ManyToOne
    @JoinColumn(name = "element_to_id")
    private ChainElement elementTo;

    public static Dependency of(ChainElement elementFrom, ChainElement elementTo) {
        return new Dependency(elementFrom, elementTo);
    }

    private Dependency(ChainElement elementFrom, ChainElement elementTo) {
        this();
        this.elementFrom = elementFrom;
        this.elementTo = elementTo;
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
        Dependency that = (Dependency) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy
                ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode()
                : getClass().hashCode();
    }

    @Override
    public int compareTo(Dependency dependency) {
     return Comparator.comparing((Dependency d) -> d.getId())
                .thenComparing((Dependency d) -> d.getElementFrom().getId())
                .thenComparing((Dependency d) -> d.getElementTo().getId())
                .compare(this, dependency);
    }
}

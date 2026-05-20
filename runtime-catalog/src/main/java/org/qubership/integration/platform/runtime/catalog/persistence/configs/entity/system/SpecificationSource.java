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

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.proxy.HibernateProxy;
import org.jetbrains.annotations.Nullable;
import org.qubership.integration.platform.runtime.catalog.util.HashUtils;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(value = {"source"}, allowSetters = true)
@Table(name = "specification_source")
@FieldNameConstants
public class SpecificationSource extends AbstractSystemEntity {

    @JsonBackReference
    @ManyToOne
    @JoinColumn(name = "model_id")
    private SystemModel systemModel;

    @Column(name = "main")
    private boolean isMainSource;
    @Column(columnDefinition = "TEXT")
    private String source;

    /**
     * It is necessary to provide/calculate the hash when setting source
     */
    @Column()
    private String sourceHash;

    public void setSource(String source) {
        this.source = source;
        this.setSourceHash(buildHash(source));
    }

    @Nullable
    private static String buildHash(String source) {
        return HashUtils.sha256hex(source);
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

        SpecificationSource that = (SpecificationSource) o;
        return super.equals(o, strict)
                && StringUtils.equals(getSourceHash(), that.getSourceHash());
    }

    public abstract static class SpecificationSourceBuilder<C extends SpecificationSource, B extends SpecificationSourceBuilder<C, B>> extends AbstractSystemEntityBuilder<C, B> {

        public B source(String source) {
            this.source = source;
            this.sourceHash(buildHash(source));
            return this.self();
        }
    }
}

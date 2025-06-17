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
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.proxy.HibernateProxy;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.util.CompareListUtils;

import java.util.List;
import java.util.Objects;

@Getter
@Setter
@ToString
@Entity
@SuperBuilder
@NoArgsConstructor
@Table(name = "environment")
@AllArgsConstructor
public class Environment extends AbstractSystemEntity {

    @Column
    private String address;

    @Builder.Default
    @Column
    @Enumerated(EnumType.STRING)
    private EnvironmentSourceType sourceType = EnvironmentSourceType.MANUAL;

    @Column
    @ElementCollection
    @Enumerated(EnumType.STRING)
    private List<EnvironmentLabel> labels;

    @JsonBackReference
    @ManyToOne
    @JoinColumn(name = "system_id")
    private IntegrationSystem system;

    @Column
    @Deprecated
    private String maasInstanceId;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode properties;

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

        Environment that = (Environment) o;
        return super.equals(o, strict) && Objects.equals(this.getAddress(), that.getAddress())
                && this.getSourceType() == that.getSourceType()
                && isLabelEquals(that.getLabels())
                && Objects.equals(this.getProperties(), that.getProperties());
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Objects.hash(this.getAddress(), this.getSourceType(), this.getLabels(), this.getProperties());
    }

    private boolean isLabelEquals(List<EnvironmentLabel> newLabels) {
        return CompareListUtils.listEquals(this.getLabels(), newLabels);
    }
}

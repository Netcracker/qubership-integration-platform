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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.proxy.HibernateProxy;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "operations")
public class Operation extends AbstractSystemEntity {

    @Column(nullable = false)
    private String method;

    @Column(nullable = false)
    private String path;

    // Source of truth for the protocol-specific operation data; method and path are derived from it.
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private TypedOperation typed;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode specification;

    @JsonBackReference
    @ManyToOne
    @JoinColumn(name = "model_id")
    private SystemModel systemModel;

    // Rebuilt on demand from the raw specification source by OperationSchemaExtractor; no longer persisted.
    @Transient
    private Map<String, JsonNode> requestSchema;

    @Transient
    private Map<String, JsonNode> responseSchemas;

    @Transient
    private List<Chain> chains;

    // Flat views over the typed payload for name-based DTO mapping. TypedOperation defaults each accessor to null,
    // so a protocol that has no such field needs no branch here.
    //
    // No path serializes this entity today — export goes through ApiOperationDto and the REST surface through the
    // MapStruct DTOs — but the entity graph is still wired for Jackson (@JsonBackReference here, @JsonManagedReference
    // on SystemModel.operations), and none of these nine views belongs in a serialized operation: they are already
    // carried by method, path and typed. @JsonIgnore keeps them out of a graph no test pins the shape of.
    @JsonIgnore
    public String getOperationKind() {
        if (typed == null) {
            return null;
        }
        JsonTypeName typeName = typed.getClass().getAnnotation(JsonTypeName.class);
        return typeName == null ? null : typeName.value();
    }

    @JsonIgnore
    public String getChannel() {
        return typed == null ? null : typed.channel();
    }

    @JsonIgnore
    public String getSummary() {
        return typed == null ? null : typed.summary();
    }

    @JsonIgnore
    public Boolean getIsDeprecated() {
        return typed == null ? null : typed.deprecated();
    }

    @JsonIgnore
    public String getOperationType() {
        return typed == null ? null : typed.operationType();
    }

    @JsonIgnore
    public String getBinding() {
        return typed == null ? null : typed.binding();
    }

    @JsonIgnore
    public String getRpcMethod() {
        return typed == null ? null : typed.rpcMethod();
    }

    @JsonIgnore
    public String getPackage() {
        return typed == null ? null : typed.packageName();
    }

    @JsonIgnore
    public String getService() {
        return typed == null ? null : typed.service();
    }

    // Hand-written to suppress the Lombok setter: OperationSchemaExtractor matches unpersisted
    // operations by getPath() / getMethod(), so the derived columns must be current before persistence.
    public void setTyped(TypedOperation typed) {
        this.typed = typed;
        deriveMethodAndPath();
    }

    // Safety net for the @SuperBuilder / @AllArgsConstructor paths, which write typed directly and
    // skip setTyped. A distinct name keeps AbstractEntity's @PreUpdate audit callback from being
    // overridden. A null typed leaves method and path as the old-archive import carried them.
    // A derived null is never written over a good column either: an incompletely backfilled typed
    // (e.g. a graphql row whose sdl was absent) must not null a path the engine still resolves by.
    @PrePersist
    @PreUpdate
    public void deriveMethodAndPath() {
        if (typed != null) {
            String derivedMethod = typed.deriveMethod();
            String derivedPath = typed.derivePath();
            if (derivedMethod != null) {
                method = derivedMethod;
            }
            if (derivedPath != null) {
                path = derivedPath;
            }
        }
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
        Operation operation = (Operation) o;
        return getId() != null && Objects.equals(getId(), operation.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy
                ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode()
                : getClass().hashCode();
    }
}

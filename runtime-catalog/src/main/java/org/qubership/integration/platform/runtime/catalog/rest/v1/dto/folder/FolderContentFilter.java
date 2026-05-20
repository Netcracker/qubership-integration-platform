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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.util.TriggerUtils;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Data
@Schema(description = "Content filter object for a folder item request")
public class FolderContentFilter {
    @Schema(description = "Whether to show only chains with http trigger in them")
    private boolean chainsWithHttpTriggers;
    @Schema(description = "Whether to show only chains with at least one external service used")
    private boolean externalRoutesOnly;

    public boolean isEmpty() {
        return !chainsWithHttpTriggers;
    }

    public Specification<Chain> getSpecification() {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);
            List<Predicate> predicates = new ArrayList<>();
            Join<Chain, ChainElement> elementJoin = root.join("elements", JoinType.INNER);
            if (chainsWithHttpTriggers) {
                Collection<String> elementTypes = Collections.singletonList(TriggerUtils.getHttpTriggerTypeName());
                predicates.add(elementJoin.get("type").in(elementTypes));
                if (externalRoutesOnly) {
                    predicates.add(
                            criteriaBuilder.equal(
                                    criteriaBuilder
                                            .function(
                                                    "jsonb_extract_path_text",
                                                    String.class, elementJoin.get("properties"),
                                                    criteriaBuilder.literal("externalRoute")
                                            ),
                                    "true")
                    );
                }
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}

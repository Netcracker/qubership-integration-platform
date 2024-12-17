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

package org.qubership.integration.platform.engine.opensearch.ism.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import lombok.*;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
@ToString
public class PolicyResponse {
    public static final long UNASSIGNED_SEQ_NO = -2L;
    public static final long UNASSIGNED_PRIMARY_TERM = 0L;

    @Builder.Default
    @JsonProperty("_id")
    private String id = "";

    @Builder.Default
    @JsonProperty("_seq_no")
    private Long seqNo = UNASSIGNED_SEQ_NO;

    @Builder.Default
    @JsonProperty("_primary_term")
    private Long primaryTerm = UNASSIGNED_PRIMARY_TERM;

    @JsonProperty("_version")
    private Long version;

    @JsonProperty
    private Policy policy;
}

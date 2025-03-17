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

package org.qubership.integration.platform.engine.model.opensearch;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchField;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class AbstractElementElastic {

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String id;

    @OpenSearchField(type = OpenSearchFieldType.Date)
    private String started;

    @OpenSearchField(type = OpenSearchFieldType.Date)
    private String finished;

    private long duration;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private ExecutionStatus executionStatus;

}

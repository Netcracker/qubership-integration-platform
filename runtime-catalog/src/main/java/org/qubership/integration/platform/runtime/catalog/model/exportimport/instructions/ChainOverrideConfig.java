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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.validation.constraint.ChainNotOverrideItself;
import org.qubership.integration.platform.runtime.catalog.validation.constraint.NotStartOrEndWithSpace;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@ChainNotOverrideItself
public class ChainOverrideConfig {

    @NotStartOrEndWithSpace(message = "must not be empty and must not start or end with a space")
    private String id;
    @NotStartOrEndWithSpace(message = "must not be empty and must not start or end with a space")
    private String overriddenBy;
}

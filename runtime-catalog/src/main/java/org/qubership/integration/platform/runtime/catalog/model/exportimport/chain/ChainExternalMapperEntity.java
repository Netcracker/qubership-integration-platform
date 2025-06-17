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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.chain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.util.ChainUtils;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ChainExternalMapperEntity {

    @NonNull
    private ChainExternalEntity chainExternalEntity;
    @Nullable
    private Chain existingChain;
    @Nullable
    private Folder existingFolder;
    @Nullable
    private File chainFilesDirectory;
    @Builder.Default
    private Map<String, byte[]> elementPropertyFiles = new HashMap<>();
    @Nullable
    private Function<Chain, Chain> actionBeforeDependencyMapping;

    @Nullable
    public Chain getExistingChain() {
        return ChainUtils.getChainCopy(existingChain);
    }
}

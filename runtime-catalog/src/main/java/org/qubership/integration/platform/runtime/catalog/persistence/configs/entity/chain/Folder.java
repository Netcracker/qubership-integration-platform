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

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.LinkedList;
import java.util.List;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity(name = "folders")
public class Folder extends FoldableEntity {

    @Builder.Default
    @OneToMany(mappedBy = "parentFolder", fetch = FetchType.LAZY, cascade = { PERSIST, MERGE, REFRESH, DETACH })
    private List<Folder> folderList = new LinkedList<>();

    @Builder.Default
    @OneToMany(mappedBy = "parentFolder", fetch = FetchType.LAZY, cascade = { PERSIST, MERGE, REFRESH, DETACH })
    private List<Chain> chainList = new LinkedList<>();

    public void addChildFolder(Folder folder) {
        if (this.folderList == null) {
            this.folderList = new LinkedList<>();
        }
        this.folderList.add(folder);
        folder.setParentFolder(this);
    }

    public void addChildChain(Chain chain) {
        this.chainList.add(chain);
        chain.setParentFolder(this);
    }

    public void removeChildChain(Chain chain) {
        this.chainList.remove(chain);
        chain.setParentFolder(null);
    }

}

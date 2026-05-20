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

package org.qubership.integration.platform.runtime.catalog.model;

import lombok.Data;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Data
public class ChainRoute {

    private String id;
    private List<ChainElement> elements;
    private List<ChainRoute> nextRoutes;

    public ChainRoute() {
        this.id = UUID.randomUUID().toString();
        this.elements = new LinkedList<>();
        this.nextRoutes = new LinkedList<>();
    }


    public ChainRoute(String id) {
        this.id = id;
        this.elements = new LinkedList<>();
        this.nextRoutes = new LinkedList<>();
    }
}

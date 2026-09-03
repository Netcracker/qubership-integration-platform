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

package org.qubership.integration.platform.camelk.model;

import lombok.Getter;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;

import java.util.*;

@Getter
public class ResourceBuildContext<T> {
    private static final IntegrationServiceCatalog DEFAULT_SERVICE_CATALOG = new IntegrationServiceCatalog() {
        @Override
        public Optional<IntegrationService> findById(String id) {
            return Optional.empty();
        }

        @Override
        public Collection<IntegrationService> findAllByIds(Collection<String> ids) {
            return List.of();
        }
    };

    private final BuildInfo buildInfo;
    private final Map<String, Object> buildCache;
    private final IntegrationServiceCatalog serviceCatalog;
    private final T data;


    public static ResourceBuildContext<Void> create(BuildInfo buildInfo) {
        return new ResourceBuildContext<>(buildInfo, new HashMap<>(), DEFAULT_SERVICE_CATALOG, null);
    }

    public static ResourceBuildContext<Void> create(BuildInfo buildInfo, IntegrationServiceCatalog serviceCatalog) {
        return new ResourceBuildContext<>(buildInfo, new HashMap<>(), serviceCatalog, null);
    }

    public <U> ResourceBuildContext<U> updateTo(U data) {
        return new ResourceBuildContext<>(buildInfo, buildCache, serviceCatalog, data);
    }

    private ResourceBuildContext(
            BuildInfo buildInfo,
            Map<String, Object> buildCache,
            IntegrationServiceCatalog serviceCatalog,
            T data
    ) {
        this.buildInfo = buildInfo;
        this.buildCache = buildCache;
        this.serviceCatalog = serviceCatalog;
        this.data = data;
    }
}

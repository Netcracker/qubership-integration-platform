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

package org.qubership.integration.platform.engine.opensearch;

import org.apache.commons.lang3.StringUtils;
import org.opensearch.client.opensearch.OpenSearchClient;

public class DefaultOpenSearchClientSupplier implements OpenSearchClientSupplier {
    private final OpenSearchClient client;
    private final String prefix;

    public DefaultOpenSearchClientSupplier(
        OpenSearchClient client,
        String prefix
    ) {
        this.client = client;
        this.prefix = prefix;
    }

    @Override
    public OpenSearchClient getClient() {
        return client;
    }

    @Override
    public String normalize(String name) {
        return StringUtils.isEmpty(prefix) ? name : (prefix + "_" + name);
    }
}

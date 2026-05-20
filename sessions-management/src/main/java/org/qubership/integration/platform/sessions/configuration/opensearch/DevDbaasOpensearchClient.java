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

package org.qubership.integration.platform.sessions.configuration.opensearch;

import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.opensearch.client.opensearch.OpenSearchClient;

public class DevDbaasOpensearchClient implements DbaasOpensearchClient {
    private static final String PREFIX = "dev";

    private OpenSearchClient client;

    private DevDbaasOpensearchClient() {}

    public DevDbaasOpensearchClient(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public OpenSearchClient getClient() {
        return client;
    }

    @Override
    public OpenSearchClient getClient(DatabaseConfig databaseConfig) {
        return getClient();
    }

    @Override
    public String getPrefix() {
        return PREFIX;
    }

    @Override
    public String normalize(String name) {
        return PREFIX + "_" + name;
    }

    @Override
    public String normalize(DatabaseConfig databaseConfig, String name) {
        return normalize(name);
    }
}


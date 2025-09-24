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

package org.qubership.integration.platform.engine.configuration.opensearch;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.qubership.integration.platform.engine.opensearch.DefaultOpenSearchClientSupplier;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;

@Slf4j
@ApplicationScoped
public class OpenSearchClientSupplierProducer {
    @Inject
    OpenSearchProperties properties;

    @Produces
    @DefaultBean
    @ApplicationScoped
    public OpenSearchClientSupplier openSearchClientSupplier(
            OpenSearchInitializer openSearchInitializer
    ) {
        OpenSearchClientSupplier clientSupplier = new DefaultOpenSearchClientSupplier(
                createOpenSearchClient(),
                properties.client().prefix().orElse("")
        );
        openSearchInitializer.initialize(clientSupplier);
        return clientSupplier;
    }

    private OpenSearchClient createOpenSearchClient() {
        AuthScope authScope = new AuthScope(null, null, -1, null, null);
        Credentials credentials = new UsernamePasswordCredentials(
                properties.client().userName().orElse(""),
                properties.client().password().orElse("").toCharArray());
        HttpHost httpHost = new HttpHost(
                properties.client().protocol(),
                properties.client().host(),
                properties.client().port());
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(authScope, credentials);
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder
            .builder(httpHost)
            .setHttpClientConfigCallback(httpClientBuilder ->
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        return new OpenSearchClient(builder.build());
    }
}

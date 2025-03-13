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

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.qubership.integration.platform.sessions.opensearch.DefaultOpenSearchClientSupplier;
import org.qubership.integration.platform.sessions.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.sessions.properties.opensearch.ClientProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(OpenSearchProperties.class)
public class OpenSearchAutoConfiguration {
    private OpenSearchClient createOpenSearchClient(ClientProperties properties) {
        AuthScope authScope = new AuthScope(null, null, -1, null, null);
        Credentials credentials = new UsernamePasswordCredentials(properties.username(), properties.password().toCharArray());
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(authScope, credentials);
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder
                .builder(new HttpHost(properties.protocol(), properties.host(), properties.port()))
                .setHttpClientConfigCallback(httpClientBuilder ->
                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        return new OpenSearchClient(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean(OpenSearchClientSupplier.class)
    public OpenSearchClientSupplier openSearchClientSupplier(OpenSearchProperties properties) {
        OpenSearchClient client = createOpenSearchClient(properties.client());
        return new DefaultOpenSearchClientSupplier(client, properties.index().prefix());
    }
}

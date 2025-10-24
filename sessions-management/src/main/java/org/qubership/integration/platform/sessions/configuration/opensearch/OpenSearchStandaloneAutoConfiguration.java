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

import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.qubership.integration.platform.sessions.properties.opensearch.ClientProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static com.netcracker.cloud.dbaas.client.opensearch.config.DbaasOpensearchConfiguration.TENANT_NATIVE_OPENSEARCH_CLIENT;

@Configuration
@EnableConfigurationProperties(OpenSearchProperties.class)
@ConditionalOnProperty(name = "${qip.datasource.configuration.enabled}", havingValue = "false", matchIfMissing = true)
public class OpenSearchStandaloneAutoConfiguration {

    @Primary
    @Bean(TENANT_NATIVE_OPENSEARCH_CLIENT)
    public DbaasOpensearchClient createOpenSearchClient(OpenSearchProperties properties) {
        ClientProperties clientProperties = properties.client();
        AuthScope authScope = new AuthScope(null, null, -1, null, null);
        Credentials credentials = new UsernamePasswordCredentials(clientProperties.username(), clientProperties.password().toCharArray());
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(authScope, credentials);
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder
                .builder(new HttpHost(clientProperties.protocol(), clientProperties.host(), clientProperties.port()))
                .setHttpClientConfigCallback(httpClientBuilder ->
                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        return new DevDbaasOpensearchClient(new OpenSearchClient(builder.build()));
    }
}

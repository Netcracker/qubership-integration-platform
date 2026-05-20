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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@Slf4j
@AutoConfiguration
public class OpenSearchConfiguration {
    public static final String OPENSEARCH_CLIENT_SUPPLIER_BEAN_NAME = "openSearchClientSupplier";
    public static final String OPENSEARCH_ENTITY_NAME_NORMALIZER_BEAN_NAME = "openSearchEntityNameNormalizer";
    public static final long TEMPLATE_VERSION = 4L;

    @Value("${qip.opensearch.client.host:opensearch}")
    private String host;

    @Value("${qip.opensearch.client.port:9200}")
    private Integer port;

    @Value("${qip.opensearch.client.protocol:http}")
    private String protocol;

    @Value("${qip.opensearch.client.user-name:}")
    private String username;

    @Value("${qip.opensearch.client.password:}")
    private String password;

    @Value("${qip.opensearch.client.prefix:}")
    private String prefix;

    @Bean
    @ConditionalOnMissingBean(OpenSearchClientSupplier.class)
    public OpenSearchClientSupplier openSearchClientSupplier() {
        return new DefaultOpenSearchClientSupplier(createOpenSearchClient(), prefix);
    }

    private OpenSearchClient createOpenSearchClient() {
        AuthScope authScope = new AuthScope(null, null, -1, null, null);
        Credentials credentials = new UsernamePasswordCredentials(username, password.toCharArray());
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(authScope, credentials);
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder
            .builder(new HttpHost(protocol, host, port))
            .setHttpClientConfigCallback(httpClientBuilder ->
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        return new OpenSearchClient(builder.build());
    }
}

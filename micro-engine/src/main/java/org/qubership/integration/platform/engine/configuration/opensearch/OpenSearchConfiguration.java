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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.IntegrationEngineApplication;
import org.qubership.integration.platform.engine.model.opensearch.OpenSearchFieldType;
import org.qubership.integration.platform.engine.opensearch.DefaultOpenSearchClientSupplier;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchDocument;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchField;
import org.qubership.integration.platform.engine.opensearch.ism.IndexStateManagementClient;
import org.qubership.integration.platform.engine.opensearch.ism.model.Conditions;
import org.qubership.integration.platform.engine.opensearch.ism.model.FailedIndex;
import org.qubership.integration.platform.engine.opensearch.ism.model.ISMTemplate;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.model.State;
import org.qubership.integration.platform.engine.opensearch.ism.model.Transition;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.DeleteAction;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.RolloverAction;
import org.qubership.integration.platform.engine.opensearch.ism.rest.ISMStatusResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.PolicyResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.RequestHelper;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ExpandWildcard;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.UpdateAliasesRequest;
import org.opensearch.client.opensearch.indices.update_aliases.Action;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

import static org.qubership.integration.platform.engine.opensearch.ism.rest.RequestHelper.processHttpResponse;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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

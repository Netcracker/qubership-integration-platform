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

package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.Category;
import org.apache.camel.Component;
import org.apache.camel.Producer;
import org.apache.camel.component.graphql.GraphqlEndpoint;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;

import java.util.Arrays;

@UriEndpoint(firstVersion = "3.0.0", scheme = "graphql-custom", title = "GraphQL", syntax = "graphql-custom:httpUri",
    category = { Category.API }, producerOnly = true, lenientProperties = true)
public class GraphqlCustomEndpoint extends GraphqlEndpoint {
    @UriParam(label = "advanced", description = "HTTP client configurer")
    private HttpClientConfigurer httpClientConfigurer;

    public GraphqlCustomEndpoint(String uri, Component component) {
        super(uri, component);
    }

    public HttpClientConfigurer getHttpClientConfigurer() {
        return httpClientConfigurer;
    }

    public void setHttpClientConfigurer(HttpClientConfigurer httpClientConfigurer) {
        this.httpClientConfigurer = httpClientConfigurer;
    }

    @Override
    public Producer createProducer() throws Exception {
        return new GraphqlCustomProducer(this);
    }

    @Override
    public CloseableHttpClient getHttpclient() {
        CloseableHttpClient httpClient = getHttpClient();
        if (httpClient == null) {
            httpClient = createHttpClient();
            setHttpClient(httpClient);
        }
        return httpClient;
    }

    private CloseableHttpClient createHttpClient() {
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        String proxyHost = getProxyHost();
        if (proxyHost != null) {
            String[] parts = proxyHost.split(":");
            String hostname = parts[0];
            int port = Integer.parseInt(parts[1]);
            httpClientBuilder.setProxy(new HttpHost(hostname, port));
        }
        String accessToken = getAccessToken();
        if (accessToken != null) {
            String authType = "Bearer";
            String jwtAuthorizationType = getJwtAuthorizationType();
            if (jwtAuthorizationType != null) {
                authType = jwtAuthorizationType;
            }
            httpClientBuilder.setDefaultHeaders(
                Arrays.asList(new BasicHeader(HttpHeaders.AUTHORIZATION, authType + " " + accessToken)));
        }
        String username = getUsername();
        String password = getPassword();
        if (username != null && password != null) {
            CredentialsStore credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                new AuthScope(null, -1),
                new UsernamePasswordCredentials(username, password.toCharArray()));
            httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
        }

        HttpClientConfigurer configurer = getHttpClientConfigurer();
        if (configurer != null) {
            configurer.configureHttpClient(httpClientBuilder);
        }
        return httpClientBuilder.build();
    }
}

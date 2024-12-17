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

package org.qubership.integration.platform.engine.configuration.web;

import org.eclipse.jetty.client.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JettyClientHttpConnector;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfiguration {

    @Bean
    public HttpClient httpClient(@Value("${server.max-http-request-header-size}") DataSize maxHttpHeaderSize) {
        HttpClient client = new HttpClient();
        int bufferSize = Long.valueOf(maxHttpHeaderSize.toBytes()).intValue();
        client.setRequestBufferSize(bufferSize);
        return client;
    }

    @Bean
    public ClientHttpConnector clientHttpConnector(HttpClient httpClient) {
        return new JettyClientHttpConnector(httpClient);
    }

    @Bean
    public WebClient localhostWebclient(ClientHttpConnector clientHttpConnector) {
        return WebClient.builder().clientConnector(clientHttpConnector).baseUrl("http://localhost:8080").build();
    }
}

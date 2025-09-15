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

import io.vertx.core.http.HttpClientOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WebClientConfiguration {

    @Produces
    public HttpClient httpClient(
            @ConfigProperty(name = "server.max-http-request-header-size") int maxHttpHeaderSize
    ) {
        HttpClientOptions options = new HttpClientOptions();
        options.setMaxHeaderSize(maxHttpHeaderSize);
        return Vertx.vertx().createHttpClient(options);
    }

    @Produces
    public WebClient localhostWebclient(HttpClient httpClient) {
        WebClientOptions options = new WebClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(8080);
        return WebClient.wrap(httpClient, options);
    }
}

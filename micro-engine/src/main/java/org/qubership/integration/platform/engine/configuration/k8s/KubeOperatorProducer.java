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

package org.qubership.integration.platform.engine.configuration.k8s;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import io.kubernetes.client.util.credentials.TokenFileAuthentication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;


@Slf4j
public class KubeOperatorProducer {
    @Inject
    KubernetesProperties properties;

    private interface ClientConfigurer {
        void accept(ClientBuilder builder) throws Exception;
    }

    @Produces
    @ApplicationScoped
    public KubeOperator kubeOperator() {
        try {
            log.info("Creating KubernetesOperator bean in {} mode", properties.devMode() ? "DEV" : "PROD");

            ClientBuilder builder = new ClientBuilder();
            ClientConfigurer configurer = getClientConfigurer();
            configurer.accept(builder);
            ApiClient client = builder.build();

            return new KubeOperator(client, properties.cluster().namespace(), properties.devMode());
        } catch (Exception e) {
            log.error("Invalid k8s cluster parameters, can't initialize k8s API. {}", e.getMessage());
            return new KubeOperator();
        }
    }

    private ClientConfigurer getClientConfigurer() {
        return properties.devMode() ? this::configureForDev : this::configureForProd;
    }

    private void configureForDev(ClientBuilder builder) {
        builder
                .setVerifyingSsl(false)
                .setBasePath(properties.cluster().uri())
                .setAuthentication(new AccessTokenAuthentication(properties.cluster().devToken()));
    }

    private void configureForProd(ClientBuilder builder) throws IOException {
        builder
                .setVerifyingSsl(false)
                .setBasePath(properties.cluster().uri())
                .setCertificateAuthority(Files.readAllBytes(Paths.get(properties.serviceAccount().cert())))
                .setAuthentication(new TokenFileAuthentication(properties.serviceAccount().tokenFilePath()));
    }
}

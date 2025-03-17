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

package org.qubership.integration.platform.engine.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.util.EngineDomainUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@Getter
@Setter
@Configuration
public class ServerConfiguration {
    private final ApplicationAutoConfiguration applicationConfiguration;

    private String host;

    @Value("${server.port}")
    private int port;

    private final String domain;

    public ServerConfiguration(ApplicationAutoConfiguration applicationConfiguration,
                               EngineDomainUtils engineDomainUtils) {
        this.applicationConfiguration = applicationConfiguration;
        this.domain = engineDomainUtils.extractEngineDomain(applicationConfiguration.getMicroserviceName());
    }

    @PostConstruct
    public void initHost() {
        this.host = getCurrentHost();
    }

    public EngineInfo getEngineInfo() {
        return EngineInfo.builder()
                .domain(domain)
                .engineDeploymentName(applicationConfiguration.getDeploymentName())
                .host(host)
                .build();
    }

    private String getCurrentHost() {
        try {
            var localHost = InetAddress.getLocalHost();
            return localHost.getHostAddress();
        } catch (UnknownHostException e) {
            log.error("Can't identify current host address");
        }
        return "";
    }
}

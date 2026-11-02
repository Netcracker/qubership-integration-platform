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

package org.qubership.integration.platform.engine.configuration.security;

import com.netcracker.cloud.security.core.auth.M2MManager;
import com.netcracker.cloud.security.core.utils.k8s.AudienceName;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesAudienceToken;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.util.DevModeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Optional;

@Slf4j
@Configuration
public class SecurityConfiguration {

    private static Optional<M2MManager> m2MManager = Optional.empty();
    private static DevModeUtil devModeUtil;

    public SecurityConfiguration(@Autowired Optional<M2MManager> m2MManager, DevModeUtil devModeUtil) {
        SecurityConfiguration.m2MManager = m2MManager;
        SecurityConfiguration.devModeUtil = devModeUtil;
    }

    @Deprecated
    public static String getOldM2MToken() {
        return m2MManager
                .map(manager -> manager.getToken().getTokenValue())
                .orElse("");
    }

    @Nullable
    public static String getDefaultM2MToken() {
        return getM2MToken(AudienceName.NETCRACKER);
    }

    @Nullable
    public static String getM2MToken(String audience) {
        return KubernetesAudienceToken.getToken(audience);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "httpSecurityConfigurer")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(customizer -> customizer
                .anyRequest().permitAll())
            .build();
    }
}

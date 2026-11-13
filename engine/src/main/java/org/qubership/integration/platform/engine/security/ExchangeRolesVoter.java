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

package org.qubership.integration.platform.engine.security;

import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.util.DevModeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Component
public class ExchangeRolesVoter implements AuthorizationManager<Exchange> {

    private final DevModeUtil devModeUtil;
    private final String rolePrefix;

    @Autowired
    public ExchangeRolesVoter(DevModeUtil devModeUtil, @Value("${security.rolePrefix:}") String rolePrefix) {
        this.devModeUtil = devModeUtil;
        this.rolePrefix = rolePrefix;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, Exchange exchange) {
        CipSecurityAccessPolicy accessPolicy = extractSecurityPolicy(exchange);
        if (isNull(accessPolicy) || devModeUtil.isDevMode()) {
            return new AuthorizationDecision(true);
        }

        List<String> applicableRoles = accessPolicy.getRoles().stream()
            .filter(role -> role.startsWith(rolePrefix))
            .toList();
        if (applicableRoles.isEmpty()) {
            return new AuthorizationDecision(true);
        }

        Set<String> authorities = authentication.get().getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        return new AuthorizationDecision(applicableRoles.stream().anyMatch(authorities::contains));
    }

    private CipSecurityAccessPolicy extractSecurityPolicy(Exchange exchange) {
        CipSecurityAccessPolicy accessPolicy = exchange.getProperty(
            Properties.RBAC_ACCESS_POLICY, CipSecurityAccessPolicy.class);
        if (nonNull(accessPolicy)) {
            exchange.removeProperty(Properties.RBAC_ACCESS_POLICY);
        }
        return accessPolicy;
    }
}

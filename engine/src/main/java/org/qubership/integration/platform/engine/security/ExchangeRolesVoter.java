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
import org.springframework.security.access.AccessDecisionVoter;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.access.vote.RoleVoter;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Collection;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Component
public class ExchangeRolesVoter implements AccessDecisionVoter<Exchange> {

    private final RoleVoter roleVoter;
    private final DevModeUtil devModeUtil;

    @Autowired
    public ExchangeRolesVoter(RoleVoter roleVoter, DevModeUtil devModeUtil) {
        this.roleVoter = roleVoter;
        this.devModeUtil = devModeUtil;
    }

    @Override
    public boolean supports(ConfigAttribute attribute) {
        return true;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return Exchange.class.isAssignableFrom(clazz);
    }

    @Override
    public int vote(Authentication authentication, Exchange object, Collection<ConfigAttribute> attributes) {
        QipSecurityAccessPolicy accessPolicy = extractSecurityPolicy(object);
        return isNull(accessPolicy) || devModeUtil.isDevMode() ?
                ACCESS_GRANTED
                : roleVoter.vote(authentication, object, accessPolicy.getConfigAttributes());
    }

    private QipSecurityAccessPolicy extractSecurityPolicy(Exchange exchange) {
        QipSecurityAccessPolicy accessPolicy = exchange.getProperty(
            Properties.RBAC_ACCESS_POLICY, QipSecurityAccessPolicy.class);
        if (nonNull(accessPolicy)) {
            exchange.removeProperty(Properties.RBAC_ACCESS_POLICY);
        }
        return accessPolicy;
    }
}

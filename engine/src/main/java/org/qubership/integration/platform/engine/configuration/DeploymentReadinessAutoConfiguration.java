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

import org.qubership.integration.platform.engine.events.CommonVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.SecuredVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.util.DevModeUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@AutoConfiguration
public class DeploymentReadinessAutoConfiguration {
    public static final String DEPLOYMENT_READINESS_EVENTS_BEAN = "deploymentReadinessEvents";

    @Bean(DEPLOYMENT_READINESS_EVENTS_BEAN)
    @ConditionalOnMissingBean(name = DEPLOYMENT_READINESS_EVENTS_BEAN)
    Set<Class<? extends UpdateEvent>> deploymentReadinessEvents(DevModeUtil devModeUtil) {
        Set<Class<? extends UpdateEvent>> events = new HashSet<>();
        events.add(CommonVariablesUpdatedEvent.class);
        if (!devModeUtil.isDevMode()) {
            events.add(SecuredVariablesUpdatedEvent.class);
        }
        return Collections.unmodifiableSet(events);
    }
}

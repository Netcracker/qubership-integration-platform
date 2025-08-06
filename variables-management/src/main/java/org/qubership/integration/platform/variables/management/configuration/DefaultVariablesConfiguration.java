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

package org.qubership.integration.platform.variables.management.configuration;

import org.qubership.integration.platform.variables.management.service.DefaultVariablesProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Configuration
public class DefaultVariablesConfiguration {

    @ConditionalOnMissingBean
    @Bean
    public DefaultVariablesProvider defaultVariablesProvider() {
        return new DefaultVariablesProvider() {
            @Override
            public List<String> getDefaultVariableNames() {
                return Collections.emptyList();
            }

            @Override
            public Map<String, String> provide() {
                return Collections.emptyMap();
            }
        };
    }
}

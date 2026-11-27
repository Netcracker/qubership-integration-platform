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

package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.spi.Policy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CamelSecurityAutoConfiguration {

    public CamelSecurityAutoConfiguration() {
    }

    private static Policy buildNoOpPolicy() {
        return new Policy() {
            @Override
            public void beforeWrap(Route route, NamedNode definition) {
            }

            @Override
            public Processor wrap(Route route, Processor processor) {
                return processor;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "abacPolicy")
    public Policy abacPolicy() {
        return buildNoOpPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(name = "rbacPolicy")
    public Policy rbacPolicy() {
        return buildNoOpPolicy();
    }
}

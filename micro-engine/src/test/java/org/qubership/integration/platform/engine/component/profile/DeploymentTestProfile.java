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

package org.qubership.integration.platform.engine.component.profile;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

public class DeploymentTestProfile implements QuarkusTestProfile {

    private static final String EXCLUDED_TYPES = String.join(",",
            "org.qubership.integration.platform.engine.consul.**",
            "org.qubership.integration.platform.engine.rest.**",
            "org.qubership.integration.platform.engine.scheduler.*",
            "org.qubership.integration.platform.engine.healthcheck.*",

            "org.qubership.integration.platform.engine.service.suspend.**",
            "org.qubership.integration.platform.engine.service.testing.**",
            "org.qubership.integration.platform.engine.service.xmlpreprocessor.**",

            "org.qubership.integration.platform.engine.service.deployment.actions.**",
            "org.qubership.integration.platform.engine.service.deployment.qualifiers.**",
            "org.qubership.integration.platform.engine.service.deployment.",

            "org.qubership.integration.platform.engine.service.DeploymentsUpdateService",
            "org.qubership.integration.platform.engine.service.IntegrationRuntimeService",
            "org.qubership.integration.platform.engine.service.debugger.metrics.SessionsMetricsService",
            "org.qubership.integration.platform.engine.service.debugger.metrics.SessionsMetricsServiceProducer",
            "org.qubership.integration.platform.engine.configuration.opensearch.**",
            "org.qubership.integration.platform.engine.service.debugger.sessions.**",
            "org.qubership.integration.platform.engine.service.debugger.CamelDebugger",
            "org.qubership.integration.platform.engine.configuration.SessionsMetricsServiceProducer"

    );

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("quarkus.arc.exclude-types", EXCLUDED_TYPES),

                Map.entry("quarkus.consul-config.enabled", "false"),
                Map.entry("consul.enabled", "false"),
                Map.entry("consul.keys.prefix", "qip/tests"),
                Map.entry("consul.token", "dummy"),

                Map.entry("qip.metrics.enabled", "false")
        );
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresTwoDbTestResource.class));
    }
}

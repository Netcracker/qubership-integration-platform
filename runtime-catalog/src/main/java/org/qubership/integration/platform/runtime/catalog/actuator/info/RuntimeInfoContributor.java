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

package org.qubership.integration.platform.runtime.catalog.actuator.info;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;

public class RuntimeInfoContributor implements InfoContributor {

    private final Environment environment;

    public RuntimeInfoContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> content = new HashMap<>();
        putValue(content, "java.vm.version");
        putValue(content, "java.vm.vendor");
        putValue(content, "java.vm.name");

        builder.withDetail("env", content);
    }

    private void putValue(Map<String, Object> content, String propertyName) {
        content.put(propertyName, environment.getProperty(propertyName));
    }
}

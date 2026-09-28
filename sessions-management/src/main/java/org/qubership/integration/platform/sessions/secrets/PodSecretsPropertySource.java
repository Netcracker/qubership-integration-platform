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

package org.qubership.integration.platform.sessions.secrets;

import org.springframework.core.env.PropertySource;

import java.util.Set;

class PodSecretsPropertySource extends PropertySource<Set<String>> {

    PodSecretsPropertySource(String name, Set<String> secretProperties) {
        super(name, secretProperties);
    }

    @Override
    public Object getProperty(String name) {
        if (!getSource().contains(name)) {
            return null;
        }
        return PodSecrets.readSecretFile(name, PodSecrets.DEFAULT_SECRET_FILE, PodSecrets.DEFAULT_BASE_PATH);
    }
}

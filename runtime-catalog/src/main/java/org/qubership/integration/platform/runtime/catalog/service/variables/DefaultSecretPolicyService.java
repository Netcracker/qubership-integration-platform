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

package org.qubership.integration.platform.runtime.catalog.service.variables;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DefaultSecretGoneException;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.SecretService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DefaultSecretPolicyService {

    private final SecretService secretService;
    @Getter
    private final boolean defaultSecretEnabled;

    public DefaultSecretPolicyService(
            SecretService secretService,
            @Value("${qip.variables.default-secret.enabled:false}") boolean defaultSecretEnabled) {
        this.secretService = secretService;
        this.defaultSecretEnabled = defaultSecretEnabled;
    }

    public Map<String, ? extends Map<String, String>> filterSecretsForList(
            Map<String, ? extends Map<String, String>> secrets) {
        if (defaultSecretEnabled) {
            return secrets;
        }

        return secrets.entrySet().stream()
                .filter(entry -> !secretService.isDefaultSecret(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void assertDefaultSecretAccessible(String secretName) {
        if (!defaultSecretEnabled && secretService.isDefaultSecret(secretName)) {
            throw DefaultSecretGoneException.disabled();
        }
    }

}

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

package org.qubership.integration.platform.variables.management.service.secrets;

import okhttp3.Call;
import org.qubership.integration.platform.variables.management.kubernetes.SecretUpdateCallback;

import java.util.Map;
import java.util.Set;

public interface SecretService {
    Map<String, ? extends Map<String, String>> getAllSecretsData();

    Map<String, String> getSecretData(String secretName, boolean failIfNotExist);

    boolean createSecret(String secretName);

    Map<String, String> addEntries(String secretName, Map<String, String> data, boolean init);

    Map<String, String> updateEntries(String secretName, Map<String, String> data);

    Map<String, String> removeEntries(String secretName, Set<String> keys);

    Call removeEntriesAsync(String secretName, Set<String> keys, SecretUpdateCallback callback);

    String getSecretTemplate(String secretName);

    String getDefaultSecretName();

    default boolean isDefaultSecret(String secretName) {
        String defaultSecretName = getDefaultSecretName();
        return (defaultSecretName == null && secretName == null)
                || (defaultSecretName != null && defaultSecretName.equals(secretName));
    }
}

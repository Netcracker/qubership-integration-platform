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

package org.qubership.integration.platform.variables.management.kubernetes;

import io.kubernetes.client.openapi.models.V1Secret;
import okhttp3.Call;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.lang.Nullable;

import java.util.*;

public interface KubeOperator {
    Map<String, ? extends Map<String, String>> getAllSecretsWithLabel(Pair<String, String> label);

    @Nullable
    V1Secret getSecretObjectByName(String name);

    Map<String, String> getSecretByName(String name, boolean failIfNotExist) throws KubeApiException;

    void createSecret(String name, Pair<String, String> label, Map<String, String> data);

    Map<String, String> addSecretData(String secretName, Map<String, String> data, boolean init);

    Map<String, String> removeSecretData(String secretName, Set<String> keys);

    Call removeSecretDataAsync(String secretName, Set<String> keys, SecretUpdateCallback callback);

    Map<String, String> updateSecretData(String secretName, Map<String, String> data);
}

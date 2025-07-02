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

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Secret;
import okhttp3.Call;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class LocalDevKubeOperator implements KubeOperator {
    private final ConcurrentMap<String, ConcurrentMap<String, String>> secrets;

    public LocalDevKubeOperator() {
        secrets = new ConcurrentHashMap<>();
    }

    @Override
    public Map<String, ? extends Map<String, String>> getAllSecretsWithLabel(Pair<String, String> label) {
        return secrets;
    }

    @Override
    public V1Secret getSecretObjectByName(String name) {
        var data = secrets.get(name);
        if (isNull(data)) {
            return null;
        }
        V1Secret secret = new V1Secret();
        secret.setData(data.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getBytes())));
        return secret;
    }

    @Override
    public Map<String, String> getSecretByName(String name, boolean failIfNotExist) throws KubeApiException {
        Map<String, String> data = secrets.get(name);
        if (isNull(data)) {
            if (failIfNotExist) {
                throw new KubeApiNotFoundException("Kube secret not found: " + name);
            }
            return Collections.emptyMap();
        }
        return data;
    }

    @Override
    public void createSecret(String name, Pair<String, String> label, Map<String, String> data) {
        if (nonNull(secrets.putIfAbsent(name, isNull(data) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(data)))) {
            throw new SecretAlreadyExists("Secret with name " + name + " already exists");
        }
    }

    @Override
    public Map<String, String> addSecretData(String secretName, Map<String, String> data, boolean init) {
        return modifySecretData(secretName, v -> {
            v.putAll(data);
            return v;
        });
    }

    @Override
    public Map<String, String> removeSecretData(String secretName, Set<String> keys) {
        return modifySecretData(secretName, v -> {
            keys.forEach(v::remove);
            return v;
        });
    }

    @Override
    public Call removeSecretDataAsync(String secretName, Set<String> keys, SecretUpdateCallback callback) {
        try {
            removeSecretData(secretName, keys);
            callback.onSuccess(getSecretObjectByName(secretName), HttpStatus.SC_OK, Collections.emptyMap());
        } catch (Exception e) {
            callback.onFailure(new ApiException(e), HttpStatus.SC_INTERNAL_SERVER_ERROR, Collections.emptyMap());
        }
        return null;
    }

    @Override
    public Map<String, String> updateSecretData(String secretName, Map<String, String> data) {
        return modifySecretData(secretName, v -> {
            v.putAll(data);
            return v;
        });
    }

    private Map<String, String> modifySecretData(
            String secretName,
            Function<ConcurrentMap<String, String>, ConcurrentMap<String, String>> modifier
    ) {
        var result = secrets.computeIfPresent(secretName, (k, v) -> modifier.apply(v));
        if (isNull(result)) {
            throw new KubeApiNotFoundException("Kube secret not found: " + secretName);
        }
        return result;
    }
}

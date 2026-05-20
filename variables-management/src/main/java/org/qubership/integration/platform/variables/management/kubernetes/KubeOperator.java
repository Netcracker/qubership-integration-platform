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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.model.json.JsonPatch;
import org.qubership.integration.platform.variables.management.model.json.PatchOperation;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Slf4j
public class KubeOperator {

    public static final String SECRET_LABELS_PATH = "/metadata/labels";
    public static final String SECRET_DATA_PATH = "/data";

    private static final String METADATA_NAME_FIELD = "metadata.name";
    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";

    private final ObjectMapper objectMapper;
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;

    private final String namespace;

    public KubeOperator() {
        coreApi = new CoreV1Api();
        appsApi = new AppsV1Api();
        customObjectsApi = new CustomObjectsApi();
        namespace = null;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    public KubeOperator(
            ApiClient client,
            String namespace) {

        coreApi = new CoreV1Api();
        coreApi.setApiClient(client);

        appsApi = new AppsV1Api();
        appsApi.setApiClient(client);

        customObjectsApi = new CustomObjectsApi();
        customObjectsApi.setApiClient(client);

        this.namespace = namespace;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    public ConcurrentMap<String, ConcurrentMap<String, String>> getAllSecretsWithLabel(Pair<String, String> label) {
        ConcurrentMap<String, ConcurrentMap<String, String>> secrets = new ConcurrentHashMap<>();

        try {
            V1SecretList secretList = coreApi.listNamespacedSecret(
                    namespace,
                    null,
                    null,
                    null,
                    null,
                    label.getKey() + "=" + label.getValue(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            List<V1Secret> secretListItems = secretList.getItems();
            for (V1Secret secret : secretListItems) {
                V1ObjectMeta metadata = secret.getMetadata();
                if (metadata == null) {
                    continue;
                }

                ConcurrentMap<String, String> dataMap = new ConcurrentHashMap<>();
                if (secret.getData() != null) {
                    secret.getData().forEach((k, v) -> dataMap.put(k, new String(v)));
                }
                secrets.put(metadata.getName(), dataMap);
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }

        return secrets;
    }

    @Nullable
    public V1Secret getSecretObjectByName(String name) {
        try {
            return coreApi.readNamespacedSecret(name, namespace, null);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }

            return null;
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public ConcurrentMap<String, String> getSecretByName(String name) {
        return getSecretByName(name, true);
    }

    public ConcurrentMap<String, String> getSecretByName(String name, boolean failIfNotExist) throws KubeApiException {
        ConcurrentMap<String, String> secretMap = new ConcurrentHashMap<>();

        try {
            V1Secret secret = coreApi.readNamespacedSecret(
                    name,
                    namespace,
                    null
            );

            if (secret.getData() != null) {
                secret.getData().forEach((k, v) -> secretMap.put(k, new String(v)));
            }
        } catch (ApiException e) {
            if (failIfNotExist || e.getCode() != 404) {
                if (e.getCode() == 404) {
                    throw new KubeApiNotFoundException("Kube secret not found", e);
                }

                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
        return secretMap;
    }

    public boolean secretExists(String name) throws KubeApiException {
        try {
            V1SecretList list = coreApi.listNamespacedSecret(namespace, null, null, null,
                    METADATA_NAME_FIELD + "=" + name, null, null, null,
                    null, null, null, null);
            return !list.getItems().isEmpty();
        } catch (ApiException e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public void createSecret(String name, Pair<String, String> label, Map<String, String> data) {
        try {
            Map<String, byte[]> dataByte = data == null ? Collections.emptyMap() : data.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getBytes()));

            V1Secret secret = new V1Secret();
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(name);
            metadata.setNamespace(namespace);
            metadata.setLabels(Collections.singletonMap(label.getKey(), label.getValue()));
            secret.setMetadata(metadata);
            secret.setData(dataByte);

            coreApi.createNamespacedSecret(namespace, secret, null, null, null, null);
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                throw new SecretAlreadyExists("Secret with name " + name + " already exists");
            }

            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public void patchSecret(String secretName, JsonPatch patch) {
        try {
            coreApi.patchNamespacedSecret(
                    secretName,
                    namespace,
                    new V1Patch(objectMapper.writeValueAsString(Collections.singletonList(patch))),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize secret patch request", e);
            throw new KubeApiException("Unable to serialize secret patch request", e);
        } catch (ApiException e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        }
    }

    public ConcurrentMap<String, String> addSecretData(String secretName, Map<String, String> data, boolean init) {
        List<JsonPatch> patches = new ArrayList<>();
        if (init) {
            patches.add(new JsonPatch(PatchOperation.ADD, SECRET_DATA_PATH, Collections.emptyMap()));
        }

        for (Map.Entry<String, String> entry : data.entrySet()) {
            patches.add(new JsonPatch(PatchOperation.ADD, getDataKeyPath(entry.getKey()), entry.getValue().getBytes()));
        }

        return updateSecretData(secretName, patches);
    }

    public ConcurrentMap<String, String> removeSecretData(String secretName, Set<String> keys) {
        List<JsonPatch> patches = keys.stream()
                .map(key -> new JsonPatch(PatchOperation.REMOVE, getDataKeyPath(key), null))
                .toList();
        return updateSecretData(secretName, patches);
    }

    public Call removeSecretDataAsync(String secretName, Set<String> keys, SecretUpdateCallback callback) {
        List<JsonPatch> patches = keys.stream()
                .map(key -> new JsonPatch(PatchOperation.REMOVE, getDataKeyPath(key), null))
                .toList();

        try {
            return coreApi.patchNamespacedSecretAsync(
                    secretName,
                    namespace,
                    new V1Patch(objectMapper.writeValueAsString(patches)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    callback
            );
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize secret patch request", e);
            throw new KubeApiException("Unable to serialize secret patch request", e);
        } catch (ApiException e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        }
    }

    public void setSecretByName(String name, Pair<String, String> label, Map<String, String> data, boolean failIfExists)
            throws KubeApiException {
        try {
            Map<String, byte[]> byteData = new HashMap<>();
            if (data != null) {
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    byteData.put(entry.getKey(), entry.getValue().getBytes());
                }
            }
            V1Secret secret = new V1Secret();
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(name);
            metadata.setNamespace(namespace);
            metadata.setLabels(Collections.singletonMap(label.getKey(), label.getValue()));
            secret.setMetadata(metadata);
            secret.setData(byteData);

            if (failIfExists) {
                coreApi.createNamespacedSecret(namespace, secret, null, null, null, null);
            } else {
                if (secretExists(name)) {
                    coreApi.replaceNamespacedSecret(name, namespace, secret, null, null, null, null);
                } else {
                    coreApi.createNamespacedSecret(namespace, secret, null, null, null, null);
                }
            }
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                throw new SecretAlreadyExists();
            }

            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public ConcurrentMap<String, String> updateSecretData(String secretName, Map<String, String> data) {
        List<JsonPatch> patches = data.entrySet().stream()
                .map(dataEntry -> new JsonPatch(PatchOperation.REPLACE, getDataKeyPath(dataEntry.getKey()), dataEntry.getValue().getBytes()))
                .toList();
        return updateSecretData(secretName, patches);
    }

    private ConcurrentMap<String, String> updateSecretData(String secretName, List<JsonPatch> patches) {
        ConcurrentMap<String, String> secretMap = new ConcurrentHashMap<>();

        try {
            V1Secret secret = coreApi.patchNamespacedSecret(
                    secretName,
                    namespace,
                    new V1Patch(objectMapper.writeValueAsString(patches)),
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (secret.getData() != null) {
                secret.getData().forEach((k, v) -> secretMap.put(k, new String(v)));
            }
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize secret patch request", e);
            throw new KubeApiException("Unable to serialize secret patch request", e);
        } catch (ApiException e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        }

        return secretMap;
    }

    private String getDataKeyPath(String key) {
        return SECRET_DATA_PATH + "/" + key;
    }
}

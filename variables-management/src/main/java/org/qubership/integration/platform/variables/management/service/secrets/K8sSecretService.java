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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kubernetes.client.openapi.models.V1Secret;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.kubernetes.KubeApiException;
import org.qubership.integration.platform.variables.management.kubernetes.KubeApiNotFoundException;
import org.qubership.integration.platform.variables.management.kubernetes.KubeOperator;
import org.qubership.integration.platform.variables.management.kubernetes.SecretUpdateCallback;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.SecretNotFoundException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesException;
import org.qubership.integration.platform.variables.management.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Service
public class K8sSecretService implements SecretService {
    private final KubeOperator kubeOperator;
    private final ActionsLogService actionLogger;
    private final YAMLMapper yamlMapper;
    private final Pair<String, String> kubeSecretsLabel;
    private final String kubeSecretV2Name;

    @Autowired
    public K8sSecretService(
            KubeOperator kubeOperator,
            ActionsLogService actionLogger,
            @Qualifier("yamlMapper") YAMLMapper yamlMapper,
            @Value("${kubernetes.variables-secret.label}") String label,
            @Value("${kubernetes.variables-secret.name}") String kubeSecretV2Name
    ) {
        this.kubeOperator = kubeOperator;
        this.actionLogger = actionLogger;
        this.yamlMapper = yamlMapper;
        this.kubeSecretsLabel = Pair.of(label, "secured");
        this.kubeSecretV2Name = kubeSecretV2Name;
    }

    @Override
    public Map<String, ? extends Map<String, String>> getAllSecretsData() {
        try {
            return kubeOperator.getAllSecretsWithLabel(kubeSecretsLabel);
        } catch (KubeApiException exception) {
            log.error("Can't get kube secrets {}", exception.getMessage());
            throw exception;
        }
    }

    @Override
    public Map<String, String> getSecretData(String secretName, boolean failIfNotExist) {
        try {
            return kubeOperator.getSecretByName(secretName, failIfNotExist);
        } catch (KubeApiNotFoundException exception) {
            log.error("Cannot get data from secret", exception);
            throw SecretNotFoundException.forSecretAndCause(secretName, exception);
        } catch (KubeApiException exception) {
            log.error("Can't get kube secret: {}", exception.getMessage());
            throw exception;
        }
    }

    @Override
    public boolean createSecret(String secretName) {
        try {
            if (nonNull(kubeOperator.getSecretObjectByName(secretName))) {
                return false;
            }
            kubeOperator.createSecret(secretName, kubeSecretsLabel, null);
            logAction(secretName, LogOperation.CREATE);
            return true;
        } catch (KubeApiException exception) {
            log.error("Failed to create secret", exception);
            throw exception;
        }
    }

    @Override
    public Map<String, String> addEntries(String secretName, Map<String, String> data, boolean init) {
        try {
            return kubeOperator.addSecretData(secretName, data, init);
        } catch (KubeApiException exception) {
            log.error("Failed to add data to secret", exception);
            throw exception;
        }
    }

    @Override
    public Map<String, String> updateEntries(String secretName, Map<String, String> data) {
        try {
            return kubeOperator.updateSecretData(secretName, data);
        } catch (KubeApiException exception) {
            log.error("Failed to update data to secret", exception);
            throw exception;
        }
    }

    @Override
    public Map<String, String> removeEntries(String secretName, Set<String> keys) {
        try {
            return kubeOperator.removeSecretData(secretName, keys);
        } catch (KubeApiException exception) {
            log.error("Failed to remove data from secret", exception);
            throw exception;
        }
    }

    @Override
    public Call removeEntriesAsync(String secretName, Set<String> keys, SecretUpdateCallback callback) {
        return kubeOperator.removeSecretDataAsync(secretName, keys, callback);
    }

    @Override
    public String getSecretTemplate(String secretName) {
        try {
            V1Secret secret = kubeOperator.getSecretObjectByName(secretName);
            if (isNull(secret)) {
                throw SecretNotFoundException.forSecret(secretName);
            }

            return yamlMapper.writeValueAsString(secret);
        } catch (JsonProcessingException e) {
            throw new SecuredVariablesException("Failed to get secret helm chart", e);
        }

    }

    @Override
    public String getDefaultSecretName() {
        return kubeSecretV2Name;
    }

    private void logAction(String secretName, LogOperation operation) {
        ActionLog action = ActionLog.builder()
                .entityType(EntityType.SECRET)
                .entityName(secretName)
                .operation(operation)
                .build();
        actionLogger.logAction(action);
    }
}

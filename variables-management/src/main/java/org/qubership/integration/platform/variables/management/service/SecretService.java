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

package org.qubership.integration.platform.variables.management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kubernetes.client.openapi.models.V1Secret;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.kubernetes.KubeOperator;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SecretService {

    @Getter
    private final String kubeSecretV2Name;
    @Getter
    private final Pair<String, String> kubeSecretsLabel;

    public static final String SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT = "Secret with name %s not found";

    protected final YAMLMapper yamlMapper;
    protected final ObjectMapper jsonMapper;
    protected final KubeOperator operator;
    protected final ActionsLogService actionLogger;

    @Autowired
    public SecretService(
            @Qualifier("yamlMapper") YAMLMapper yamlMapper,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            KubeOperator operator,
            ActionsLogService actionLogger,
            @Value("${kubernetes.variables-secret.label}") String kubeSecretsLabel,
            @Value("${kubernetes.variables-secret.name}") String kubeSecretV2Name
    ) {
        this.yamlMapper = yamlMapper;
        this.jsonMapper = objectMapper;
        this.operator = operator;
        this.actionLogger = actionLogger;
        this.kubeSecretV2Name = kubeSecretV2Name;
        this.kubeSecretsLabel = Pair.of(kubeSecretsLabel, "secured");
    }

    public void createSecuredVariablesSecret(String name) {
        createSecuredVariablesSecret(name, null);
    }

    public void createSecuredVariablesSecret(String name, @Nullable Map<String, String> securedVariables) {
        if (operator.getSecretObjectByName(name) != null) {
            return;
        }
        operator.createSecret(name, kubeSecretsLabel, securedVariables);

        logCreateAction(EntityType.SECRET, name);

        Set<String> variablesKeys = securedVariables == null ? Collections.emptySet() : securedVariables.keySet();
        for (String variableName : variablesKeys) {
            logCreateAction(EntityType.SECURED_VARIABLE, variableName);
        }
    }

    public String getSecretTemplate(String secretName) {
        try {
            V1Secret foundSecret = operator.getSecretObjectByName(secretName);

            if (foundSecret == null) {
                throw new SecuredVariablesNotFoundException(SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT.formatted(secretName));
            }

            return yamlMapper.writeValueAsString(foundSecret);
        } catch (JsonProcessingException e) {
            throw new SecuredVariablesException("Failed to get secret helm chart", e);
        }
    }

    public boolean isDefaultSecret(String secretName) {
        return kubeSecretV2Name.equals(secretName);
    }

    private void logCreateAction(EntityType entityType, String name) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(entityType)
                .entityName(name)
                .operation(LogOperation.CREATE)
                .build());
    }
}

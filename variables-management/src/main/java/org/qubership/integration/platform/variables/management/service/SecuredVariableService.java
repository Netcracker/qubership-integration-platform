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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.persistence.EntityExistsException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.kubernetes.KubeApiException;
import org.qubership.integration.platform.variables.management.kubernetes.KubeApiNotFoundException;
import org.qubership.integration.platform.variables.management.kubernetes.KubeOperator;
import org.qubership.integration.platform.variables.management.kubernetes.SecretUpdateCallback;
import org.qubership.integration.platform.variables.management.model.SecretEntity;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.EmptyVariableFieldException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesNotFoundException;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.SecretErrorResponse;
import org.qubership.integration.platform.variables.management.util.DevModeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class SecuredVariableService extends SecretService {

    public static final String EMPTY_SECURED_VARIABLE_NAME_ERROR_MESSAGE = "Secured variable's name is empty";

    private final CommonVariablesService commonVariablesService;
    private final Lock lock;
    private final ConcurrentMap<String, SecretEntity> securedVariablesSecrets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapperWithSorting;
    private final DevModeUtil devModeUtil;

    @Autowired
    public SecuredVariableService(
            @Qualifier("yamlMapper") YAMLMapper yamlMapper,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            KubeOperator operator,
            ActionsLogService actionLogger,
            @Value("${kubernetes.variables-secret.label}") String kubeSecretsLabel,
            @Value("${kubernetes.variables-secret.name}") String kubeSecretV2Name,
            DevModeUtil devModeUtil,
            @Lazy CommonVariablesService commonVariablesService,
            @Qualifier("objectMapperWithSorting") ObjectMapper objectMapperWithSorting
    ) {
        super(yamlMapper, objectMapper, operator, actionLogger, kubeSecretsLabel, kubeSecretV2Name);
        this.commonVariablesService = commonVariablesService;
        this.lock = new ReentrantLock(true);
        this.objectMapperWithSorting = objectMapperWithSorting;
        this.devModeUtil = devModeUtil;
    }

    public Map<String, Set<String>> getAllSecretsVariablesNames() {
        lock.lock();
        try {
            refreshAllVariablesSecrets();
            return getVariablesBySecret().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().keySet()));
        } finally {
            lock.unlock();
        }
    }

    public Set<String> getVariablesForDefaultSecret(boolean failIfSecretNotExist) {
        return getVariablesForSecret(getKubeSecretV2Name(), failIfSecretNotExist);
    }

    public Set<String> getVariablesForSecret(String secretName, boolean failIfSecretNotExist) {
        secretName = resolveSecretName(secretName);

        lock.lock();
        try {
            refreshVariablesForSecret(secretName, failIfSecretNotExist);

            SecretEntity secret = securedVariablesSecrets.get(secretName);
            if (secret == null) {
                if (failIfSecretNotExist) {
                    throw new SecuredVariablesNotFoundException(SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT.formatted(secretName));
                } else {
                    return Collections.emptySet();
                }
            }

            return secret.getVariables().keySet();
        } finally {
            lock.unlock();
        }
    }

    public Set<String> addVariablesToDefaultSecret(Map<String, String> newVariables) {
        return addVariables(getKubeSecretV2Name(), newVariables).get(getKubeSecretV2Name());
    }

    public Map<String, Set<String>> addVariables(String secretName, Map<String, String> newVariables) {
        return addVariables(secretName, newVariables, false);
    }

    public Map<String, Set<String>> addVariables(String secretName, Map<String, String> newVariables, boolean importMode) {
        if (newVariables.isEmpty()) {
            return Collections.singletonMap(secretName, Collections.emptySet());
        }

        Map<String, String> oldVariablesCopy;

        lock.lock();
        try {
            secretName = resolveSecretName(secretName);

            refreshVariablesForSecret(secretName, true);
            SecretEntity secret = securedVariablesSecrets.get(secretName);
            if (secret == null) {
                throw new SecuredVariablesNotFoundException(SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT.formatted(secretName));
            }

            ConcurrentMap<String, String> variables = new ConcurrentHashMap<>(secret.getVariables());
            oldVariablesCopy = new HashMap<>(variables);

            if (isDefaultSecret(secretName)) {
                validateSecuredVariablesUniqueness(variables, newVariables);
            }

            for (Map.Entry<String, String> securedVariable : newVariables.entrySet()) {
                validateSecuredVariable(securedVariable.getKey(), securedVariable.getValue());
            }

            updateVariablesCache(secretName, operator.addSecretData(secretName, newVariables, variables.isEmpty()));
        } finally {
            lock.unlock();
        }

        for (String name : newVariables.keySet()) {
            logSecuredVariableAction(name, secretName, importMode
                    ? LogOperation.IMPORT
                    : (oldVariablesCopy.containsKey(name) ? LogOperation.UPDATE : LogOperation.CREATE));
        }

        return Collections.singletonMap(secretName, newVariables.keySet());
    }

    public void deleteVariablesFromDefaultSecret(Set<String> variablesNames) {
        deleteVariables(getKubeSecretV2Name(), variablesNames);
    }

    public void deleteVariables(String secretName, Set<String> variablesNames) {
        deleteVariables(secretName, variablesNames, true);
    }

    public void deleteVariables(String secretName, Set<String> variablesNames, boolean logOperation) {
        secretName = resolveSecretName(secretName);
        if (CollectionUtils.isEmpty(variablesNames)) {
            return;
        }

        lock.lock();
        try {
            refreshVariablesForSecret(secretName, true);
            SecretEntity secret = securedVariablesSecrets.get(secretName);
            if (secret == null) {
                throw new SecuredVariablesNotFoundException(SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT.formatted(secretName));
            }

            updateVariablesCache(secretName, operator.removeSecretData(secretName, variablesNames));
        } finally {
            lock.unlock();
        }

        if (logOperation) {
            final String finalSecretName = secretName;
            variablesNames.forEach(name -> logSecuredVariableAction(name, finalSecretName, LogOperation.DELETE));
        }
    }

    public List<SecretErrorResponse> deleteVariablesForMultipleSecrets(Map<String, Set<String>> variablesPerSecret) {
        List<CompletableFuture<Map<String, String>>> secretUpdateFutures = new ArrayList<>();
        Map<String, Throwable> secretUpdateExceptions = new HashMap<>();

        lock.lock();
        try {
            refreshAllVariablesSecrets();
            for (Map.Entry<String, Set<String>> variablePerSecret : variablesPerSecret.entrySet()) {
                String secretName = resolveSecretName(variablePerSecret.getKey());
                Set<String> variablesToRemove = variablePerSecret.getValue();
                SecretEntity secret = securedVariablesSecrets.get(secretName);
                if (secret == null) {
                    secretUpdateExceptions.put(
                            secretName,
                            new SecuredVariablesNotFoundException(SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT.formatted(secretName))
                    );
                    continue;
                }

                try {
                    CompletableFuture<Map<String, String>> future = new CompletableFuture<Map<String, String>>()
                            .whenComplete((secretData, throwable) -> {
                                if (secretData != null) {
                                    updateVariablesCache(secretName, secretData);
                                    return;
                                }
                                if (throwable != null) {
                                    secretUpdateExceptions.put(secretName, throwable);
                                }
                            });
                    secretUpdateFutures.add(future);
                    operator.removeSecretDataAsync(secretName, variablesToRemove, new SecretUpdateCallback(future));
                } catch (Exception e) {
                    secretUpdateExceptions.putIfAbsent(
                            secretName,
                            new SecuredVariablesException("Failed to delete variables from secret: " + secretName, e)
                    );
                }
            }

            CompletableFuture.allOf(secretUpdateFutures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to delete variables", e);
            throw new SecuredVariablesException("Failed to delete variables", e);
        } finally {
            lock.unlock();
        }

        variablesPerSecret.entrySet().stream()
                .filter(entry -> !secretUpdateExceptions.containsKey(entry.getKey()))
                .forEach(entry -> entry.getValue().forEach(variable ->
                        logSecuredVariableAction(variable, entry.getKey(), LogOperation.DELETE)));
        if (!secretUpdateExceptions.isEmpty()) {
            List<SecretErrorResponse> errorResponses = new ArrayList<>();
            for (Map.Entry<String, Throwable> entry : secretUpdateExceptions.entrySet()) {
                errorResponses.add(new SecretErrorResponse(entry.getKey(), entry.getValue().getMessage()));
                log.error("Failed to delete variables from secret {}", entry.getKey(), entry.getValue());
            }
            if (secretUpdateExceptions.keySet().containsAll(variablesPerSecret.keySet())) {
                throw new SecuredVariablesException("Failed to delete variables from multiple secrets");
            }
            return errorResponses;
        }

        return Collections.emptyList();
    }

    public String updateVariableInDefaultSecret(String name, String value) {
        updateVariables(getKubeSecretV2Name(), Collections.singletonMap(name, value));
        return name;
    }

    public Pair<String, Set<String>> updateVariables(String secretName, Map<String, String> variablesToUpdate) {
        secretName = resolveSecretName(secretName);

        lock.lock();
        try {
            refreshVariablesForSecret(secretName, true);

            ConcurrentMap<String, String> variables = new ConcurrentHashMap<>(
                    securedVariablesSecrets.get(secretName).getVariables()
            );

            for (Map.Entry<String, String> variable : variablesToUpdate.entrySet()) {
                String name = variable.getKey();
                String value = variable.getValue();
                validateSecuredVariable(name, value);

                if (!variables.containsKey(name)) {
                    throw new SecuredVariablesNotFoundException("Cannot find variable " + name);
                }

                variables.put(name, isNull(value) ? "" : value);
            }

            updateVariablesCache(secretName, operator.updateSecretData(secretName, variables));
        } finally {
            lock.unlock();
        }

        final String finalSecretName = secretName;
        variablesToUpdate.keySet().forEach(name -> logSecuredVariableAction(name, finalSecretName, LogOperation.UPDATE));
        return Pair.of(secretName, variablesToUpdate.keySet());
    }

    public Set<String> importVariablesRequest(MultipartFile file) {
        Map<String, String> importedVariables;
        try {
            importedVariables = yamlMapper.readValue(new String(file.getBytes()), new TypeReference<>() {
            });
        } catch (IOException e) {
            log.error("Unable to convert file to variables {}", e.getMessage());
            throw new RuntimeException("Unable to convert file to variables");
        }
        addVariables(getKubeSecretV2Name(), importedVariables, true);

        importedVariables.keySet().forEach(name -> logSecuredVariableAction(name, getKubeSecretV2Name(), LogOperation.IMPORT));
        return importedVariables.keySet();
    }

    protected Map<String, SecretEntity> getSecuredVariablesSecrets() {
        return securedVariablesSecrets;
    }

    private void validateSecuredVariable(String name, String value) {
        if (StringUtils.isBlank(name)) {
            throw new EmptyVariableFieldException(EMPTY_SECURED_VARIABLE_NAME_ERROR_MESSAGE);
        }
    }

    private void validateSecuredVariablesUniqueness(Map<String, String> currentVariables, Map<String, String> newVariables) {
        Map<String, String> commonVariables = commonVariablesService.getVariables();
        for (Map.Entry<String, String> commonVariable : commonVariables.entrySet()) {
            String name = commonVariable.getKey();
            if (currentVariables.containsKey(name) || newVariables.containsKey(name)) {
                throw new EntityExistsException("Common variable with name " + name + " already exists");
            }
        }
    }

    private ConcurrentMap<String, ConcurrentMap<String, String>> getVariablesBySecret() {
        ConcurrentMap<String, ConcurrentMap<String, String>> variables = new ConcurrentHashMap<>();
        for (Map.Entry<String, SecretEntity> entry : securedVariablesSecrets.entrySet()) {
            variables.put(entry.getKey(), entry.getValue().getVariables());
        }

        return variables;
    }

    private void refreshAllVariablesSecrets() {
        ConcurrentMap<String, ConcurrentMap<String, String>> foundSecrets;

        try {
            foundSecrets = operator.getAllSecretsWithLabel(getKubeSecretsLabel());
        } catch (KubeApiException e) {
            log.error("Can't get kube secrets {}", e.getMessage());
            if (!devModeUtil.isDevMode()) {
                throw e;
            }
            foundSecrets = new ConcurrentHashMap<>();
        }

        securedVariablesSecrets.clear();
        for (Map.Entry<String, ConcurrentMap<String, String>> entry : foundSecrets.entrySet()) {
            String secretName = entry.getKey();
            ConcurrentMap<String, String> secretData = entry.getValue();
            updateVariablesCache(secretName, secretData);
        }
    }

    private void refreshVariablesForSecret(String secretName, boolean failIfSecretNotExist) {
        try {
            ConcurrentMap<String, String> secretData = operator.getSecretByName(secretName, failIfSecretNotExist);
            updateVariablesCache(secretName, secretData);
        } catch (KubeApiNotFoundException e) {
            log.error("Cannot get secured variables from secret", e);
            securedVariablesSecrets.remove(secretName);
            if (!devModeUtil.isDevMode()) {
                throw new SecuredVariablesNotFoundException(
                        SECRET_NOT_FOUND_ERROR_MESSAGE_FORMAT.formatted(secretName),
                        e
                );
            }
        } catch (KubeApiException e) {
            log.error("Can't get kube secret: {}", e.getMessage());
            if (!devModeUtil.isDevMode()) {
                throw e;
            }
        }
    }

    private void updateVariablesCache(String secretName, Map<String, String> variables) {
        securedVariablesSecrets.put(secretName, SecretEntity.builder()
                .secretName(secretName)
                .variables(new ConcurrentHashMap<>(variables))
                .build());
    }

    private String resolveSecretName(@Nullable String secretName) {
        return StringUtils.isBlank(secretName) || "default".equalsIgnoreCase(secretName)
                ? getKubeSecretV2Name()
                : secretName;
    }

    private void logSecuredVariableAction(String name, String secretName, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SECURED_VARIABLE)
                .entityName(name)
                .parentType(EntityType.SECRET)
                .parentName(secretName)
                .operation(operation)
                .build());
    }
}

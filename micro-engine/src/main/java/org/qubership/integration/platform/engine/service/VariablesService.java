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

package org.qubership.integration.platform.engine.service;

import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.text.StringSubstitutor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.events.CommonVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.SecuredVariablesUpdatedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@ApplicationScoped
public class VariablesService {
    private final String kubeSecretV2Name;
    private final Pair<String, String> kubeSecretsLabel;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("#\\{[a-zA-Z0-9:._-]+\\}");
    private static final String SECRET_VARIABLE_SEPARATOR = ":";
    public static final String NAMESPACE_VARIABLE = "namespace";

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final EventBus eventBus;
    private final KubeOperator operator;
    private final ApplicationConfiguration applicationConfiguration;

    private Map<String, String> commonVariables = Collections.emptyMap();
    private Map<String, String> securedVariables = Collections.emptyMap();
    private Map<String, String> mergedVariables = Collections.emptyMap();

    private StringSubstitutor substitutor;
    private StringSubstitutor substitutorEscaped;

    private boolean isInitialSecuredEvent = true;
    private boolean isInitialCommonEvent = true;

    @Inject
    public VariablesService(
            EventBus eventBus,
            KubeOperator operator,
            ApplicationConfiguration applicationConfiguration,
            @ConfigProperty(name = "kubernetes.variables-secret.label") String kubeSecretsLabel,
            @ConfigProperty(name = "kubernetes.variables-secret.name") String kubeSecretV2Name
    ) {
        this.eventBus = eventBus;
        this.operator = operator;
        this.applicationConfiguration = applicationConfiguration;
        this.kubeSecretV2Name = kubeSecretV2Name;
        this.kubeSecretsLabel = Pair.of(kubeSecretsLabel, "secured");
        updateSubstitutors(Collections.emptyMap());
    }

    public String injectVariables(String text) {
        return injectVariables(text, false);
    }

    public String injectVariables(String text, boolean escapeDesignTimeVariables) {
        if (text == null) {
            return null;
        }

        lock.readLock().lock();
        try {
            // remove empty properties in components
            for (Map.Entry<String, String> entry : mergedVariables.entrySet()) {
                if (entry.getValue().trim().isEmpty()) {
                    text = text
                        .replaceAll("(&amp;)?[a-zA-Z0-9_-]+=#?\\{" + entry.getKey() + "\\}", "");
                }
            }

            // substitute variables
            // #{var}
            if (!escapeDesignTimeVariables) {
                text = substitutor.replace(text);
            } else {
                text = substitutorEscaped.replace(text);
            }

        } finally {
            lock.readLock().unlock();
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        if (matcher.find()) {
            throw new DeploymentRetriableException(
                "Couldn't resolve variables. " + matcher.group(0) + " variable doesn't exist");
        }
        return text;
    }

    public void injectVariablesToExchangeProperties(Map<String, Object> properties) {
        ((MergedVariablesMap<String, Object>) properties.computeIfAbsent(
                CamelConstants.Properties.VARIABLES_PROPERTY_MAP_NAME, k -> new MergedVariablesMap<String, Object>()))
                .putAll(mergedVariables);
    }

    public void refreshSecuredVariables() {
        securedVariables = pollSecuredVariables();
        mergeVariables();
        eventBus.publish(UpdateEvent.EVENT_ADDRESS,
                new SecuredVariablesUpdatedEvent(this, isInitialSecuredEvent));
        if (isInitialSecuredEvent) {
            isInitialSecuredEvent = false;
        }
    }

    public void updateCommonVariables(@NonNull Map<String, String> variables) {
        variables = patchNamespaceValue(variables);
        this.commonVariables = variables;

        mergeVariables();
        eventBus.publish(UpdateEvent.EVENT_ADDRESS,
                new CommonVariablesUpdatedEvent(this, isInitialCommonEvent));
        if (isInitialCommonEvent) {
            isInitialCommonEvent = false;
        }
    }

    private Map<String, String> patchNamespaceValue(@NotNull Map<String, String> variables) {
        String namespace = applicationConfiguration.getNamespace();
        if (variables.isEmpty()) {
            return Map.of(NAMESPACE_VARIABLE, namespace);
        } else {
            variables.put(NAMESPACE_VARIABLE, namespace);
            return variables;
        }
    }

    private Map<String, String> pollSecuredVariables() {
        Map<String, String> securedVariables = new ConcurrentHashMap<>();
        try {
            for (Map.Entry<String, Map<String, String>> secretEntry : operator.getAllSecretsWithLabel(
                kubeSecretsLabel).entrySet()) {
                String secretName = secretEntry.getKey();
                Map<String, String> variables = secretEntry.getValue();

                for (Map.Entry<String, String> variablesEntry : variables.entrySet()) {
                    String key = kubeSecretV2Name.equals(secretName) ? variablesEntry.getKey()
                        : secretName + SECRET_VARIABLE_SEPARATOR + variablesEntry.getKey();

                    // merge all variables to common map
                    securedVariables.put(key, variablesEntry.getValue());
                }
            }
        } catch (KubeApiException e) {
            if (operator.isDevmode()) {
                log.debug("Can't to get secured variables from k8s");
            } else {
                log.warn("Failed to get secured variables from k8s", e);
                throw e;
            }
        }
        return securedVariables;
    }

    private void mergeVariables() {
        lock.writeLock().lock();
        try {
            // merge variables
            mergedVariables = new MergedVariablesMap<>();
            mergedVariables.putAll(commonVariables);
            mergedVariables.putAll(securedVariables);

            // build substitutors
            updateSubstitutors(mergedVariables);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void updateSubstitutors(Map<String, String> variables) {
        substitutor = buildSubst(variables, "#{");
        Map<String, String> variablesToEscape = new HashMap<>(variables);
        variablesToEscape.forEach((k, v) -> variablesToEscape.replace(k, StringEscapeUtils.escapeXml10(v)));
        substitutorEscaped = buildSubst(variablesToEscape, "#{");
    }

    private StringSubstitutor buildSubst(Map<String, String> variables, String prefix) {
        StringSubstitutor substitutor = new StringSubstitutor(variables);
        substitutor.setVariablePrefix(prefix).setVariableSuffix("}")
            .setPreserveEscapes(true)
            .setEnableUndefinedVariableException(false)
            .setDisableSubstitutionInValues(true)
            .setEnableSubstitutionInVariables(false);
        return substitutor;
    }

    public boolean hasVariableReferences(String text) {
        return VARIABLE_PATTERN.matcher(text).find();
    }
}

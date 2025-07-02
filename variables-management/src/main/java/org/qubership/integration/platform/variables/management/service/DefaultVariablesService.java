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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.variables.management.service.secrets.SecretService;
import org.slf4j.MDC;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class DefaultVariablesService {
    public static final String NAMESPACE_VARIABLE_NAME = "namespace";
    public static final String TENANT_VARIABLE_NAME = "tenant_id";
    public static final String[] DEFAULT_VARIABLES_LIST = {NAMESPACE_VARIABLE_NAME, TENANT_VARIABLE_NAME};

    private final SecretService secretService;
    private final CommonVariablesService commonVariablesService;
    private final SecuredVariableService securedVariableService;
    private final ApplicationAutoConfiguration applicationConfiguration;

    public DefaultVariablesService(
            SecretService secretService,
            CommonVariablesService commonVariablesService,
            SecuredVariableService securedVariableService,
            ApplicationAutoConfiguration applicationConfiguration
    ) {
        this.secretService = secretService;
        this.commonVariablesService = commonVariablesService;
        this.securedVariableService = securedVariableService;
        this.applicationConfiguration = applicationConfiguration;
    }

    @Retryable(
            maxAttemptsExpression = "${listeners.RestoreVariablesListener.call-retry.max-attempts}",
            backoff = @Backoff(delayExpression = "${listeners.RestoreVariablesListener.call-retry.delay}"))
    public void restoreVariables() {
        try {
            log.debug("Restore variables started");
            secretService.createSecret(secretService.getDefaultSecretName());

            Map<String, String> defaultCommonVariables = getDefaultCommonVariables();

            securedVariableService.deleteVariables(secretService.getDefaultSecretName(), defaultCommonVariables.keySet(), false);

            commonVariablesService.addVariablesUnlogged(defaultCommonVariables);
            log.debug("Restore variables finished");
        } catch (Exception e) {
            MDC.put("error_code", "8050");
            log.warn("Retry of Event Listener failed with error: failed to restore variables: {}", e.getMessage());
            MDC.remove("error_code");
            throw e;
        }
    }

    private Map<String, String> getDefaultCommonVariables() {
        Map<String, String> defaultCommonVariables = new HashMap<>();

        defaultCommonVariables.put(NAMESPACE_VARIABLE_NAME, applicationConfiguration.getNamespace());

        return defaultCommonVariables;
    }

}
